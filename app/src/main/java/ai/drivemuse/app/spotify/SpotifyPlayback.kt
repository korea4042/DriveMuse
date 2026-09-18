package ai.drivemuse.app.spotify

import ai.drivemuse.domain.Track

/** What actually happened when the app asked Spotify to play something. */
data class SpotifyPlayResult(val ok: Boolean, val message: String, val trackId: String? = null)

/**
 * Technical design v2.3 §30, Spotify edition. The pool is still keyed by YouTube ids, so this
 * bridges one recommended Track to a Spotify recording by searching title and artist. The match is
 * deliberately conservative: a wrong recording would be attributed to the wrong Track by the
 * learning path, so a weak match is reported rather than played.
 */
class SpotifyPlayback(private val api: SpotifyApi) {

    suspend fun play(track: Track): SpotifyPlayResult {
        val match = resolve(track) ?: return SpotifyPlayResult(false, "Spotify에서 같은 곡을 찾지 못했어요")
        return try {
            api.play(match.id)
            SpotifyPlayResult(true, "${match.artists.firstOrNull() ?: ""} ${match.name} 재생 중".trim(), match.id)
        } catch (e: SpotifyPremiumRequired) { SpotifyPlayResult(false, e.message ?: "")
        } catch (e: SpotifyNoActiveDevice) { SpotifyPlayResult(false, e.message ?: "")
        } catch (e: SpotifyAuthRequired) { SpotifyPlayResult(false, e.message ?: "")
        } catch (e: Exception) { SpotifyPlayResult(false, e.message ?: "재생하지 못했어요") }
    }

    /** Adds the rest of the batch behind the current track so the picks play in order. */
    suspend fun queue(tracks: List<Track>): Int {
        var queued = 0
        for (t in tracks) {
            val match = resolve(t) ?: continue
            runCatching { api.queue(match.id) }.onSuccess { queued++ }
        }
        return queued
    }

    private suspend fun resolve(track: Track): SpotifyTrack? {
        val artist = track.artist.removeSuffix(" - Topic").trim()
        val cleanTitle = clean(track.title)
        val results = runCatching { api.search("track:\"$cleanTitle\" artist:\"$artist\"", 10) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { api.search("$cleanTitle $artist", 10) }.getOrNull().orEmpty()
        // Track.durationMs is nullable in the domain; an unknown length just makes duration neutral.
        val duration = track.durationMs ?: 0L
        return results.maxByOrNull { score(it, cleanTitle, artist, duration) }
            ?.takeIf { score(it, cleanTitle, artist, duration) >= .55 }
    }

    /** Title and artist agreement carry the match; duration only confirms it. */
    private fun score(candidate: SpotifyTrack, title: String, artist: String, durationMs: Long): Double {
        val t = similarity(clean(candidate.name), title)
        val a = if (artist.isBlank()) .5 else candidate.artists.maxOfOrNull { similarity(it.lowercase(), artist.lowercase()) } ?: 0.0
        val d = if (durationMs <= 0 || candidate.durationMs <= 0) .5
        else (1.0 - kotlin.math.abs(candidate.durationMs - durationMs) / 15_000.0).coerceIn(0.0, 1.0)
        return t * .5 + a * .35 + d * .15
    }

    private fun clean(raw: String) = raw.lowercase()
        .replace(Regex("\\(([^)]*(official|audio|video|lyric|mv|feat|ft)[^)]*)\\)"), " ")
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Token overlap: robust enough for "Artist - Song (Official Audio)" against "Song". */
    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val x = a.split(' ').filter { it.isNotBlank() }.toSet()
        val y = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / maxOf(x.size, y.size)
    }
}
