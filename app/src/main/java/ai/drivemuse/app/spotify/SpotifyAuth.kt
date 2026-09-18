package ai.drivemuse.app.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection

/** Raised when a Spotify call needs the user to sign in again. */
class SpotifyAuthRequired(message: String = "Spotify 계정 연결이 필요합니다") : IllegalStateException(message)

/**
 * Technical design v2.3 §22, adapted for Spotify. An installed app cannot keep a client secret,
 * so this is Authorization Code with PKCE: the verifier never leaves the device and there is no
 * secret to enter. The refresh token is the only durable credential and lives in the encrypted
 * store, next to the other provider credentials.
 */
class SpotifyAuth(
    private val context: Context,
    private val clientId: () -> String?,
    private val readRefresh: () -> String?,
    private val writeRefresh: (String?) -> Unit
) {
    companion object {
        const val REDIRECT = "drivemuse://spotify-callback"
        /** Read profile and library, read playback state, and control playback (Premium only). */
        val SCOPES = listOf(
            "user-read-email", "user-read-private",
            "user-library-read", "user-top-read", "user-follow-read", "user-read-recently-played",
            "user-read-playback-state", "user-modify-playback-state"
        )
    }

    private val mutex = Mutex()
    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt = 0L
    @Volatile private var verifier: String? = null

    val linked get() = !readRefresh().isNullOrBlank()

    /** Builds the consent URL and hands it to the browser; the redirect comes back to MainActivity. */
    fun authorizeIntent(): Intent? {
        val id = clientId()?.trim().orEmpty()
        if (id.isBlank()) return null
        val v = randomString(64).also { verifier = it }
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.US_ASCII)))
        val url = "https://accounts.spotify.com/authorize?" + listOf(
            "client_id" to id, "response_type" to "code", "redirect_uri" to REDIRECT,
            "code_challenge_method" to "S256", "code_challenge" to challenge,
            "scope" to SCOPES.joinToString(" "), "state" to randomString(16)
        ).joinToString("&") { (k, value) -> k + "=" + URLEncoder.encode(value, "UTF-8") }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Consumes the redirect. Returns the error description when Spotify refused. */
    suspend fun onRedirect(uri: Uri): String? {
        uri.getQueryParameter("error")?.let { return it }
        val code = uri.getQueryParameter("code") ?: return "code_missing"
        val v = verifier ?: return "verifier_missing"
        val id = clientId()?.trim().orEmpty().ifBlank { return "client_id_missing" }
        return try {
            val body = form(mapOf(
                "grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to REDIRECT, "client_id" to id, "code_verifier" to v))
            store(token(body)); verifier = null; null
        } catch (e: Exception) { e.message ?: "token_exchange_failed" }
    }

    /** A valid access token, refreshing when needed. */
    suspend fun token(): String = mutex.withLock {
        accessToken?.takeIf { System.currentTimeMillis() < expiresAt - 60_000 }?.let { return it }
        val refresh = readRefresh()?.takeIf { it.isNotBlank() } ?: throw SpotifyAuthRequired()
        val id = clientId()?.trim().orEmpty()
        if (id.isBlank()) throw SpotifyAuthRequired("Spotify Client ID가 필요합니다")
        val json = try {
            token(form(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to id)))
        } catch (e: Exception) { writeRefresh(null); throw SpotifyAuthRequired() }
        store(json)
        accessToken ?: throw SpotifyAuthRequired()
    }

    fun signOut() { accessToken = null; expiresAt = 0; writeRefresh(null) }

    private fun store(json: JSONObject) {
        accessToken = json.optString("access_token").takeIf { it.isNotBlank() }
        expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        // A rotated refresh token replaces the old one; an omitted one means keep what we have.
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { writeRefresh(it) }
    }

    private fun form(values: Map<String, String>) =
        values.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

    private suspend fun token(body: String): JSONObject = withContext(Dispatchers.IO) {
        val c = (URL("https://accounts.spotify.com/api/token").openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 10000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val detail = c.errorStream?.use { String(it.readNBytes(100_000), Charsets.UTF_8) }.orEmpty()
                error("Spotify 토큰 오류 $code ${detail.take(200)}")
            }
            JSONObject(c.inputStream.use { String(it.readNBytes(200_000), Charsets.UTF_8) })
        } finally { c.disconnect() }
    }

    private fun randomString(bytes: Int) = base64Url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    private fun base64Url(data: ByteArray) =
        android.util.Base64.encodeToString(data, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
}
