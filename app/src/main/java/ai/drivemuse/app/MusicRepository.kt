package ai.drivemuse.app

import ai.drivemuse.domain.*
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
    private val api: YouTubeApi,
    private val dao: DriveDao,
    private val prefs: Preferences
) {
    private val seedMutex=Mutex()
    suspend fun addSurveyCandidates(answers: List<SurveyAnswer>) = seedMutex.withLock {
        val phrases=answers.filter { it.status==AnswerStatus.ANSWERED }.sortedByDescending { it.question.id=="Q7" }.flatMap { a ->
            if(a.freeText.isNotBlank()) listOf(a.freeText.take(120)) else if(a.question.intent==Intent.PREFERENCE) a.question.options.filter { it.id in a.selected }.map { it.label+" music" } else emptyList()
        }.distinct().take(3)
        for(phrase in phrases) {
            val now=System.currentTimeMillis();val today=now/86400000;val s=prefs.flow.first();val used=if(s.quotaDay==today) s.searchCalls else 0
            if(!Quota.canSearch(used)) break
            if(!prefs.reserveSearch(today)) break
            val existing=dao.candidates(now-poolTtl).map { it.videoId }.toSet()
            val results=api.searchMusic(phrase).filter { playable(it) && it.id !in existing }.map { row(it,false,.6,"survey_search",now) }
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

        val stale = now - settings.tasteSyncedAt > syncInterval
        if (stale || dao.candidateCount(now - poolTtl) < minimumPool) {
            runCatching { refresh(settings) }.onFailure { error ->
                // A failed refresh must never empty the queue. §6.8
                if (dao.candidateCount(now - poolTtl) == 0) throw error
            }
        }
        return toTracks(dao.candidates(now - poolTtl), context, now)
    }

    /**
     * Rebuilds the pool. Liked videos and subscriptions need the bearer token; the chart does
     * not, which is what keeps the app useful before the account is linked.
     */
    suspend fun refresh(settings: Settings) {
        val now = System.currentTimeMillis()
        val rows = mutableListOf<CandidateEntity>()
        var likedArtists = emptySet<String>()

        if (settings.accountLinked) {
            runCatching {
                val liked = api.videos(api.likedVideoIds(limit = 300))
                likedArtists = liked.map { it.channel.removeSuffix(" - Topic") }.toSet()
                rows += liked.filter { playable(it) }.map { row(it, familiar = true, affinity = .9, source = "liked", now = now) }
            }.onFailure { if (it is AuthExpiredException || it is UserAuthRequiredException) throw it }

            runCatching {
                val subscribed = api.subscribedChannels().map { it.removeSuffix(" - Topic") }.toSet()
                likedArtists = likedArtists + subscribed
            }
        }

        runCatching {
            rows += api.popularMusic(settings.regionCode).filter { playable(it) }.map {
                val known = it.channel.removeSuffix(" - Topic") in likedArtists
                row(it, familiar = known, affinity = if (known) .75 else .35, source = "chart", now = now)
            }
        }

        // §6.5 — search is 100 units, so it only runs when the pool is genuinely thin and the
        // daily ceiling still allows it.
        val today = now / 86_400_000
        val callsToday = if (settings.quotaDay == today) settings.searchCalls else 0
        if (rows.size < 60 && likedArtists.isNotEmpty() && Quota.canSearch(callsToday)) {
            val seed = likedArtists.random()
            runCatching {
                check(prefs.reserveSearch(today)) { "Search quota reached" }
                rows += api.searchMusic(seed).filter { playable(it) }.map { row(it, familiar = false, affinity = .55, source = "search", now = now) }
            }
        }

        if (rows.isNotEmpty()) {
            dao.putCandidates(rows.distinctBy { it.videoId })
            prefs.long("tasteSyncedAt", now)
        }
    }

    private fun playable(v: RawVideo) = Policy.playableTrack(v.categoryId, v.live, v.durationSec) && Policy.validTrackId(v.id)

    private fun row(v: RawVideo, familiar: Boolean, affinity: Double, source: String, now: Long) = CandidateEntity(
        videoId = v.id,
        // "Artist - Topic" channels are auto-generated art tracks; stripping the suffix gives a usable artist name.
        title = v.title, artist = v.channel.removeSuffix(" - Topic"),
        durationSec = v.durationSec, topics = v.topics.joinToString("|"),
        familiar = familiar, affinity = affinity,
        freshness = if (v.publishedYear >= java.time.Year.now().value - 2) .8 else .4,
        energy = EnergyHints.estimate(v.topics),
        source = source, fetchedAt = now, audioLanguage=v.audioLanguage
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
                familiar = row.familiar, energy = row.energy, affinity = row.affinity,
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
