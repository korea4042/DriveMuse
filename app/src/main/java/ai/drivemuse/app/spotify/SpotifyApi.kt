package ai.drivemuse.app.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** Premium is required for playback control; the Web API says so with 403 PREMIUM_REQUIRED. */
class SpotifyPremiumRequired : IllegalStateException("Spotify Premium 계정에서만 재생을 제어할 수 있어요")
class SpotifyNoActiveDevice : IllegalStateException("재생할 기기를 찾지 못했어요. Spotify 앱을 먼저 실행해 주세요")
class SpotifyUnavailable(message: String) : IllegalStateException(message)

/** One track as Spotify returns it. Unlike a video id, this identifies the recording itself. */
data class SpotifyTrack(
    val id: String, val name: String, val artists: List<String>, val artistIds: List<String>,
    val album: String?, val releaseDate: String?, val durationMs: Long,
    val popularity: Int?, val isrc: String?, val explicit: Boolean
) {
    val uri get() = "spotify:track:$id"
}

/** What the player is doing right now, as one poll of /me/player sees it. */
data class SpotifyPlayback(
    val trackId: String?, val playing: Boolean, val positionMs: Long,
    val durationMs: Long, val deviceId: String?, val deviceName: String?, val observedAt: Long
)

/**
 * Technical design v2.3 §3 and §28, Spotify edition. Everything goes through the Web API with the
 * user's bearer token: there is no API-key path, because every endpoint here is tied to the account.
 * Playback control needs Premium and an active device, and both failures are reported as such
 * rather than as a generic error — §9 shows different recovery steps for each.
 */
class SpotifyApi(private val auth: SpotifyAuth) {
    private val base = "https://api.spotify.com/v1/"

    private suspend fun request(method: String, path: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null, retry: Boolean = true): JSONObject? = withContext(Dispatchers.IO) {
        val token = auth.token()
        val query = if (params.isEmpty()) "" else "?" + params.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val c = (URL(base + path + query).openConnection() as HttpsURLConnection).apply {
            requestMethod = method; connectTimeout = 6000; readTimeout = 10000; instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            body?.let { b -> c.outputStream.use { it.write(b.toString().toByteArray(Charsets.UTF_8)) } }
            when (val code = c.responseCode) {
                204 -> null                       // no content: nothing is playing, or the command was accepted
                in 200..299 -> {
                    val bytes = c.inputStream.use { it.readNBytes(2_000_001) }
                    if (bytes.isEmpty()) null else JSONObject(String(bytes, Charsets.UTF_8))
                }
                401 -> throw SpotifyAuthRequired()
                403 -> {
                    val detail = c.errorStream?.use { String(it.readNBytes(100_000), Charsets.UTF_8) }.orEmpty()
                    if ("premium" in detail.lowercase()) throw SpotifyPremiumRequired() else throw SpotifyUnavailable(reason(detail, code))
                }
                404 -> {
                    val detail = c.errorStream?.use { String(it.readNBytes(100_000), Charsets.UTF_8) }.orEmpty()
                    if ("device" in detail.lowercase()) throw SpotifyNoActiveDevice() else throw SpotifyUnavailable(reason(detail, code))
                }
                429 -> {
                    val wait = c.getHeaderField("Retry-After")?.toLongOrNull() ?: 2
                    if (!retry) throw SpotifyUnavailable("요청이 많아 잠시 후 다시 시도해요")
                    delay(wait.coerceAtMost(10) * 1000)
                    return@withContext request(method, path, params, body, retry = false)
                }
                else -> throw SpotifyUnavailable(reason(c.errorStream?.use { String(it.readNBytes(100_000), Charsets.UTF_8) }.orEmpty(), code))
            }
        } finally { c.disconnect() }
    }

    private fun reason(detail: String, code: Int) =
        runCatching { JSONObject(detail).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
            .ifBlank { "Spotify 응답 $code" }

    // ---- library and discovery -------------------------------------------------------------

    suspend fun me(): JSONObject? = request("GET", "me")

    suspend fun search(query: String, limit: Int = 20): List<SpotifyTrack> =
        request("GET", "search", mapOf("q" to query.take(200), "type" to "track", "limit" to limit.coerceIn(1, 50).toString()))
            ?.optJSONObject("tracks")?.optJSONArray("items").toTracks()

    suspend fun savedTracks(limit: Int = 50): List<SpotifyTrack> =
        request("GET", "me/tracks", mapOf("limit" to limit.coerceIn(1, 50).toString()))
            ?.optJSONArray("items")?.let { items ->
                (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optJSONObject("track")?.let(::track) }
            }.orEmpty()

    suspend fun topTracks(timeRange: String = "medium_term", limit: Int = 50): List<SpotifyTrack> =
        request("GET", "me/top/tracks", mapOf("time_range" to timeRange, "limit" to limit.coerceIn(1, 50).toString()))
            ?.optJSONArray("items").toTracks()

    suspend fun topArtistIds(limit: Int = 20): List<String> =
        request("GET", "me/top/artists", mapOf("limit" to limit.coerceIn(1, 50).toString()))
            ?.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } } }
            .orEmpty()

    suspend fun artistTopTracks(artistId: String, market: String = "KR"): List<SpotifyTrack> =
        request("GET", "artists/$artistId/top-tracks", mapOf("market" to market))?.optJSONArray("tracks").toTracks()

    suspend fun tracks(ids: List<String>): List<SpotifyTrack> = ids.distinct().chunked(50).flatMap { chunk ->
        request("GET", "tracks", mapOf("ids" to chunk.joinToString(",")))?.optJSONArray("tracks").toTracks()
    }

    // ---- playback --------------------------------------------------------------------------

    suspend fun devices(): List<Pair<String, String>> =
        request("GET", "me/player/devices")?.optJSONArray("devices")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val d = a.optJSONObject(i) ?: return@mapNotNull null
                val id = d.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to d.optString("name")
            }
        }.orEmpty()

    suspend fun playback(): SpotifyPlayback? {
        val root = request("GET", "me/player") ?: return null
        val item = root.optJSONObject("item")
        return SpotifyPlayback(
            trackId = item?.optString("id")?.takeIf { it.isNotBlank() },
            playing = root.optBoolean("is_playing"),
            positionMs = root.optLong("progress_ms"),
            durationMs = item?.optLong("duration_ms") ?: 0,
            deviceId = root.optJSONObject("device")?.optString("id")?.takeIf { it.isNotBlank() },
            deviceName = root.optJSONObject("device")?.optString("name"),
            observedAt = System.currentTimeMillis()
        )
    }

    /** Starts one track. Without an active device, wakes the most plausible one first. */
    suspend fun play(trackId: String, deviceId: String? = null) {
        val target = deviceId ?: playback()?.deviceId ?: devices().firstOrNull()?.first
        val params = target?.let { mapOf("device_id" to it) } ?: emptyMap()
        request("PUT", "me/player/play", params, JSONObject().put("uris", JSONArray().put("spotify:track:$trackId")))
    }

    suspend fun queue(trackId: String, deviceId: String? = null) {
        val params = buildMap { put("uri", "spotify:track:$trackId"); deviceId?.let { put("device_id", it) } }
        request("POST", "me/player/queue", params)
    }

    suspend fun next() { request("POST", "me/player/next") }
    suspend fun pause() { request("PUT", "me/player/pause") }
    suspend fun resume() { request("PUT", "me/player/play") }

    // ---- parsing ---------------------------------------------------------------------------

    private fun JSONArray?.toTracks(): List<SpotifyTrack> {
        val a = this ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::track) }
    }

    private fun track(j: JSONObject): SpotifyTrack? {
        val id = j.optString("id").takeIf { it.isNotBlank() } ?: return null
        val artists = j.optJSONArray("artists")
        val names = mutableListOf<String>(); val ids = mutableListOf<String>()
        for (i in 0 until (artists?.length() ?: 0)) {
            artists?.optJSONObject(i)?.let { names += it.optString("name"); ids += it.optString("id") }
        }
        val album = j.optJSONObject("album")
        return SpotifyTrack(
            id = id, name = j.optString("name").take(200),
            artists = names.filter { it.isNotBlank() }, artistIds = ids.filter { it.isNotBlank() },
            album = album?.optString("name")?.takeIf { it.isNotBlank() },
            releaseDate = album?.optString("release_date")?.takeIf { it.isNotBlank() },
            durationMs = j.optLong("duration_ms"),
            popularity = if (j.has("popularity")) j.optInt("popularity") else null,
            isrc = j.optJSONObject("external_ids")?.optString("isrc")?.takeIf { it.isNotBlank() },
            explicit = j.optBoolean("explicit")
        )
    }
}
