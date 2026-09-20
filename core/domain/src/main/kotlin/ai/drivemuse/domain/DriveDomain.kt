package ai.drivemuse.domain

import java.time.LocalDateTime

enum class DriveContext(val label: String, val mix: String) {
    COMMUTE_TO_WORK("출근길", "Morning Focus"), COMMUTE_HOME("퇴근길", "After Hours"),
    TRAVEL("여행", "Open Road"), NIGHT_DRIVE("야간 드라이브", "Midnight Flow"),
    GENERAL_DRIVE("드라이브", "Your Drive Mix"), UNKNOWN("연결 대기", "Find your rhythm")
}
enum class Zone { HOME, WORK, USUAL, OUTSIDE, UNKNOWN }
data class Signals(val connected: Boolean, val time: LocalDateTime, val origin: Zone = Zone.UNKNOWN, val destination: Zone = Zone.UNKNOWN, val longTrip: Boolean = false)
data class Classification(val context: DriveContext, val confidence: Double, val reasons: List<String>)
object ContextEngine {
    fun classify(s: Signals): Classification {
        if (!s.connected) return Classification(DriveContext.UNKNOWN, 0.0, listOf("차량 미연결"))
        val minutes = s.time.hour * 60 + s.time.minute
        val weekday = s.time.dayOfWeek.value <= 5
        // Travel purpose cannot be inferred from distance.
        if (weekday && minutes in 390..630 && s.origin == Zone.HOME && s.destination == Zone.WORK) return commute(s, DriveContext.COMMUTE_TO_WORK, Zone.HOME, Zone.WORK)
        if (weekday && minutes in 1020..1350 && s.origin == Zone.WORK && s.destination == Zone.HOME) return commute(s, DriveContext.COMMUTE_HOME, Zone.WORK, Zone.HOME)
        // Night is a time attribute, not a purpose or emotional state.
        return Classification(DriveContext.GENERAL_DRIVE, .5, listOf("차량 연결", "기본 믹스"))
    }
    private fun commute(s: Signals, kind: DriveContext, origin: Zone, target: Zone): Classification {
        val score = (.45 + (if (s.origin == origin) .30 else 0.0) + (if (s.destination == target) .25 else 0.0) - (if (s.longTrip) .15 else 0.0)).coerceIn(0.0, 1.0)
        return Classification(kind, score, listOf("평일 시간대", if (score < .8) "위치 없이 시간 기반 추정" else "출발·도착 영역 일치"))
    }
}
data class MusicRule(val id: String, val scope: DriveContext?, val text: String, val discovery: Double? = null, val energyCeiling: Double? = null, val enabled: Boolean = true, val priority: Int = 0, val createdAt: Long = 0)
data class EffectiveRules(val discovery: Double, val energyCeiling: Double) {
    /** Unknown energy cannot establish a conflict with the requested mood. */
    fun allowsEnergy(track: Track) = energyCeiling >= 1.0 || track.energy?.let { it <= energyCeiling } != false
}
object RuleEngine {
    fun resolve(rules: List<MusicRule>, context: DriveContext, defaultRatio: Double): EffectiveRules {
        val applicable = rules.filter { it.enabled && (it.scope == null || it.scope == context) }.sortedWith(compareByDescending<MusicRule> { it.priority }.thenByDescending { it.scope != null }.thenByDescending { it.createdAt })
        return EffectiveRules(applicable.firstNotNullOfOrNull { it.discovery } ?: defaultRatio, applicable.firstNotNullOfOrNull { it.energyCeiling } ?: 1.0)
    }
    fun parse(text: String, id: String, now: Long): MusicRule? {
        val scope = when { "퇴근" in text -> DriveContext.COMMUTE_HOME; "출근" in text -> DriveContext.COMMUTE_TO_WORK; "여행" in text -> DriveContext.TRAVEL; "야간" in text -> DriveContext.NIGHT_DRIVE; else -> null }
        val ratio = Regex("(-?\\d+(?:\\.\\d+)?)\\s*%").find(text)?.groupValues?.get(1)?.toDouble()?.div(100)
        if (ratio != null && ratio !in 0.0..1.0) return null
        val quiet = listOf("잔잔", "조용", "차분", "편안", "시끄러운 곡 제외", "시끄러운 곡은 빼").any { it in text }
        val lively = listOf("신나", "신나게", "리드미컬", "경쾌", "빠른", "에너지").any { it in text }
        // A scope on its own is a usable rule ("퇴근길에는 팝송"): it pins the context even when no
        // number or mood word follows. Only a sentence with nothing recognisable is rejected.
        if (ratio == null && !quiet && !lively && scope == null) return null
        return MusicRule(id, scope, text, ratio, if (quiet) .55 else null, createdAt = now)
    }
}
data class Track(val id: String, val title: String, val artist: String, val familiar: Boolean = false, val energy: Double? = null, val affinity: Double = .5, val contextFit: Double = .5, val freshness: Double = .5, val fatigue: Double = 0.0, val skipped: Boolean = false, val durationMs: Long? = null, val features: List<VerifiedFeature> = emptyList(),
    // §5/§31: where `energy` came from. GENRE_APPROX is a guess from artist genres, not a measurement.
    val energyBasis: String? = null,
    /**
     * Provider popularity, 0–100. Recognisability, not quality and not preference: it says how many
     * people are playing this, nothing about whether this listener will like it.
     */
    val popularity: Int? = null) {
    /** Unknown popularity is neutral, never treated as obscure. */
    val recognisability get() = (popularity?.coerceIn(0, 100)?.div(100.0)) ?: .5
}
object Ranker {
    /**
     * Technical design v1.2 §6.9. The provider_relevance term is gone: the YouTube Data API
     * exposes no personalised ranking signal, so its weight moves onto locally derived affinity.
     */
    fun select(tracks: List<Track>, rules: EffectiveRules, count: Int = Policy.BATCH_SIZE): List<Track> {
        val pool = tracks.distinctBy { it.id }.filter { !it.skipped && rules.allowsEnergy(it) }.sortedByDescending { .40 * it.affinity + .25 * it.contextFit + .20 * (if (it.familiar) 0.0 else 1.0) + .15 * it.freshness + Policy.RECOGNISABILITY_WEIGHT * it.recognisability - it.fatigue }.toMutableList()
        val selected = mutableListOf<Track>()
        while (pool.isNotEmpty() && selected.size < count) {
            val wantNew = selected.count { !it.familiar } < (selected.size + 1) * rules.discovery
            val eligible = pool.filter { it.artist != selected.lastOrNull()?.artist }
            if (eligible.isEmpty()) break
            val next = eligible.firstOrNull { !it.familiar == wantNew } ?: eligible.first()
            selected += next; pool.remove(next)
        }
        return selected
    }
}
/**
 * Quota is billed per Cloud project, not per user. search.list costs 100 units against a
 * 10,000/day default, so it is the only call that needs an explicit ceiling. §6.5
 */
object Quota {
    const val DAILY_UNITS = 10_000
    const val SEARCH_COST = 100
    const val SEARCH_CALLS_PER_DAY = 20
    fun canSearch(callsToday: Int) = callsToday < SEARCH_CALLS_PER_DAY
    fun remainingSearches(callsToday: Int) = (SEARCH_CALLS_PER_DAY - callsToday).coerceAtLeast(0)
}
object Policy {
    /**
     * Tracks planned per batch. Three was the design's planning unit; eight trades reaction speed
     * for fewer interruptions — evidence from the first track now reaches selection eight tracks
     * later instead of three, and the model is called roughly a third as often.
     */
    const val BATCH_SIZE = 8
    /**
     * Most tracks one artist may hold in a batch. A pool built from a few favourite artists' top
     * tracks is heavily skewed, so without a cap a batch of eight can be one artist eight times.
     * Soft: if the pool cannot fill the batch otherwise, a full batch wins over the cap (§30).
     */
    const val MAX_PER_ARTIST = 2
    /** How much recognisability counts. Discovery should mean unheard, not obscure. */
    const val RECOGNISABILITY_WEIGHT = .20
    /**
     * Reduced mode: select from the user's own stated rules only, never from scores derived by
     * analysing Spotify data (affinity, provider popularity, freshness, observation learning).
     * Spotify Developer Policy III.13. Turn off only once a permitted signal source exists.
     */
    const val DIRECT_INPUT_ONLY = true
    /** v1 requests youtube.readonly only. Write scopes are requested per feature, never at onboarding. */
    const val SCOPE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"
    /** Provider track ids: a Spotify id is 22 base62 characters, an older YouTube id is 11. */
    fun validTrackId(id: String) = Regex("[A-Za-z0-9_-]{11,40}").matches(id)
    fun canAutoSelect(enabled: Boolean, connected: Boolean, confidence: Double, suspendedUntil: Long, now: Long) = enabled && connected && confidence >= .8 && now >= suspendedUntil
    /** Metadata eligibility only; category 10 does not guarantee YouTube Music playback. */
    fun playableTrack(categoryId: String?, live: String?, durationSec: Int) =
        categoryId == "10" && (live == null || live == "none") && durationSec in 60..600
}
