package ai.drivemuse.domain

/*
 * Technical design v2.3 §19, §22, §23, §28.
 * Provider contracts are declared per capability; a provider without credentials disables
 * its capabilities and nothing else. Credential requirements are per provider — there is no
 * shared "Client ID + Client Secret" assumption.
 */

enum class Capability { LOOKUP_IDENTITY, FETCH_METADATA, DISCOVER_RELATED, DISCOVER_RESOURCES }
enum class ProviderId { YOUTUBE, SPOTIFY, MUSICBRAINZ, LASTFM, LISTENBRAINZ, FIREBASE_AI, GEMINI_DIRECT, WEATHER }
enum class AuthMode { NONE, API_KEY, OAUTH_GOOGLE, OAUTH_PKCE, USERNAME_OPTIONAL_TOKEN, FIREBASE_CONFIG, PENDING_PROVIDER_SELECTION }

data class CredentialField(val key: String, val label: String, val secret: Boolean, val required: Boolean)

/** §22 table: exactly the fields each provider needs. No Client Secret field exists in the base scope. */
object ProviderRequirements {
    fun fields(p: ProviderId): List<CredentialField> = when (p) {
        // §22: a linked Google account already authorizes public reads, so the key is a fallback for signed-out use.
        ProviderId.YOUTUBE -> listOf(CredentialField("apiKey", "YouTube Data API 키 (계정 연결 시 생략 가능)", true, false))
        // §22: an Android Spotify app is a public client — PKCE, so a Client ID and no secret.
        ProviderId.SPOTIFY -> listOf(CredentialField("clientId", "Spotify Client ID", false, true))
        ProviderId.MUSICBRAINZ -> emptyList()
        ProviderId.LASTFM -> listOf(CredentialField("apiKey", "Last.fm API 키", true, true))
        ProviderId.LISTENBRAINZ -> listOf(CredentialField("userName", "ListenBrainz 사용자명 (선택)", false, false), CredentialField("token", "ListenBrainz 토큰 (개인 기능에만)", true, false))
        ProviderId.FIREBASE_AI -> listOf(CredentialField("projectId", "Firebase projectId", false, true), CredentialField("applicationId", "Firebase applicationId", false, true), CredentialField("apiKey", "Firebase API key", true, true), CredentialField("modelId", "모델 ID", false, true))
        ProviderId.GEMINI_DIRECT -> listOf(CredentialField("apiKey", "개인 Gemini API 키", true, true), CredentialField("modelId", "모델 ID", false, true))
        ProviderId.WEATHER -> emptyList()   // Open-Meteo needs no credential (§4)
    }
    fun authMode(p: ProviderId) = when (p) {
        ProviderId.YOUTUBE -> AuthMode.API_KEY; ProviderId.SPOTIFY -> AuthMode.OAUTH_PKCE; ProviderId.MUSICBRAINZ -> AuthMode.NONE; ProviderId.LASTFM -> AuthMode.API_KEY
        ProviderId.LISTENBRAINZ -> AuthMode.USERNAME_OPTIONAL_TOKEN; ProviderId.FIREBASE_AI -> AuthMode.FIREBASE_CONFIG
        ProviderId.GEMINI_DIRECT -> AuthMode.API_KEY; ProviderId.WEATHER -> AuthMode.NONE
    }
    /** Which capabilities remain when the given credential keys are present. */
    fun capabilities(p: ProviderId, present: Set<String>): Set<Capability> {
        val required = fields(p).filter { it.required }.map { it.key }
        if (required.any { it !in present }) return emptySet()
        return when (p) {
            ProviderId.YOUTUBE -> setOf(Capability.DISCOVER_RESOURCES, Capability.FETCH_METADATA)
            // Spotify identifies the recording itself and can control playback, so it covers both.
            ProviderId.SPOTIFY -> setOf(Capability.DISCOVER_RESOURCES, Capability.FETCH_METADATA, Capability.DISCOVER_RELATED)
            ProviderId.MUSICBRAINZ -> setOf(Capability.LOOKUP_IDENTITY, Capability.FETCH_METADATA, Capability.DISCOVER_RELATED)
            ProviderId.LASTFM -> setOf(Capability.FETCH_METADATA)
            ProviderId.LISTENBRAINZ -> setOf(Capability.DISCOVER_RELATED) // personal recommendations need userName; checked at call time
            else -> emptySet()
        }
    }
    /** Keys that must never be asked for: a Shared Secret or Client Secret is not part of these contracts. */
    fun forbidden(p: ProviderId): Set<String> = when (p) { ProviderId.LASTFM -> setOf("sharedSecret"); ProviderId.YOUTUBE -> setOf("clientSecret"); else -> setOf("clientSecret") }
}

enum class IntegrationStatus { UNCONFIGURED, DRAFT, VALIDATING, READY, ERROR }
enum class IntegrationError { NONE, FORMAT, API_NOT_ENABLED, KEY_RESTRICTED, PERMISSION, QUOTA, NETWORK, UNKNOWN }

data class IntegrationConfig(
    val provider: ProviderId, val authMode: AuthMode = ProviderRequirements.authMode(provider),
    val clientId: String? = null, val endpointId: String? = null, val modelId: String? = null,
    val credentialRef: String? = null, val configVersion: Long = 0,
    val status: IntegrationStatus = IntegrationStatus.UNCONFIGURED, val error: IntegrationError = IntegrationError.NONE,
    val lastValidatedAt: Long? = null, val presentKeys: Set<String> = emptySet()
) {
    val ready get() = status == IntegrationStatus.READY
    val capabilities get() = if (ready) ProviderRequirements.capabilities(provider, presentKeys) else emptySet()
}

object IntegrationPolicy {
    /** §24: a saved "on" flag never shows as on while the integration is not READY. */
    fun effectiveEnabled(requested: Boolean, configReady: Boolean) = requested && configReady
    /** Format check is a pre-condition, never a success (§22). */
    fun formatOk(p: ProviderId, values: Map<String, String>): Boolean {
        val fields = ProviderRequirements.fields(p)
        if (values.keys.any { k -> k in ProviderRequirements.forbidden(p) }) return false
        if (fields.filter { it.required }.any { values[it.key].isNullOrBlank() }) return false
        return values.values.all { it.length <= 512 && !it.contains('\n') }
    }
    /** Draft → validating; active config untouched until validation passes. */
    fun beginValidation(active: IntegrationConfig, draftKeys: Set<String>): IntegrationConfig = active.copy(status = IntegrationStatus.VALIDATING, presentKeys = draftKeys)
    /** Atomic promote: version increments; a failed draft leaves the previous READY config in place. */
    fun promote(active: IntegrationConfig, validatedDraft: IntegrationConfig, now: Long): IntegrationConfig =
        validatedDraft.copy(status = IntegrationStatus.READY, error = IntegrationError.NONE, configVersion = active.configVersion + 1, lastValidatedAt = now)
    fun fail(active: IntegrationConfig, previous: IntegrationConfig?, error: IntegrationError): IntegrationConfig =
        previous?.takeIf { it.ready } ?: active.copy(status = IntegrationStatus.ERROR, error = error)
    /** §23 T17: responses carrying an older configVersion are discarded. */
    fun accepts(activeVersion: Long, responseVersion: Long) = responseVersion == activeVersion
    fun remove(active: IntegrationConfig) = IntegrationConfig(active.provider, configVersion = active.configVersion + 1)
}

/** §28: one request per second per provider, serialized app-wide; pure decision so tests are deterministic. */
class RateLimiter(private val minIntervalMs: Long = 1000) {
    private var lastAt = Long.MIN_VALUE
    /** Returns how long to wait before the next request may start, then reserves that slot. */
    @Synchronized fun reserve(now: Long): Long { val start = if (lastAt == Long.MIN_VALUE) now else maxOf(now, lastAt + minIntervalMs); lastAt = start; return start - now }
    @Synchronized fun applyServerHint(resetInSec: Long, now: Long) { lastAt = maxOf(lastAt, now + resetInSec * 1000 - minIntervalMs) }
}

/** §28 breaker: 3 consecutive transient failures → open 15 min → one probe. Auth errors don't trip it (they wait for re-login). */
class CircuitBreaker(private val threshold: Int = 3, private val openMs: Long = 15 * 60_000) {
    enum class State { CLOSED, OPEN, HALF_OPEN }
    var state = State.CLOSED; private set
    private var failures = 0; private var openedAt = 0L; private var probing = false
    @Synchronized fun allow(now: Long): Boolean = when (state) {
        State.CLOSED -> true
        State.OPEN -> if (now - openedAt >= openMs) { state = State.HALF_OPEN; probing = true; true } else false
        State.HALF_OPEN -> false   // exactly one probe is in flight until success() or transientFailure()
    }
    @Synchronized fun success() { failures = 0; state = State.CLOSED }
    @Synchronized fun transientFailure(now: Long) { failures++; if (state == State.HALF_OPEN || failures >= threshold) { state = State.OPEN; openedAt = now; probing = false } }
}

/** §19 quota ledger entry: reservations count as consumed until released; failures still consume (conservative). */
data class QuotaWindow(val budgetScope: String, val windowKey: String, val endpoint: String, val reserved: Int = 0, val consumed: Int = 0, val limit: Int, val resetAt: Long) {
    fun canReserve(cost: Int) = reserved + consumed + cost <= limit
    fun reserve(cost: Int) = if (canReserve(cost)) copy(reserved = reserved + cost) else null
    fun settle(cost: Int) = copy(reserved = (reserved - cost).coerceAtLeast(0), consumed = consumed + cost)
    fun expired(now: Long) = now >= resetAt
}
object QuotaWindows {
    /** Local protection window in a stated zone; the provider's own reset is tracked separately. */
    fun localDayKey(nowMs: Long, zoneOffsetMs: Long) = ((nowMs + zoneOffsetMs) / 86_400_000).toString()
}

/** §19 backoff: exponential with jitter, capped, and Retry-After wins. */
object Backoff {
    fun delayMs(attempt: Int, retryAfterSec: Long? = null, jitter: Double = 0.0, baseMs: Long = 30_000, capMs: Long = 30 * 60_000): Long {
        if (retryAfterSec != null) return retryAfterSec * 1000
        val exp = (baseMs * (1L shl attempt.coerceIn(0, 10))).coerceAtMost(capMs)
        return (exp * (1 + jitter.coerceIn(0.0, .5))).toLong().coerceAtMost(capMs)
    }
    fun shouldHoldUntilNextPeriod(consecutiveFailures: Int) = consecutiveFailures >= 3
}

/** ListenBrainz recommendation endpoint contract (§28): 204 = none generated, 404 = no such user. */
enum class RecommendationLookup { AVAILABLE, NONE_GENERATED, NO_USER, ERROR }
object ListenBrainzContract {
    fun classify(httpStatus: Int) = when (httpStatus) { 200 -> RecommendationLookup.AVAILABLE; 204 -> RecommendationLookup.NONE_GENERATED; 404 -> RecommendationLookup.NO_USER; else -> RecommendationLookup.ERROR }
}
