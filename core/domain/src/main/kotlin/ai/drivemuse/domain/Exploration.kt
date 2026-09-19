package ai.drivemuse.domain

/*
 * Technical design v2.3 §29 "수집 비율과 실제 노출 비율".
 * Playback mix (Known / Discovery / Experimental) is separate from the collection budget.
 * Default 50/40/10 applies only when Q4 was not answered; an explicit Q4 target
 * (.15/.35/.55 total exploration) wins and is split Discovery:Experimental = 4:1.
 */

enum class MixClass { KNOWN, DISCOVERY, EXPERIMENTAL, UNCLASSIFIED }

data class MixTarget(val known: Double, val discovery: Double, val experimental: Double) {
    val exploration get() = discovery + experimental
    companion object {
        val DEFAULT = MixTarget(.50, .40, .10)
        fun fromExploration(total: Double): MixTarget { val t = total.coerceIn(.10, .60); return MixTarget(1 - t, t * .8, t * .2) }
        /** Q4 explicit → its mapping. Unanswered → product default. A stronger explicit limit beats the exploration floor. */
        fun resolve(profile: SurveyProfile) = if ("Q4" in profile.unknowns) DEFAULT else fromExploration(profile.discovery)
    }
}

/** Cumulative START_CONFIRMED exposures per class this session. Display/open at L0 is not exposure. */
data class MixProgress(val known: Int = 0, val discovery: Int = 0, val experimental: Int = 0) {
    val total get() = known + discovery + experimental
    fun plus(c: MixClass) = when (c) { MixClass.KNOWN -> copy(known = known + 1); MixClass.DISCOVERY -> copy(discovery = discovery + 1); MixClass.EXPERIMENTAL -> copy(experimental = experimental + 1); MixClass.UNCLASSIFIED -> this }
}

object ExplorationMix {
    /** §29: classification from evidence; unclear is never disguised as Known. */
    fun classify(novelty: NoveltyState, knownArtist: Boolean, tasteFit: Double?): MixClass = when (novelty) {
        NoveltyState.CONFIRMED_LISTENED, NoveltyState.KNOWN_PREFERENCE -> MixClass.KNOWN
        NoveltyState.NO_OBSERVED_HISTORY, NoveltyState.EXPOSED_ONLY -> if (knownArtist || (tasteFit ?: 0.0) >= .5) MixClass.DISCOVERY else MixClass.EXPERIMENTAL
        NoveltyState.UNKNOWN -> MixClass.UNCLASSIFIED
    }

    /**
     * Integer allocation for the next [count] slots, correcting for what the session already
     * exposed rather than forcing 50/40/10 in every batch of three.
     */
    fun allocate(target: MixTarget, progress: MixProgress, count: Int = Policy.BATCH_SIZE): List<MixClass> {
        val result = mutableListOf<MixClass>(); var p = progress
        repeat(count) {
            val n = p.total + 1
            val deficit = listOf(
                MixClass.KNOWN to target.known * n - p.known,
                MixClass.DISCOVERY to target.discovery * n - p.discovery,
                MixClass.EXPERIMENTAL to target.experimental * n - p.experimental
            ).maxBy { it.second }.first
            result += deficit; p = p.plus(deficit)
        }
        return result
    }

    /** Fills a plan with candidates; falls back to any class rather than violating constraints or returning fewer than possible. */
    fun fill(plan: List<MixClass>, pool: List<Pair<Track, MixClass>>, avoidArtistRepeat: Boolean = true): List<Track> {
        val remaining = pool.toMutableList(); val out = mutableListOf<Track>()
        for (want in plan) {
            val eligible = remaining.filter { !avoidArtistRepeat || it.first.artist != out.lastOrNull()?.artist }
            val pick = eligible.firstOrNull { it.second == want } ?: eligible.firstOrNull { it.second != MixClass.UNCLASSIFIED } ?: eligible.firstOrNull() ?: break
            out += pick.first; remaining.remove(pick)
        }
        return out
    }

    /**
     * §29 adjustment: only after ≥3 sessions and ≥20 valid attempts, at most 5 %p per day,
     * within [.10, .60]. No signal → unchanged.
     */
    fun adjust(current: Double, signal: Double?, distinctSessions: Int, validAttempts: Int, alreadyAdjustedTodayPp: Double): Double {
        if (signal == null || distinctSessions < 3 || validAttempts < 20) return current
        val room = (.05 - alreadyAdjustedTodayPp).coerceAtLeast(0.0)
        return (current + signal.coerceIn(-room, room)).coerceIn(.10, .60)
    }
}
