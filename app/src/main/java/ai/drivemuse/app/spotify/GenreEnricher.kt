package ai.drivemuse.app.spotify

import ai.drivemuse.app.ArtistGenreEntity
import ai.drivemuse.app.DriveDao
import ai.drivemuse.domain.GenreMap
import org.json.JSONArray

/**
 * Phase 1 §5. Candidates were being stored with `topics=""`, which meant the survey's genre
 * questions had nothing to match and silently did nothing. Spotify publishes genres per artist, so
 * one batched `/artists` call per 50 unknown ids fills the gap at negligible cost.
 *
 * The result is an approximation of the track and is recorded as one: the axes go in `topics`, the
 * energy guess goes in its own column with `GENRE_APPROX` beside it, and the measured `energy`
 * column is never touched.
 */
class GenreEnricher(private val api: SpotifyApi, private val dao: DriveDao, private val ttlMs: Long = 7 * 86_400_000L) {
    data class Result(val genres: Map<String, List<String>>, val fetched: Int, val error: String?)

    /** Cached first, then one call per 50 remaining ids. A failure leaves the cache intact. */
    suspend fun genresFor(artistIds: Collection<String>, now: Long): Result {
        val wanted = artistIds.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return Result(emptyMap(), 0, null)
        val cached = runCatching { dao.artistGenres(now - ttlMs) }.getOrDefault(emptyList())
            .filter { it.artistId in wanted }
            .associate { it.artistId to decode(it.genresJson) }
        val missing = wanted.filterNot { it in cached }
        if (missing.isEmpty()) return Result(cached, 0, null)
        val found = mutableMapOf<String, List<String>>()
        var error: String? = null
        for (chunk in missing.chunked(50)) {
            val outcome = runCatching { api.artistGenres(chunk) }
            outcome.onFailure { if (error == null) error = it.message ?: it::class.simpleName }
            val batch = outcome.getOrNull() ?: break
            // An artist with no genres is a real answer; cache it so it is not asked for again.
            chunk.forEach { id -> found[id] = batch[id].orEmpty() }
        }
        if (found.isNotEmpty()) runCatching {
            dao.putArtistGenres(found.map { (id, genres) -> ArtistGenreEntity(id, encode(genres), now) })
        }
        return Result(cached + found, found.size, error)
    }

    /** Axes the survey can act on, plus the energy guess. Both empty when nothing is known. */
    fun describe(artistIds: List<String>, genres: Map<String, List<String>>): Pair<String, Double?> {
        val raw = artistIds.flatMap { genres[it].orEmpty() }
        val axes = GenreMap.axes(raw)
        return axes.joinToString("|") to GenreMap.energy(raw)
    }

    private fun decode(json: String): List<String> = runCatching {
        JSONArray(json).let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }
    }.getOrDefault(emptyList())
    private fun encode(genres: List<String>) = JSONArray(genres).toString()
}
