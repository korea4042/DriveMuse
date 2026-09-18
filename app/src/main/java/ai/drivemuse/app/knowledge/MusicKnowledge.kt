package ai.drivemuse.app.knowledge

import ai.drivemuse.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/*
 * Technical design v2.3 §28. Providers return ProviderRecords with provenance; they never
 * touch a domain Track. Each adapter exposes only the capabilities it really supports, and
 * a missing credential disables that adapter alone.
 */

data class ProviderRecord(val provider: ProviderId, val recordId: String, val fetchedAt: Long, val ttlMs: Long, val payload: JSONObject) { val expiresAt get() = fetchedAt + ttlMs }
data class RelatedSeed(val kind: SeedKind, val value: String, val recordId: String)

class ProviderUnavailable(val provider: ProviderId, val reason: String) : IllegalStateException("$provider: $reason")
class ProviderAuthRequired(val provider: ProviderId) : IllegalStateException("$provider needs credentials")
class ProviderRateLimited(val provider: ProviderId, val retryAfterSec: Long?) : IllegalStateException("$provider rate limited")

interface MusicKnowledgeProvider {
    val id: ProviderId
    fun capabilities(): Set<Capability>
    suspend fun lookupIdentity(title: String, artist: String, durationMs: Long?, isrc: String?): List<IdentityCandidate> = throw ProviderUnavailable(id, "LOOKUP_IDENTITY unsupported")
    suspend fun fetchMetadata(track: TrackRecord): List<MetadataAssertion> = throw ProviderUnavailable(id, "FETCH_METADATA unsupported")
    suspend fun discoverRelated(seed: DiscoverySeed): List<RelatedSeed> = throw ProviderUnavailable(id, "DISCOVER_RELATED unsupported")
}

/** Shared HTTP path: app-wide 1 req/s per provider, circuit breaker, Retry-After, response size cap. */
class ProviderHttp(private val provider: ProviderId, private val userAgent: String, private val limiter: RateLimiter = RateLimiter(1000), private val breaker: CircuitBreaker = CircuitBreaker()) {
    val breakerState get() = breaker.state
    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap(), now: () -> Long = System::currentTimeMillis): JSONObject = withContext(Dispatchers.IO) {
        if (!breaker.allow(now())) throw ProviderUnavailable(provider, "CIRCUIT_OPEN")
        delay(limiter.reserve(now()))
        val c = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 6000; readTimeout = 10000; requestMethod = "GET"; instanceFollowRedirects = false
            setRequestProperty("User-Agent", userAgent); setRequestProperty("Accept", "application/json"); headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = c.responseCode
            c.getHeaderField("X-RateLimit-Reset-In")?.toLongOrNull()?.let { if ((c.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull() ?: 1) <= 0) limiter.applyServerHint(it, now()) }
            when (code) {
                in 200..299 -> { val bytes = c.inputStream.use { it.readNBytes(1_000_001) }; check(bytes.size <= 1_000_000) { "응답 크기 초과" }; breaker.success(); if (bytes.isEmpty()) JSONObject().put("_status", code) else JSONObject(String(bytes, Charsets.UTF_8)).put("_status", code) }
                204 -> { breaker.success(); JSONObject().put("_status", 204) }
                401, 403 -> throw ProviderAuthRequired(provider)   // auth never trips the breaker (§28)
                404 -> { breaker.success(); JSONObject().put("_status", 404) }
                429, 503 -> { breaker.transientFailure(now()); throw ProviderRateLimited(provider, c.getHeaderField("Retry-After")?.toLongOrNull()) }
                else -> { breaker.transientFailure(now()); throw ProviderUnavailable(provider, "HTTP_$code") }
            }
        } catch (e: java.io.IOException) { breaker.transientFailure(now()); throw ProviderUnavailable(provider, "NETWORK") } finally { c.disconnect() }
    }
    fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}

/** MusicBrainz: public API, no key, meaningful UA, ≤1 req/s app-wide. [R15] */
class MusicBrainzAdapter(private val http: ProviderHttp, private val ttlMs: Long = 30L * 86_400_000) : MusicKnowledgeProvider {
    override val id = ProviderId.MUSICBRAINZ
    override fun capabilities() = ProviderRequirements.capabilities(id, emptySet())
    private val base = "https://musicbrainz.org/ws/2/"
    override suspend fun lookupIdentity(title: String, artist: String, durationMs: Long?, isrc: String?): List<IdentityCandidate> {
        val out = mutableListOf<IdentityCandidate>()
        if (!isrc.isNullOrBlank()) runCatching { http.getJson("${base}isrc/${http.enc(isrc)}?inc=artist-credits+releases&fmt=json") }.getOrNull()?.optJSONArray("recordings")?.let { arr -> (0 until arr.length()).forEach { out += candidate(arr.getJSONObject(it), isrc) } }
        if (title.isBlank()) return out
        val q = buildString { append("recording:\"").append(title.replace("\"", "")).append('"'); if (artist.isNotBlank()) append(" AND artist:\"").append(artist.replace("\"", "")).append('"') }
        val j = http.getJson("${base}recording?query=${http.enc(q)}&limit=8&fmt=json")
        j.optJSONArray("recordings")?.let { arr -> (0 until arr.length()).forEach { out += candidate(arr.getJSONObject(it), null) } }
        return out.distinctBy { it.recordingId }
    }
    private fun candidate(r: JSONObject, isrc: String?): IdentityCandidate {
        val credits = r.optJSONArray("artist-credit"); val artist = if (credits == null) "" else (0 until credits.length()).joinToString("") { i -> val c = credits.getJSONObject(i); c.optString("name") + c.optString("joinphrase", "") }
        val title = r.optString("title"); val disambig = r.optString("disambiguation", "")
        val version = TitleNormalizer.versionHint(title + " " + disambig)
        val isrcs = r.optJSONArray("isrcs")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return IdentityCandidate(r.getString("id"), "MUSICBRAINZ", title, artist, r.optLong("length", 0).takeIf { it > 0 }, r.optJSONArray("releases")?.optJSONObject(0)?.optString("title"), version, isrc ?: isrcs.firstOrNull(), r.optInt("score", -1).takeIf { it >= 0 }?.div(100.0))
    }
    override suspend fun fetchMetadata(track: TrackRecord): List<MetadataAssertion> {
        val mbid = track.identifiers.firstOrNull { it.type == IdentifierType.RECORDING_MBID }?.value ?: return emptyList()
        val j = http.getJson("${base}recording/${http.enc(mbid)}?inc=artist-credits+releases+tags+isrcs&fmt=json"); val now = System.currentTimeMillis(); val out = mutableListOf<MetadataAssertion>()
        j.optString("first-release-date", "").takeIf { it.isNotBlank() }?.let { d -> out += MetadataAssertion("mb:$mbid:release", track.trackId, "releaseDate", d, Basis.PROVIDER_FACT, "MUSICBRAINZ", mbid, 1.0, now, now + ttlMs, "CC0") ; out += MetadataAssertion("mb:$mbid:precision", track.trackId, "releasePrecision", when (d.length) { 4 -> "YEAR"; 7 -> "MONTH"; else -> "DAY" }, Basis.PROVIDER_FACT, "MUSICBRAINZ", mbid, 1.0, now, now + ttlMs, "CC0") }
        j.optLong("length", 0).takeIf { it > 0 }?.let { out += MetadataAssertion("mb:$mbid:length", track.trackId, "durationMs", it.toString(), Basis.PROVIDER_FACT, "MUSICBRAINZ", mbid, 1.0, now, now + ttlMs, "CC0") }
        j.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("count") >= 2 }.take(8).forEach { t -> out += MetadataAssertion("mb:$mbid:tag:${t.getString("name")}", track.trackId, "tag", t.getString("name"), Basis.COMMUNITY_TAG, "MUSICBRAINZ", mbid, (t.optInt("count") / 10.0).coerceIn(.2, .9), now, now + ttlMs, "CC0") }
        return out
    }
    override suspend fun discoverRelated(seed: DiscoverySeed): List<RelatedSeed> {
        if (seed.kind != SeedKind.PREFERRED_ARTIST) return emptyList()
        val j = http.getJson("${base}artist?query=${http.enc("artist:\"" + seed.value.replace("\"", "") + "\"")}&limit=1&fmt=json")
        val artist = j.optJSONArray("artists")?.optJSONObject(0) ?: return emptyList()
        val rel = http.getJson("${base}artist/${artist.getString("id")}?inc=artist-rels+tags&fmt=json"); val out = mutableListOf<RelatedSeed>()
        rel.optJSONArray("relations")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("target-type") == "artist" && it.optString("type") in setOf("member of band", "collaboration", "founder", "is person") }.mapNotNull { it.optJSONObject("artist")?.optString("name") }.distinct().take(5).forEach { out += RelatedSeed(SeedKind.RELATED_ARTIST, it, artist.getString("id")) } }
        rel.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("count") >= 3 }.take(3).forEach { out += RelatedSeed(SeedKind.GENRE_TAG, it.getString("name"), artist.getString("id")) } }
        return out
    }
}

/** Last.fm: track.getInfo / track.getTopTags take only an API key. No user login, no Shared Secret. [R17·R18] */
class LastFmAdapter(private val http: ProviderHttp, private val apiKey: () -> String?, private val ttlMs: Long = 30L * 86_400_000) : MusicKnowledgeProvider {
    override val id = ProviderId.LASTFM
    override fun capabilities() = ProviderRequirements.capabilities(id, if (apiKey().isNullOrBlank()) emptySet() else setOf("apiKey"))
    override suspend fun fetchMetadata(track: TrackRecord): List<MetadataAssertion> {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw ProviderAuthRequired(id)
        val base = "https://ws.audioscrobbler.com/2.0/?api_key=${http.enc(key)}&format=json&autocorrect=0&artist=${http.enc(track.primaryArtist)}&track=${http.enc(track.title)}"
        val now = System.currentTimeMillis(); val out = mutableListOf<MetadataAssertion>()
        val tags = http.getJson("$base&method=track.getTopTags")
        tags.optJSONObject("toptags")?.optJSONArray("tag")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("count") >= 10 }.take(8).forEach { t ->
            out += MetadataAssertion("lfm:${track.trackId}:tag:${t.getString("name")}", track.trackId, "tag", t.getString("name").lowercase(), Basis.COMMUNITY_TAG, "LASTFM", null, (t.optInt("count") / 100.0).coerceIn(.2, .9), now, now + ttlMs, "LASTFM_TOS")
        } }
        val info = http.getJson("$base&method=track.getInfo").optJSONObject("track")
        info?.optString("listeners", "")?.toLongOrNull()?.let { out += MetadataAssertion("lfm:${track.trackId}:listeners", track.trackId, "popularity:LASTFM", it.toString(), Basis.PROVIDER_FACT, "LASTFM", info.optString("mbid").takeIf { m -> m.isNotBlank() }, 1.0, now, now + ttlMs, "LASTFM_TOS") }
        info?.optJSONObject("album")?.optString("title", "")?.takeIf { it.isNotBlank() }?.let { out += MetadataAssertion("lfm:${track.trackId}:album", track.trackId, "album", it, Basis.PROVIDER_FACT, "LASTFM", null, .8, now, now + ttlMs, "LASTFM_TOS") }
        return out
    }
}

/** ListenBrainz: public lookups need no key; personal recommendations need a real userName (experimental endpoint). [R16] */
class ListenBrainzAdapter(private val http: ProviderHttp, private val userName: () -> String?, private val token: () -> String?, private val ttlMs: Long = 7L * 86_400_000) : MusicKnowledgeProvider {
    override val id = ProviderId.LISTENBRAINZ
    override fun capabilities() = ProviderRequirements.capabilities(id, emptySet())
    private val base = "https://api.listenbrainz.org/1/"
    override suspend fun discoverRelated(seed: DiscoverySeed): List<RelatedSeed> {
        if (seed.kind != SeedKind.LISTENING_RELATION) return emptyList()
        val user = userName()?.takeIf { it.isNotBlank() } ?: return emptyList()   // no account → skip this path, use other seeds (§28)
        val headers = token()?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Token $it") } ?: emptyMap()
        val j = http.getJson("${base}cf/recommendation/user/${http.enc(user)}/recording?count=25", headers)
        return when (ListenBrainzContract.classify(j.optInt("_status", 200))) {
            RecommendationLookup.AVAILABLE -> j.optJSONObject("payload")?.optJSONArray("mbids")?.let { a -> (0 until a.length()).mapNotNull { a.getJSONObject(it).optString("recording_mbid").takeIf { m -> m.isNotBlank() } }.map { RelatedSeed(SeedKind.LISTENING_RELATION, it, "lb:$user") } } ?: emptyList()
            RecommendationLookup.NONE_GENERATED, RecommendationLookup.NO_USER -> emptyList()
            RecommendationLookup.ERROR -> throw ProviderUnavailable(id, "RECOMMENDATION_ERROR")
        }
    }
}

/** Registry: only providers whose capability set is non-empty participate; others are reported as unavailable, not as errors. */
class ProviderRegistry(private val providers: List<MusicKnowledgeProvider>) {
    fun with(c: Capability) = providers.filter { c in it.capabilities() }
    fun status(): Map<ProviderId, Set<Capability>> = providers.associate { it.id to it.capabilities() }
}
