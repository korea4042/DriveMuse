package ai.drivemuse.domain

import java.text.Normalizer
import kotlin.math.abs

/*
 * Technical design v2.3 §27, §29, §31.
 *
 * A Track is one recording. A PlayableRef is one provider resource (a YouTube video) that
 * may or may not be that recording. Facts about a Track are MetadataAssertions with an
 * explicit basis; nothing here lets an AI inference overwrite a provider fact, and nothing
 * here lets a title string alone merge two rows.
 */

enum class MetadataStatus { DISCOVERED, BASIC, ENRICHED, VALIDATED }
enum class QueueStatus { PENDING, RESOLVING, ENRICHING, VALIDATING, RETRY_WAIT, QUARANTINED, DONE }
enum class VersionType { ORIGINAL, LIVE, REMIX, COVER, ACOUSTIC, INSTRUMENTAL, UNKNOWN }
enum class RefKind { OFFICIAL_AUDIO, OFFICIAL_MV, LYRICS, SHORTS, UNKNOWN }
enum class MatchStatus { UNMATCHED, PROPOSED, AMBIGUOUS, CONFIRMED, REJECTED }
enum class Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }
enum class Basis { PROVIDER_FACT, COMMUNITY_TAG, AI_INFERRED, USER_OBSERVED, AUDIO_ANALYZED }
enum class IdentifierType { RECORDING_MBID, ISRC, ARTIST_MBID, WORK_MBID }

data class ArtistCredit(val name: String, val mbid: String? = null)
data class TrackIdentifier(val type: IdentifierType, val value: String, val source: String)

data class TrackRecord(
    val trackId: String,
    val title: String,
    val artistCredits: List<ArtistCredit>,
    val durationMs: Long? = null,
    val releaseDate: String? = null,
    val releasePrecision: String? = null,
    val versionType: VersionType = VersionType.UNKNOWN,
    val metadataStatus: MetadataStatus = MetadataStatus.DISCOVERED,
    val identityVersion: Long = 1,
    val metadataVersion: Long = 1,
    val identifiers: List<TrackIdentifier> = emptyList(),
    val workGroupId: String? = null
) { val primaryArtist get() = artistCredits.firstOrNull()?.name ?: "" }

data class PlayableRef(
    val provider: String, val resourceId: String, val trackId: String? = null,
    val kind: RefKind = RefKind.UNKNOWN, val versionType: VersionType = VersionType.UNKNOWN,
    val matchStatus: MatchStatus = MatchStatus.UNMATCHED, val matchEvidence: List<String> = emptyList(),
    val durationMs: Long? = null, val fetchedAt: Long = 0, val expiresAt: Long = 0,
    val availability: Availability = Availability.UNKNOWN
) {
    fun usable(now: Long) = trackId != null && matchStatus == MatchStatus.CONFIRMED && availability != Availability.UNAVAILABLE && expiresAt > now
}

data class MetadataAssertion(
    val assertionId: String, val trackId: String, val field: String, val value: String,
    val basis: Basis, val source: String, val sourceRecordId: String? = null,
    val confidence: Double = 1.0, val fetchedAt: Long = 0, val expiresAt: Long = Long.MAX_VALUE,
    val licenseRef: String? = null, val evidenceIds: List<String> = emptyList()
) {
    fun live(now: Long) = expiresAt > now && confidence.isFinite() && confidence in 0.0..1.0
    /** §27: community tags are not facts; AI inference is never a fact. */
    val factual get() = basis == Basis.PROVIDER_FACT || basis == Basis.USER_OBSERVED || basis == Basis.AUDIO_ANALYZED
}

/** §27 normalization: whitespace, case and Unicode form only. Version markers are kept. */
object TitleNormalizer {
    private val versionMarkers = mapOf(
        VersionType.LIVE to Regex("\\b(live|라이브)\\b", RegexOption.IGNORE_CASE),
        VersionType.REMIX to Regex("\\b(remix|리믹스|mix)\\b", RegexOption.IGNORE_CASE),
        VersionType.COVER to Regex("\\b(cover|커버)\\b", RegexOption.IGNORE_CASE),
        VersionType.ACOUSTIC to Regex("\\b(acoustic|어쿠스틱)\\b", RegexOption.IGNORE_CASE),
        VersionType.INSTRUMENTAL to Regex("\\b(instrumental|inst\\.?|연주곡)\\b", RegexOption.IGNORE_CASE)
    )
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase().replace(Regex("\\s+"), " ").trim()
    /** Detects an explicit marker; absence means UNKNOWN, never ORIGINAL. */
    fun versionHint(raw: String): VersionType = versionMarkers.entries.firstOrNull { it.value.containsMatchIn(raw) }?.key ?: VersionType.UNKNOWN
    /** "Official" in a title is a hint for ranking refs, not proof (§27). */
    fun officialHint(raw: String) = Regex("official", RegexOption.IGNORE_CASE).containsMatchIn(raw)
    fun shortsHint(raw: String) = Regex("#shorts|\\bshorts\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)
}

/** One provider record offered as a possible identity for a PlayableRef. */
data class IdentityCandidate(
    val recordingId: String, val source: String, val title: String, val artist: String,
    val durationMs: Long?, val album: String? = null, val versionType: VersionType = VersionType.UNKNOWN,
    val isrc: String? = null, val providerScore: Double? = null
)
data class IdentityScore(val candidate: IdentityCandidate, val score: Double, val evidence: List<String>, val versionConflict: Boolean)
enum class IdentityDecision { AUTO_ACCEPT, AMBIGUOUS, REJECT }
data class IdentityResolution(val decision: IdentityDecision, val best: IdentityScore?, val runnerUp: IdentityScore?, val reasons: List<String>)

/**
 * §27 identity rules. Auto-accept needs score ≥ .90, a gap ≥ .10 to the runner-up, at least
 * two independent evidence kinds and no version conflict. Everything else is AMBIGUOUS and
 * waits in the queue. ISRC is a strong hint, never sufficient alone. Provider search scores
 * are not probabilities and are excluded from the score.
 */
object IdentityResolver {
    const val ACCEPT = .90; const val GAP = .10; const val MIN_EVIDENCE = 2
    fun score(ref: PlayableRef, refTitle: String, refArtist: String, c: IdentityCandidate): IdentityScore {
        val evidence = mutableListOf<String>(); var s = 0.0
        val t1 = TitleNormalizer.normalize(refTitle); val t2 = TitleNormalizer.normalize(c.title)
        val a1 = TitleNormalizer.normalize(refArtist); val a2 = TitleNormalizer.normalize(c.artist)
        if (t1.isNotEmpty() && (t1 == t2 || t1.contains(t2) && t2.length >= 4 || t2.contains(t1) && t1.length >= 4)) { s += .40; evidence += "TITLE" }
        if (a1.isNotEmpty() && (a1 == a2 || a1.contains(a2) && a2.length >= 2 || a2.contains(a1) && a1.length >= 2)) { s += .30; evidence += "ARTIST" }
        val d1 = ref.durationMs; val d2 = c.durationMs
        if (d1 != null && d2 != null && d1 > 0 && d2 > 0) { if (abs(d1 - d2) <= 3000) { s += .20; evidence += "DURATION" } else if (abs(d1 - d2) > 15000) s -= .30 }
        if (c.isrc != null && ref.matchEvidence.any { it == "ISRC:" + c.isrc }) { s += .10; evidence += "ISRC" }
        val hint = TitleNormalizer.versionHint(refTitle)
        val refVersion = if (ref.versionType != VersionType.UNKNOWN) ref.versionType else hint
        val conflict = refVersion != VersionType.UNKNOWN && c.versionType != VersionType.UNKNOWN && refVersion != c.versionType
        return IdentityScore(c, (Math.round(s * 100) / 100.0).coerceIn(0.0, 1.0), evidence, conflict)
    }
    fun resolve(ref: PlayableRef, refTitle: String, refArtist: String, candidates: List<IdentityCandidate>): IdentityResolution {
        val ranked = candidates.map { score(ref, refTitle, refArtist, it) }.filter { !it.versionConflict }.sortedByDescending { it.score }
        val conflicts = candidates.size - ranked.size
        val best = ranked.firstOrNull() ?: return IdentityResolution(if (conflicts > 0) IdentityDecision.AMBIGUOUS else IdentityDecision.REJECT, null, null, listOf(if (conflicts > 0) "VERSION_CONFLICT_ONLY" else "NO_CANDIDATES"))
        val runner = ranked.getOrNull(1)
        val reasons = mutableListOf<String>()
        if (best.score < ACCEPT) reasons += "SCORE_BELOW_THRESHOLD"
        if (runner != null && best.score - runner.score < GAP) reasons += "RUNNER_UP_TOO_CLOSE"
        if (best.evidence.size < MIN_EVIDENCE) reasons += "SINGLE_EVIDENCE"
        if (best.evidence == listOf("ISRC")) reasons += "ISRC_ALONE"
        return IdentityResolution(if (reasons.isEmpty()) IdentityDecision.AUTO_ACCEPT else IdentityDecision.AMBIGUOUS, best, runner, reasons)
    }
}

/** §27/§29: transitions follow evidence; nothing jumps straight to VALIDATED. */
object MetadataPromotion {
    fun next(current: MetadataStatus, hasPlayableRef: Boolean, hasNormalizedCredits: Boolean, assertionSources: Int): MetadataStatus = when (current) {
        MetadataStatus.DISCOVERED -> if (hasPlayableRef && hasNormalizedCredits) MetadataStatus.BASIC else MetadataStatus.DISCOVERED
        MetadataStatus.BASIC -> if (assertionSources >= 2) MetadataStatus.ENRICHED else MetadataStatus.BASIC
        else -> current   // ENRICHED → VALIDATED only through ValidationGate
    }
}

data class ValidationInput(
    val track: TrackRecord, val identity: IdentityDecision, val refs: List<PlayableRef>,
    val assertions: List<MetadataAssertion>, val constraints: Constraints, val now: Long,
    val requiredFields: Set<String> = emptySet()
)
data class ValidationResult(val decision: MetadataStatus, val reasons: List<String>, val matchedEvidenceIds: List<String>)

/**
 * §31 deterministic ValidationGate. An LLM never approves VALIDATED. Requires: accepted identity,
 * no unresolved version conflict, a currently usable ref, hard constraints checkable, and every
 * required field backed by a live factual or community assertion (AI inference doesn't count).
 */
object ValidationGate {
    fun validate(input: ValidationInput): ValidationResult {
        val reasons = mutableListOf<String>()
        if (input.identity != IdentityDecision.AUTO_ACCEPT) reasons += "IDENTITY_NOT_ACCEPTED"
        val usable = input.refs.filter { it.trackId == input.track.trackId && it.usable(input.now) }
        if (usable.isEmpty()) reasons += "NO_USABLE_PLAYABLE_REF"
        if (usable.any { it.versionType != VersionType.UNKNOWN && input.track.versionType != VersionType.UNKNOWN && it.versionType != input.track.versionType }) reasons += "VERSION_CONFLICT"
        val live = input.assertions.filter { it.trackId == input.track.trackId && it.live(input.now) && it.basis != Basis.AI_INFERRED }
        val genres = live.filter { it.field == "genre" }.map { it.value }.toSet()
        if (input.constraints.excludedGenres.isNotEmpty() && genres.isEmpty()) reasons += "GENRE_UNKNOWN_FOR_EXCLUSION"
        if (genres.any { it in input.constraints.excludedGenres }) reasons += "EXCLUDED_GENRE"
        if (input.track.primaryArtist in input.constraints.excludedArtists) reasons += "EXCLUDED_ARTIST"
        input.requiredFields.filter { f -> live.none { it.field == f } }.forEach { reasons += "MISSING_$it" }
        val evidence = usable.flatMap { it.matchEvidence } + live.map { it.assertionId }
        return ValidationResult(if (reasons.isEmpty()) MetadataStatus.VALIDATED else if (input.track.metadataStatus == MetadataStatus.VALIDATED) MetadataStatus.ENRICHED else input.track.metadataStatus, reasons, evidence.distinct())
    }
}

/** §30: which video to actually open for a Track. Preference order is policy, not fact. */
object PlayableRefResolver {
    private val order = listOf(RefKind.OFFICIAL_AUDIO, RefKind.OFFICIAL_MV, RefKind.LYRICS)
    fun choose(trackId: String, versionType: VersionType, refs: List<PlayableRef>, now: Long): PlayableRef? = refs
        .filter { it.trackId == trackId && it.usable(now) && it.kind != RefKind.SHORTS && it.kind in order }
        .filter { it.versionType == VersionType.UNKNOWN || versionType == VersionType.UNKNOWN || it.versionType == versionType }
        .sortedWith(compareBy<PlayableRef> { order.indexOf(it.kind) }.thenByDescending { it.fetchedAt })
        .firstOrNull()
}

/** §27 identity_alias: split/merge recovery keeps evidence attributable. */
data class IdentityAlias(val oldTrackId: String, val canonicalTrackId: String, val decisionId: String, val effectiveAt: Long)
object AliasResolver {
    fun canonical(trackId: String, aliases: List<IdentityAlias>, at: Long = Long.MAX_VALUE): String {
        var id = trackId; val seen = mutableSetOf<String>()
        while (true) { val a = aliases.filter { it.oldTrackId == id && it.effectiveAt <= at }.maxByOrNull { it.effectiveAt } ?: return id; if (!seen.add(id)) return id; id = a.canonicalTrackId }
    }
}
