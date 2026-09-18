package ai.drivemuse.app

import ai.drivemuse.domain.*
import ai.drivemuse.app.spotify.SpotifyIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Technical design v1.2 §6.9.
 *
 * Without listening history or a provider recommendation feed, the taste model has to be
 * assembled here from what OAuth does expose: liked videos and subscriptions. Everything
 * the old MCP gateway inferred server-side now lives on the device.
 */
class MusicRepository(
    private val spotify: ai.drivemuse.app.spotify.SpotifyApi,
    private val dao: DriveDao,
    private val prefs: Preferences
) {
    private val seedMutex=Mutex()
    /** Survey phrases kept from the last seeding pass, so a thin pool can reuse them. */
    @Volatile private var lastSurveySeeds: List<String> = emptyList()
    suspend fun addSurveyCandidates(answers: List<SurveyAnswer>) = seedMutex.withLock {
        val phrases=answers.filter { it.status==AnswerStatus.ANSWERED }.sortedByDescending { it.question.id=="Q7" }.flatMap { a ->
            if(a.freeText.isNotBlank()) listOf(a.freeText.take(120)) else if(a.question.intent==Intent.PREFERENCE) a.question.options.filter { it.id in a.selected }.map { it.label+" music" } else emptyList()
        }.distinct().take(3)
        lastSurveySeeds = phrases
        for(phrase in phrases) {
            val now=System.currentTimeMillis();val today=now/86400000;val s=prefs.flow.first();val used=if(s.quotaDay==today) s.searchCalls else 0
            if(!Quota.canSearch(used)) break
            if(!prefs.reserveSearch(today)) break
            val existing=dao.candidates(now-poolTtl).map { it.videoId }.toSet()
            val results=runCatching { spotify.search(phrase, 20) }.getOrDefault(emptyList())
                .filter { it.id !in existing }.map { row(it, familiar=false, affinity=.6, source="survey_search", now=now) }
            dao.putCandidates(results)
        }
    }
    private val poolTtl = 30L * 24 * 60 * 60 * 1000   // §6.6 — API response cache never exceeds 30 days
    private val syncInterval = 20L * 60 * 60 * 1000   // one taste sync a day is plenty
    private val fatigueWindow = 7L * 24 * 60 * 60 * 1000

    /**
     * Returns ranked candidates without touching the network when the cached pool is healthy.
     * §6.5 — a car session should cost zero API units.
     */
    suspend fun candidates(context: DriveContext, settings: Settings, minimumPool: Int = 40): List<Track> {
        val now = System.currentTimeMillis()
        dao.pruneCandidates(now - poolTtl)
        dao.prunePlayed(now - fatigueWindow)
        // One-time cleanup: rows keyed by a YouTube video id can never be played through Spotify.
        val legacy = dao.candidates(0).filterNot { SpotifyIds.isTrackId(it.videoId) }
        if (legacy.isNotEmpty()) dao.deleteCandidates(legacy.map { it.videoId })

        // §27: rows stored before the form filter existed are still in the pool, so the filter runs
        // at read time too. Pool health counts only rows that can actually be recommended.
        val stale = now - settings.tasteSyncedAt > syncInterval
        if (stale || usable(dao.candidates(now - poolTtl)).size < minimumPool) {
            runCatching { refresh(settings) }.onFailure { error ->
                // A failed refresh must never empty the queue. §6.8
                if (usable(dao.candidates(now - poolTtl)).isEmpty()) throw error
            }
        }
        return toTracks(usable(dao.candidates(now - poolTtl)), context, now)
    }

    /**
     * Rebuilds the pool. Liked videos and subscriptions need the bearer token; the chart does
     * not, which is what keeps the app useful before the account is linked.
     */
    suspend fun refresh(settings: Settings) {
        val now = System.currentTimeMillis()
        val rows = mutableListOf<CandidateEntity>()

        // Saved tracks and listening history are the account's own taste evidence (§17 KNOWN_PREFERENCE).
        val saved = runCatching { spotify.savedTracks(50) }.getOrDefault(emptyList())
        rows += saved.map { row(it, familiar = true, affinity = .9, source = "saved", now = now) }
        val top = runCatching { spotify.topTracks(limit = 50) }.getOrDefault(emptyList())
        rows += top.map { row(it, familiar = true, affinity = .85, source = "top", now = now) }

        val knownArtistIds = (saved + top).flatMap { it.artistIds }.toSet()
        val knownTrackIds = rows.map { it.videoId }.toSet()

        // Unheard tracks by artists the listener already has are the cheapest real discovery (§17 D01).
        val seedArtists = runCatching { spotify.topArtistIds(limit = 10) }.getOrDefault(emptyList())
            .ifEmpty { knownArtistIds.take(10) }
        for (artistId in seedArtists.take(6)) {
            val tracks = runCatching { spotify.artistTopTracks(artistId, settings.regionCode) }.getOrDefault(emptyList())
            rows += tracks.filter { it.id !in knownTrackIds }
                .map { row(it, familiar = false, affinity = .6, source = "artist_top", now = now) }
        }

        // A brand-new Spotify account has no library at all, so the pool has to start somewhere.
        if (rows.size < 20) {
            val seeds = lastSurveySeeds.ifEmpty { listOf("k-pop", "pop", "r&b", "hip hop", "indie rock") }
            for (phrase in seeds.take(5)) {
                val found = runCatching { spotify.search(phrase, 20) }.getOrDefault(emptyList())
                rows += found.map { row(it, familiar = false, affinity = .5, source = "search", now = now) }
            }
            rows += runCatching { spotify.newReleaseTracks(settings.regionCode) }.getOrDefault(emptyList())
                .map { row(it, familiar = false, affinity = .45, source = "new_release", now = now) }
        }

        if (rows.isNotEmpty()) {
            dao.putCandidates(rows.distinctBy { it.videoId })
            prefs.long("tasteSyncedAt", now)
        }
    }

    /**
     * Spotify serves recordings, not uploads, so there is no stage cut or music video to filter out.
     * The filter stays for rows the YouTube-era pool left behind, which are dropped on sight.
     */
    private fun usable(rows: List<CandidateEntity>) = rows.filter { SpotifyIds.isTrackId(it.videoId) }

    private fun row(t: ai.drivemuse.app.spotify.SpotifyTrack, familiar: Boolean, affinity: Double, source: String, now: Long) = CandidateEntity(
        videoId = t.id, title = t.name, artist = t.artists.joinToString(", "),
        durationSec = (t.durationMs / 1000).toInt(), topics = "",
        familiar = familiar, affinity = affinity,
        // A verified release date, unlike a YouTube upload time (§16 releaseRecency).
        freshness = t.releaseDate?.take(4)?.toIntOrNull()?.let { if (it >= java.time.Year.now().value - 2) .8 else .4 } ?: .5,
        // The API exposes no audio features here, so energy stays unknown rather than guessed (§31).
        energy = null,
        source = source, fetchedAt = now, audioLanguage = null
    )

    private suspend fun toTracks(rows: List<CandidateEntity>, context: DriveContext, now: Long): List<Track> {
        val recentPlays = dao.playedSince(now - fatigueWindow).groupingBy { it }.eachCount()
        val targetEnergy = when (context) {
            DriveContext.COMMUTE_TO_WORK -> .62
            DriveContext.COMMUTE_HOME -> .45
            DriveContext.TRAVEL -> .7
            DriveContext.NIGHT_DRIVE -> .3
            else -> .5
        }
        return rows.map { row ->
            Track(
                id = row.videoId, title = row.title, artist = row.artist,
                familiar = row.familiar, energy = row.energy,
                // A music video ranks below the audio upload of the same song (§30).
                affinity = (row.affinity - VideoForm.rankPenalty(row.title, row.artist)).coerceIn(0.0, 1.0),
                // Unknown energy sits at neutral rather than being guessed toward the target.
                contextFit = row.energy?.let { 1.0 - kotlin.math.abs(it - targetEnergy) } ?: .5,
                freshness = row.freshness,
                fatigue = ((recentPlays[row.videoId] ?: 0) * .18).coerceAtMost(.7),
                durationMs=row.durationSec*1000L,
                features=row.topics.split("|").mapNotNull { topic ->
                    val genre=when(topic.lowercase().replace(" ","_")) { "pop_music"->"POP";"rhythm_and_blues"->"RNB";"hip_hop_music"->"HIP_HOP";"rock_music"->"ROCK";"jazz"->"JAZZ";"classical_music"->"CLASSICAL";"electronic_music"->"ELECTRONIC";else->null }
                    genre?.let { VerifiedFeature("genre",it,"YOUTUBE_TOPIC",.85) }
                } + listOfNotNull(row.audioLanguage?.let { VerifiedFeature("language",it.substringBefore('-'),"YOUTUBE_AUDIO_LANGUAGE",1.0) })
            )
        }
    }

    /** Called on every handoff so repeat fatigue has something to measure. */
    suspend fun recordPlay(videoId: String) {
        if (Policy.validTrackId(videoId)) dao.putPlayed(PlayedEntity(videoId = videoId, playedAt = System.currentTimeMillis()))
    }

    suspend fun clearCache() { dao.clearCandidates(); dao.clearPlayed() }
}
