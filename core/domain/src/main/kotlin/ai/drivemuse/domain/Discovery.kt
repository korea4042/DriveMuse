package ai.drivemuse.domain

/*
 * Technical design v2.3 §16, §17, §19, §29.
 * Collection is not learning: nothing here increases a listening score. The novelty state is
 * derived from evidence the app actually observed; it never claims a track is "first ever".
 */

enum class NoveltyState { CONFIRMED_LISTENED, KNOWN_PREFERENCE, EXPOSED_ONLY, NO_OBSERVED_HISTORY, UNKNOWN }

data class TrackExperience(
    val trackId: String,
    val confirmedListenCount: Int = 0,
    val lastConfirmedListenAt: Long? = null,
    val explicitPreference: Int? = null,      // +1 / -1 from the user
    val surveySeed: Boolean = false,
    val exposureCount: Int = 0,               // shown or opened only (played table)
    val lastExposureAt: Long? = null,
    val matchAmbiguous: Boolean = false,
    val historyCoverageSince: Long? = null    // null: history was reset / unknown coverage
)
data class Novelty(val state: NoveltyState, val evidenceIds: List<String>, val historyCoverageSince: Long?, val knownArtist: Boolean)

object NoveltyResolver {
    /** Priority order from §17. knownArtist is separate and never turns a track familiar. */
    fun resolve(e: TrackExperience?, knownArtist: Boolean, historyReset: Boolean): Novelty {
        val since = e?.historyCoverageSince
        if (e == null) return Novelty(if (historyReset) NoveltyState.UNKNOWN else NoveltyState.NO_OBSERVED_HISTORY, emptyList(), since, knownArtist)
        return when {
            e.matchAmbiguous -> Novelty(NoveltyState.UNKNOWN, listOf("match:ambiguous"), since, knownArtist)
            e.confirmedListenCount > 0 -> Novelty(NoveltyState.CONFIRMED_LISTENED, listOf("listen:${e.confirmedListenCount}"), since, knownArtist)
            e.explicitPreference != null || e.surveySeed -> Novelty(NoveltyState.KNOWN_PREFERENCE, listOfNotNull(e.explicitPreference?.let { "explicit:$it" }, if (e.surveySeed) "survey:seed" else null), since, knownArtist)
            e.exposureCount > 0 -> Novelty(NoveltyState.EXPOSED_ONLY, listOf("exposure:${e.exposureCount}"), since, knownArtist)
            historyReset || since == null -> Novelty(NoveltyState.UNKNOWN, emptyList(), since, knownArtist)
            else -> Novelty(NoveltyState.NO_OBSERVED_HISTORY, emptyList(), since, knownArtist)
        }
    }
    /** For selector input and the agent screen. UNKNOWN is never described as "new to you". */
    fun label(state: NoveltyState) = when (state) {
        NoveltyState.CONFIRMED_LISTENED -> "들어본 곡"
        NoveltyState.KNOWN_PREFERENCE -> "좋아하는 곡"
        NoveltyState.EXPOSED_ONLY -> "추천했던 곡"
        NoveltyState.NO_OBSERVED_HISTORY -> "앱에 청취 기록 없음"
        NoveltyState.UNKNOWN -> "기록 확인 불가"
    }
}

/** §17/§29 seed kinds in exploration order. */
enum class SeedKind { PREFERRED_ARTIST, RELATED_ARTIST, GENRE_TAG, LISTENING_RELATION, RECENT_RELEASE, COVERAGE_GAP, EXPERIMENT }
enum class Strategy { OTHER_TRACKS, MATCHING_TRAITS, ADJACENT_TRAITS }
enum class BudgetBand { ADJACENT, EXPANSION, EXPERIMENT }

data class DiscoverySeed(val seedId: String, val kind: SeedKind, val value: String, val profileVersion: Long, val cursor: String? = null, val lastAttemptAt: Long = 0, val nextEligibleAt: Long = 0, val evidenceIds: List<String> = emptyList())
data class CollectionPlan(val seedId: String, val strategy: Strategy, val band: BudgetBand, val priority: Int, val reasonCode: String)

/** §16/§29 collection budget: 70% adjacent, 20% expansion, 10% experiment. Never confused with the 50/40/10 playback mix. */
data class CollectionBudget(val total: Int, val adjacent: Int, val expansion: Int, val experiment: Int) {
    companion object {
        fun of(total: Int): CollectionBudget {
            val t = total.coerceAtLeast(0); val exp = (t * .10).toInt(); val expansion = (t * .20).toInt()
            return CollectionBudget(t, t - exp - expansion, expansion, exp)
        }
    }
}

object DiscoveryPlanner {
    private val bandOf = mapOf(
        SeedKind.PREFERRED_ARTIST to BudgetBand.ADJACENT, SeedKind.RELATED_ARTIST to BudgetBand.ADJACENT, SeedKind.GENRE_TAG to BudgetBand.ADJACENT,
        SeedKind.LISTENING_RELATION to BudgetBand.EXPANSION, SeedKind.RECENT_RELEASE to BudgetBand.EXPANSION, SeedKind.COVERAGE_GAP to BudgetBand.EXPANSION,
        SeedKind.EXPERIMENT to BudgetBand.EXPERIMENT
    )
    private val strategyOf = mapOf(
        SeedKind.PREFERRED_ARTIST to Strategy.OTHER_TRACKS, SeedKind.RELATED_ARTIST to Strategy.ADJACENT_TRAITS, SeedKind.GENRE_TAG to Strategy.MATCHING_TRAITS,
        SeedKind.LISTENING_RELATION to Strategy.ADJACENT_TRAITS, SeedKind.RECENT_RELEASE to Strategy.MATCHING_TRAITS, SeedKind.COVERAGE_GAP to Strategy.ADJACENT_TRAITS,
        SeedKind.EXPERIMENT to Strategy.ADJACENT_TRAITS
    )
    fun bandOf(kind: SeedKind) = bandOf.getValue(kind)

    /**
     * Deterministic plan: eligible seeds in §29 priority order, at most [maxPlans], each stamped
     * with its budget band. Recent releases never jump the queue (they are EXPANSION, ranked
     * after adjacent seeds). Explicit exclusions are filtered by value.
     */
    fun plan(seeds: List<DiscoverySeed>, profileVersion: Long, now: Long, hardExclusions: Set<String>, maxPlans: Int = 5): List<CollectionPlan> = seeds
        .filter { it.profileVersion == profileVersion && it.nextEligibleAt <= now && it.value !in hardExclusions && it.value.isNotBlank() }
        .sortedWith(compareBy<DiscoverySeed> { it.kind.ordinal }.thenBy { it.lastAttemptAt })
        .take(maxPlans)
        .map { CollectionPlan(it.seedId, strategyOf.getValue(it.kind), bandOf.getValue(it.kind), (it.kind.ordinal / 3 + 1).coerceIn(1, 3), "SEED_${it.kind.name}") }

    /** Validates an AI discovery-planner response (§20). Unknown seeds, disallowed strategies or too many plans → null (use local plan). */
    fun accept(proposed: List<CollectionPlan>, allowed: List<DiscoverySeed>, allowedStrategies: Set<Strategy>, maxPlans: Int = 5): List<CollectionPlan>? {
        if (proposed.size > maxPlans || proposed.map { it.seedId }.distinct().size != proposed.size) return null
        val ids = allowed.associateBy { it.seedId }
        if (proposed.any { it.seedId !in ids || it.strategy !in allowedStrategies || it.priority !in 1..3 }) return null
        return proposed.map { it.copy(band = bandOf.getValue(ids.getValue(it.seedId).kind)) }
    }

    /** Builds seeds from a validated profile and observed evidence; free text never becomes a seed. */
    fun seeds(profile: SurveyProfile, likedArtists: Set<String>, profileVersion: Long, coverageGaps: List<String> = emptyList()): List<DiscoverySeed> {
        val out = mutableListOf<DiscoverySeed>()
        likedArtists.filter { it.isNotBlank() }.sorted().forEach { out += DiscoverySeed("artist:$it", SeedKind.PREFERRED_ARTIST, it, profileVersion, evidenceIds = listOf("PROVIDER_LIKE")) }
        profile.preferences.filter { it.scope == Scope.LONG_TERM && it.axis == "genre" }.forEach { out += DiscoverySeed("genre:${it.value}", SeedKind.GENRE_TAG, it.value, profileVersion, evidenceIds = listOf(it.questionId)) }
        coverageGaps.forEach { out += DiscoverySeed("gap:$it", SeedKind.COVERAGE_GAP, it, profileVersion) }
        return out.distinctBy { it.seedId }
    }
}

/** §17 pool sufficiency: counts only validated, in-constraint, non-fatigued, unexpired tracks. */
data class PoolHealth(val eligible: Int, val target: Int = 300, val refillBelow: Int = 120, val minimumToStart: Int = 12, val maxInsertPerRun: Int = 50) {
    val needsRefill get() = eligible < refillBelow
    val canStart get() = eligible >= 1        // §29: 12 is a target, not a hard floor
    val belowStartTarget get() = eligible < minimumToStart
    fun insertAllowance(alreadyInserted: Int) = (maxInsertPerRun - alreadyInserted).coerceAtLeast(0)
}

/** §17 priority = .45 fit + .35 novelty + .20 diversity; ranks within a band only. */
object CandidatePriority {
    fun score(tasteFit: Double?, novelty: NoveltyState, diversityGain: Double?): Double {
        val fit = tasteFit?.coerceIn(0.0, 1.0) ?: .3
        val n = when (novelty) { NoveltyState.NO_OBSERVED_HISTORY -> 1.0; NoveltyState.UNKNOWN -> .5; NoveltyState.EXPOSED_ONLY -> .4; NoveltyState.KNOWN_PREFERENCE -> .1; NoveltyState.CONFIRMED_LISTENED -> 0.0 }
        val d = diversityGain?.coerceIn(0.0, 1.0) ?: .3
        return .45 * fit + .35 * n + .20 * d
    }
}

/** §29 diversity audit; UNKNOWN is its own bucket, popularity stays per provider. */
data class DistributionReport(val artistShareTop5: Double, val buckets: Map<String, Map<String, Int>>, val dominantArtists: List<String>) {
    val artistConcentrated get() = artistShareTop5 > .60
}
object DiversityAudit {
    fun report(tracks: List<TrackRecord>, decade: (TrackRecord) -> String?, language: (TrackRecord) -> String?, source: (TrackRecord) -> String?): DistributionReport {
        val artists = tracks.groupingBy { it.primaryArtist.ifBlank { "UNKNOWN" } }.eachCount().entries.sortedByDescending { it.value }
        val top5 = artists.take(5); val share = if (tracks.isEmpty()) 0.0 else top5.sumOf { it.value }.toDouble() / tracks.size
        fun bucket(f: (TrackRecord) -> String?) = tracks.groupingBy { f(it) ?: "UNKNOWN" }.eachCount()
        return DistributionReport(share, mapOf("decade" to bucket(decade), "language" to bucket(language), "source" to bucket(source), "version" to bucket { it.versionType.name }), top5.map { it.key })
    }
}

/** §19 collection lease + run limits (pure part; DB does the compare-and-set). */
data class RunLimits(val maxInserts: Int = 50, val maxRequests: Int = 10, val maxElapsedMs: Long = 120_000)
data class RunProgress(val inserted: Int = 0, val updated: Int = 0, val rejected: Int = 0, val requests: Int = 0, val startedAt: Long = 0) {
    fun exhausted(limits: RunLimits, now: Long) = inserted >= limits.maxInserts || requests >= limits.maxRequests || now - startedAt >= limits.maxElapsedMs
}
data class Lease(val scope: String, val owner: String, val until: Long, val generation: Long) {
    fun heldBy(candidate: String, now: Long) = owner == candidate && until > now
}
object LeasePolicy {
    fun acquire(existing: Lease?, scope: String, owner: String, now: Long, ttlMs: Long, generation: Long): Lease? =
        if (existing == null || existing.until <= now || existing.owner == owner) Lease(scope, owner, now + ttlMs, generation) else null
}
