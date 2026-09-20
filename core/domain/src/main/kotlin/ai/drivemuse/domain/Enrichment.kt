package ai.drivemuse.domain

/*
 * Independent metadata layer. Design v2.3 §27 (basis), §28 (providers), §31 (interpreter).
 *
 * Two rules shape everything here.
 *
 * First, the source is a closed enum with no SPOTIFY member. Spotify values live in the
 * `candidates` row and are never turned into a CandidateAssertion, so any function that takes
 * `List<CandidateAssertion>` cannot be handed one — the policy boundary is a type, not a flag
 * checked at runtime.
 *
 * Second, energy stays the Double the rest of the app already speaks. `MusicRule.energyCeiling`
 * and `Track.energy` are both 0..1; introducing a LOW/MID/HIGH enum beside them would leave two
 * scales to keep in step. Tag hints map into the same 0..1 instead.
 */

/** Where a claim came from. Deliberately has no SPOTIFY member (§2 of the enrichment design). */
enum class AssertionSource { MUSICBRAINZ, LASTFM, GEMINI, USER }

/**
 * Mood as the tag mapping and the interpreter speak it.
 *
 * Survey Q6 is not on this axis: its CALM/BRIGHT/ENERGETIC answers are already read as a target
 * energy, and ENERGETIC has no mood meaning. Q6 keeps driving energy; mood comes from tags.
 */
enum class Mood { CALM, BRIGHT, DARK, TENSE, NEUTRAL }

/** One stored claim about one candidate. `trackId` is the Spotify id, the `candidates` key. */
data class CandidateAssertion(
    val assertionId: String, val trackId: String, val field: String, val value: String,
    val basis: Basis, val source: AssertionSource,
    val confidence: Double? = null, val evidenceIds: List<String> = emptyList(),
    val metadataVersion: Int = 0, val fetchedAt: Long = 0, val expiresAt: Long? = null
) {
    fun live(now: Long) = expiresAt == null || expiresAt > now
}

/** A resolved value with the receipt that produced it, so a screen can say why. */
data class Feature<T>(val value: T, val basis: Basis, val confidence: Double, val evidenceIds: List<String> = emptyList())

data class TrackFeatures(
    val energy: Feature<Double>? = null,
    val mood: Feature<Mood>? = null,
    val language: Feature<String>? = null,
    val releaseYear: Int? = null
) {
    /** Any independently sourced value at all. The coverage metric of §11 counts these. */
    val enriched get() = energy != null || mood != null
}

/** Statuses of one candidate's three enrichment legs. */
enum class EnrichStatus { PENDING, DONE, NOT_FOUND, AMBIGUOUS, RETRY_WAIT, LLM_REJECTED, SKIPPED }

data class EnrichmentState(
    val trackId: String,
    val mbStatus: EnrichStatus = EnrichStatus.PENDING,
    val lfStatus: EnrichStatus = EnrichStatus.PENDING,
    val llmStatus: EnrichStatus = EnrichStatus.PENDING,
    val metadataVersion: Int = 0,
    val lastAttemptAt: Long = 0,
    val nextEligibleAt: Long = 0,
    val attempts: Int = 0
)

/**
 * The deterministic part: which candidates to enrich next, and what a bag of community tags is
 * allowed to imply. This is app code, so its output carries basis COMMUNITY_TAG, never AI_INFERRED.
 */
object EnrichmentPolicy {

    /** Energy a tag bag implies, on the same 0..1 scale as `MusicRule.energyCeiling`. */
    const val ENERGY_HIGH = .80
    const val ENERGY_LOW = .25

    /** Below this many combined tag votes the hint is kept but marked too weak to act on. */
    const val WEAK_TAG_VOTES = 30
    const val CONFIDENT = .65
    const val WEAK = .35

    private val energyHigh = setOf("dance", "upbeat", "energetic", "party", "edm", "hard rock", "dance pop")
    private val energyLow = setOf("chill", "ambient", "acoustic", "ballad", "mellow", "sleep", "chillout")
    private val calmYes = setOf("relaxing", "calm", "chillout", "downtempo")
    private val calmNo = setOf("aggressive", "intense")
    private val brightYes = setOf("happy", "feel good", "summer", "upbeat")
    private val brightNo = setOf("sad", "melancholy")
    private val darkYes = setOf("melancholy", "sad", "dark", "moody")
    private val darkNo = setOf("happy")
    private val languages = mapOf("k-pop" to "ko", "kpop" to "ko", "j-pop" to "ja", "jpop" to "ja")

    data class Tag(val name: String, val count: Int)

    /**
     * What the tags support. A hint is produced only when the evidence points one way: a bag
     * holding both sides of an axis yields nothing and says so in `conflicts`, because guessing
     * between them would be the app inventing a judgement the listeners did not make.
     */
    data class Hints(
        val energy: Double? = null, val mood: Mood? = null, val language: String? = null,
        val confidence: Double = WEAK, val conflicts: List<String> = emptyList()
    )

    fun tagsToHints(tags: List<Tag>): Hints {
        val norm = tags.map { Tag(it.name.trim().lowercase(), it.count) }.filter { it.name.isNotBlank() }
        if (norm.isEmpty()) return Hints()
        val votes = norm.sumOf { it.count }
        val confidence = if (votes >= WEAK_TAG_VOTES) CONFIDENT else WEAK
        val conflicts = mutableListOf<String>()

        val high = norm.any { it.name in energyHigh }
        val low = norm.any { it.name in energyLow }
        val energy = when {
            high && low -> { conflicts += "energy"; null }
            high -> ENERGY_HIGH
            low -> ENERGY_LOW
            else -> null
        }

        val calm = norm.any { it.name in calmYes } && norm.none { it.name in calmNo }
        val bright = norm.any { it.name in brightYes } && norm.none { it.name in brightNo }
        val dark = norm.any { it.name in darkYes } && norm.none { it.name in darkNo }
        val moods = listOfNotNull(Mood.CALM.takeIf { calm }, Mood.BRIGHT.takeIf { bright }, Mood.DARK.takeIf { dark })
        // Both sides of an axis appeared, or two different moods did: no hint either way.
        val moodContested = (norm.any { it.name in calmYes } && norm.any { it.name in calmNo }) ||
            (norm.any { it.name in brightYes } && norm.any { it.name in brightNo }) ||
            (norm.any { it.name in darkYes } && norm.any { it.name in darkNo })
        if (moodContested || moods.size > 1) conflicts += "mood"
        val mood = if (moodContested || moods.size != 1) null else moods.single()

        val languageHits = norm.mapNotNull { languages[it.name] }.distinct()
        if (languageHits.size > 1) conflicts += "language"
        val language = languageHits.singleOrNull()

        return Hints(energy, mood, language, confidence, conflicts)
    }

    /**
     * Enrichment order (§5): what is playing now, then the head of the next batch, then the rest.
     * A candidate waiting out a backoff is skipped entirely rather than reordered.
     */
    fun pending(
        candidateIds: List<String>,
        queued: List<String>,
        states: Map<String, EnrichmentState>,
        now: Long,
        limit: Int = Policy.ENRICH_BATCH,
        head: Int = 40
    ): List<String> {
        val eligible = candidateIds.distinct().filter { id ->
            val s = states[id] ?: return@filter true
            s.nextEligibleAt <= now && !(s.mbStatus == EnrichStatus.DONE && s.lfStatus == EnrichStatus.DONE)
        }
        val queuedSet = queued.toSet()
        val headSet = candidateIds.filterNot { it in queuedSet }.take(head).toSet()
        return eligible.sortedBy { id -> when { id in queuedSet -> 0; id in headSet -> 1; else -> 2 } }.take(limit)
    }
}

/**
 * One value per field out of the stored claims (§7).
 *
 * The order is fixed: what the user said, then a provider fact, then the tag mapping, then the
 * model — and a claim under its basis's confidence floor is not a weaker answer, it is no answer.
 * Unknown stays unknown so the caller can order by it rather than exclude on it.
 */
object TrackFeatureResolver {

    const val TAG_FLOOR = .5
    const val AI_FLOOR = .4

    private fun rank(basis: Basis) = when (basis) {
        Basis.USER_OBSERVED -> 0
        Basis.PROVIDER_FACT -> 1
        Basis.COMMUNITY_TAG -> 2
        Basis.AI_INFERRED -> 3
        Basis.AUDIO_ANALYZED -> 4
    }

    private fun floor(basis: Basis) = when (basis) {
        Basis.COMMUNITY_TAG -> TAG_FLOOR
        Basis.AI_INFERRED -> AI_FLOOR
        else -> 0.0
    }

    /**
     * §27: a tag or an inference is a claim with a stated strength, and one that states none has
     * nothing to put against the floor. Treating a missing confidence as 1.0 let those through and
     * then showed them as certain. A fact has no floor, so its missing confidence still reads full.
     */
    private fun passesFloor(a: CandidateAssertion): Boolean {
        val floor = floor(a.basis)
        if (floor <= 0.0) return true
        return (a.confidence ?: return false) >= floor
    }

    /** Best claim for one field, or null. Ties inside a basis go to the later metadataVersion. */
    private fun best(assertions: List<CandidateAssertion>, field: String, now: Long): CandidateAssertion? =
        assertions.filter { it.field == field && it.live(now) && passesFloor(it) }
            .minWithOrNull(compareBy<CandidateAssertion> { rank(it.basis) }.thenByDescending { it.metadataVersion }.thenByDescending { it.fetchedAt })

    private fun <T> feature(a: CandidateAssertion?, parse: (String) -> T?): Feature<T>? {
        val value = a?.let { parse(it.value) } ?: return null
        return Feature(value, a.basis, a.confidence ?: 1.0, a.evidenceIds)
    }

    fun resolve(assertions: List<CandidateAssertion>, now: Long = Long.MAX_VALUE / 2): TrackFeatures = TrackFeatures(
        energy = feature(best(assertions, "energy", now)) { it.toDoubleOrNull()?.takeIf { v -> v in 0.0..1.0 } },
        mood = feature(best(assertions, "mood", now)) { v -> Mood.entries.firstOrNull { it.name == v } },
        language = feature(best(assertions, "language", now)) { it.trim().lowercase().takeIf { v -> v.length == 2 } },
        releaseYear = best(assertions, "releaseDate", now)?.value?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
    )
}
