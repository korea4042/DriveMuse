package ai.drivemuse.app.integration

import ai.drivemuse.app.DriveDatabase
import ai.drivemuse.app.catalog.IntegrationConfigEntity
import ai.drivemuse.domain.*
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * Technical design v2.3 §22–§23.
 * Secrets never enter Room/DataStore/logs/prompts. They are AES-GCM encrypted with an
 * Android Keystore key and kept in a backup-excluded, app-private file keyed by credentialRef.
 * A draft is tested first; only a passing draft replaces the active config, atomically,
 * bumping configVersion (T14–T18).
 */

class CredentialStore(context: Context) {
    private val alias = "drivemuse.credentials.v1"
    private val file = File(context.noBackupFilesDir, "credentials.bin")
    private val lock = Any()
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun load(): JSONObject = synchronized(lock) {
        if (!file.exists()) return JSONObject()
        runCatching {
            val bytes = file.readBytes(); val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        }.getOrElse { JSONObject() }   // corrupted key/file → re-entry required, never a crash (§23)
    }
    private fun save(j: JSONObject) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeBytes(cipher.iv + cipher.doFinal(j.toString().toByteArray(Charsets.UTF_8))); if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
    fun put(ref: String, values: Map<String, String>) { val j = load(); j.put(ref, JSONObject(values)); save(j) }
    fun get(ref: String?): Map<String, String> { if (ref == null) return emptyMap(); val o = load().optJSONObject(ref) ?: return emptyMap(); return o.keys().asSequence().associateWith { o.getString(it) } }
    fun remove(ref: String?) { if (ref == null) return; val j = load(); j.remove(ref); save(j) }
    fun clear() { synchronized(lock) { file.delete(); runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } } } }
}

/** Result of a minimal live check for one provider; the repository maps it onto IntegrationError. */
data class ProbeResult(val ok: Boolean, val error: IntegrationError = IntegrationError.NONE, val detail: String = "")

class IntegrationConfigRepository(private val db: DriveDatabase, private val credentials: CredentialStore, private val legacyDefaults: Map<ProviderId, Map<String, String>> = emptyMap()) {
    private val dao = db.catalog(); private val mutex = Mutex()
    /** Listeners (API clients, collection generation) are re-created on each promote. */
    var onPromoted: suspend (IntegrationConfig) -> Unit = {}

    fun observe(): Flow<Map<ProviderId, IntegrationConfig>> = dao.observeIntegrations().map { rows -> ProviderId.values().associateWith { p -> rows.firstOrNull { it.provider == p.name }?.domain() ?: IntegrationConfig(p) } }
    suspend fun active(p: ProviderId): IntegrationConfig = dao.integration(p.name)?.domain() ?: IntegrationConfig(p)

    /** Secret material for a client; BuildConfig values act only as an optional default that a user deletion does not resurrect (§23). */
    suspend fun secrets(p: ProviderId): Map<String, String> {
        val cfg = active(p)
        if (cfg.status == IntegrationStatus.UNCONFIGURED && cfg.configVersion == 0L) return legacyDefaults[p]?.filterValues { it.isNotBlank() } ?: emptyMap()
        return if (cfg.ready) credentials.get(cfg.credentialRef) else emptyMap()
    }

    /**
     * Draft → VALIDATING → READY | ERROR. The probe runs against the draft values; the active
     * config is untouched until success. Returns the resulting config (never the secret).
     */
    suspend fun applyDraft(p: ProviderId, values: Map<String, String>, probe: suspend (Map<String, String>) -> ProbeResult): IntegrationConfig = mutex.withLock {
        val clean = values.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }
        if (!IntegrationPolicy.formatOk(p, clean)) return@withLock persist(active(p).copy(status = IntegrationStatus.ERROR, error = IntegrationError.FORMAT))
        val previous = active(p)
        val draftRef = "draft:${p.name}:${System.nanoTime()}"; credentials.put(draftRef, clean.filterKeys { k -> ProviderRequirements.fields(p).firstOrNull { it.key == k }?.secret == true })
        persist(IntegrationPolicy.beginValidation(previous, clean.keys))
        val result = try { probe(clean) } catch (e: Exception) { ProbeResult(false, IntegrationError.NETWORK, e::class.simpleName ?: "") }
        if (!result.ok) { credentials.remove(draftRef); return@withLock persist(IntegrationPolicy.fail(previous, previous, result.error)) }
        val now = System.currentTimeMillis(); val activeRef = "active:${p.name}:$now"
        credentials.put(activeRef, credentials.get(draftRef)); credentials.remove(draftRef)
        val promoted = IntegrationPolicy.promote(previous, previous.copy(credentialRef = activeRef, clientId = clean["clientId"], endpointId = clean["projectId"] ?: clean["endpointId"], modelId = clean["modelId"], presentKeys = clean.keys), now)
        db.withTransaction { dao.putIntegration(IntegrationConfigEntity.from(promoted)); dao.bumpGeneration("default") }
        previous.credentialRef?.let { credentials.remove(it) }
        onPromoted(promoted); promoted
    }

    /** Cancel provider jobs → deactivate reference → delete secret → clear auth-derived caches (order from §23). */
    suspend fun remove(p: ProviderId, afterDeactivate: suspend () -> Unit = {}): IntegrationConfig = mutex.withLock {
        val previous = active(p); val next = IntegrationPolicy.remove(previous)
        db.withTransaction { dao.putIntegration(IntegrationConfigEntity.from(next)); dao.bumpGeneration("default") }
        credentials.remove(previous.credentialRef); afterDeactivate(); onPromoted(next); next
    }

    /** At start: a READY config whose secret vanished (key rotation, restore) drops to ERROR and asks for re-entry. */
    suspend fun revalidateOnStart() = mutex.withLock {
        ProviderId.values().forEach { p -> val c = active(p); if (c.ready && ProviderRequirements.fields(p).any { it.secret && it.required } && credentials.get(c.credentialRef).isEmpty()) persist(c.copy(status = IntegrationStatus.ERROR, error = IntegrationError.UNKNOWN)) }
    }
    suspend fun clearAll() = mutex.withLock { ProviderId.values().forEach { dao.deleteIntegration(it.name) }; credentials.clear() }

    private suspend fun persist(c: IntegrationConfig): IntegrationConfig { dao.putIntegration(IntegrationConfigEntity.from(c)); return c }
}

/** Minimal live probes per provider (§22: format alone is never "connected"). Each is one small read. */
object Probes {
    fun youtube(api: (String) -> ai.drivemuse.app.YouTubeApi): suspend (Map<String, String>) -> ProbeResult = { v ->
        try { api(v["apiKey"].orEmpty()).popularMusic("KR"); ProbeResult(true) }
        catch (e: ai.drivemuse.app.UserAuthRequiredException) { ProbeResult(false, IntegrationError.PERMISSION, e.message ?: "") }
        catch (e: ai.drivemuse.app.ApiNotConfiguredException) { ProbeResult(false, IntegrationError.API_NOT_ENABLED, e.message ?: "") }
        catch (e: ai.drivemuse.app.QuotaExceededException) { ProbeResult(false, IntegrationError.QUOTA) }
        catch (e: IllegalStateException) { ProbeResult(false, when { "API" in (e.message ?: "") && "사용" in (e.message ?: "") -> IntegrationError.API_NOT_ENABLED; "제한" in (e.message ?: "") -> IntegrationError.KEY_RESTRICTED; "403" in (e.message ?: "") -> IntegrationError.PERMISSION; else -> IntegrationError.NETWORK }, e.message ?: "") }
    }
    fun firebaseAi(context: android.content.Context): suspend (Map<String, String>) -> ProbeResult = { v ->
        val app = ai.drivemuse.app.gemini.FirebaseRuntime.app(context, v)
        when {
            app == null -> ProbeResult(false, IntegrationError.PERMISSION, "Firebase 구성으로 초기화하지 못했습니다")
            v["modelId"].isNullOrBlank() -> ProbeResult(false, IntegrationError.API_NOT_ENABLED, "모델 ID가 필요합니다")
            else -> try {
                kotlinx.coroutines.withTimeout(20_000) {
                    com.google.firebase.Firebase.ai(app = app, backend = com.google.firebase.ai.type.GenerativeBackend.googleAI())
                        .generativeModel(modelName = v.getValue("modelId")).generateContent("ping")
                }
                ProbeResult(true)
            } catch (e: Exception) {
                val m = e.message ?: ""
                ProbeResult(false, when {
                    "PERMISSION" in m || "403" in m || "App Check" in m -> IntegrationError.PERMISSION
                    "not found" in m || "404" in m || "model" in m.lowercase() -> IntegrationError.API_NOT_ENABLED
                    "quota" in m.lowercase() || "429" in m -> IntegrationError.QUOTA
                    else -> IntegrationError.NETWORK
                }, m.take(200))
            }
        }
    }
    fun lastFm(http: ai.drivemuse.app.knowledge.ProviderHttp): suspend (Map<String, String>) -> ProbeResult = { v ->
        try {
            val j = http.getJson("https://ws.audioscrobbler.com/2.0/?method=track.getTopTags&api_key=${http.enc(v.getValue("apiKey"))}&artist=cher&track=believe&format=json")
            if (j.has("error")) ProbeResult(false, if (j.optInt("error") == 10) IntegrationError.PERMISSION else IntegrationError.UNKNOWN, j.optString("message")) else ProbeResult(true)
        } catch (e: ai.drivemuse.app.knowledge.ProviderAuthRequired) { ProbeResult(false, IntegrationError.PERMISSION) } catch (e: Exception) { ProbeResult(false, IntegrationError.NETWORK) }
    }
    fun listenBrainz(http: ai.drivemuse.app.knowledge.ProviderHttp): suspend (Map<String, String>) -> ProbeResult = { v ->
        val user = v["userName"]
        if (user.isNullOrBlank()) ProbeResult(true)   // public-only mode needs nothing
        else try { val j = http.getJson("https://api.listenbrainz.org/1/user/${http.enc(user)}/listen-count"); if (j.optInt("_status", 200) == 404) ProbeResult(false, IntegrationError.PERMISSION, "사용자 없음") else ProbeResult(true) } catch (e: Exception) { ProbeResult(false, IntegrationError.NETWORK) }
    }
}
