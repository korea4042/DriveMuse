package ai.drivemuse.app.discovery

import ai.drivemuse.app.*
import ai.drivemuse.app.catalog.*
import ai.drivemuse.app.knowledge.*
import ai.drivemuse.domain.*
import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/*
 * Technical design v2.3 §17, §19, §29, §31.
 *
 * One collection run: snapshot settings/generation → lease → plan seeds → fetch resources →
 * resolve identity → enrich → deterministic ValidationGate → store. Network calls happen
 * outside DB transactions. Nothing in this file writes listening evidence or sends a
 * playback command (D02/T03).
 */

data class RunSnapshot(val scope: String, val generation: Long, val profileVersion: Long, val configVersion: Long, val profile: SurveyProfile, val likedArtists: Set<String>, val constraints: ai.drivemuse.domain.Constraints)
data class RunReport(val runId: String, val status: String, val progress: RunProgress, val reasons: List<String>)

class DiscoveryCoordinator(
    private val db: DriveDatabase,
    private val youtube: YouTubeApi?,
    private val registry: ProviderRegistry,
    private val limits: RunLimits = RunLimits(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val catalog = db.catalog()
    private val leaseTtl = 3 * 60_000L
    private val refTtl = 30L * 86_400_000
    val rulesetVersion = "validation.v2.3"

    /** Entry point for both the periodic worker and foreground top-ups. Returns null if another owner holds the lease. */
    suspend fun run(snapshot: RunSnapshot, reason: String): RunReport? {
        val owner = "run:" + UUID.randomUUID(); val now = clock()
        val generation = catalog.acquireLease(snapshot.scope, owner, now, leaseTtl) ?: return null
        if (generation != snapshot.generation) { catalog.releaseLease(snapshot.scope, owner, false, now, now); return RunReport("-", "STALE", RunProgress(), listOf("GENERATION_CHANGED")) }
        val runId = UUID.randomUUID().toString(); var progress = RunProgress(startedAt = now); val reasons = mutableListOf(reason); var status = "SUCCESS"; var errorCode: String? = null
        catalog.putRun(CollectionRunEntity(runId, snapshot.scope, generation, snapshot.profileVersion, snapshot.configVersion, "RUNNING", now, null, 0, 0, 0, 0, null, Json.strings(reasons)))
        try {
            catalog.pruneRefs(clock()); catalog.pruneAssertions(clock()); catalog.pruneQuota(clock())
            progress = refreshSeeds(snapshot, progress)
            progress = fetchResources(snapshot, progress, reasons)
            progress = resolveAndValidate(snapshot, progress, reasons)
            if (progress.exhausted(limits, clock())) status = "PARTIAL"
        } catch (e: CancellationException) { status = "CANCELLED"; throw e }
        catch (e: ProviderAuthRequired) { status = "NEEDS_AUTH"; errorCode = e.provider.name }
        catch (e: ProviderRateLimited) { status = "RATE_LIMITED"; errorCode = e.provider.name; reasons += "retryAfter:${e.retryAfterSec ?: -1}" }
        catch (e: QuotaExceededException) { status = "QUOTA"; errorCode = "YOUTUBE_QUOTA" }
        catch (e: Exception) { status = if (progress.inserted > 0) "PARTIAL" else "FAILED"; errorCode = e::class.simpleName }
        finally {
            val end = clock()
            val nextEligible = when (status) { "RATE_LIMITED" -> end + Backoff.delayMs(1, reasons.lastOrNull()?.substringAfter("retryAfter:")?.toLongOrNull()?.takeIf { it > 0 }); "FAILED" -> end + Backoff.delayMs(2); "QUOTA" -> end + 6 * 3_600_000L; else -> end + 60_000L }
            db.withTransaction {
                if (catalog.control(snapshot.scope)?.generation == generation) catalog.putRun(CollectionRunEntity(runId, snapshot.scope, generation, snapshot.profileVersion, snapshot.configVersion, status, now, end, progress.inserted, progress.updated, progress.rejected, progress.requests, errorCode, Json.strings(reasons)))
                catalog.releaseLease(snapshot.scope, owner, status == "SUCCESS" || status == "PARTIAL", end, nextEligible)
            }
        }
        return RunReport(runId, status, progress, reasons)
    }

    private suspend fun refreshSeeds(s: RunSnapshot, p: RunProgress): RunProgress {
        catalog.dropStaleSeeds(s.scope, s.profileVersion)
        val gaps = coverageGaps(s)
        catalog.insertSeeds(DiscoveryPlanner.seeds(s.profile, s.likedArtists, s.profileVersion, gaps).map { DiscoverySeedEntity.from(s.scope, it) })
        return p
    }

    /** §29 diversity: over-concentrated artists push COVERAGE_GAP seeds toward other adjacent tags. */
    private suspend fun coverageGaps(s: RunSnapshot): List<String> {
        val tracks = catalog.validatedTracks().map { TrackRecord(it.trackId, it.title, listOf(ArtistCredit(it.primaryArtist)), it.durationMs, it.releaseDate, versionType = VersionType.valueOf(it.versionType)) }
        if (tracks.size < 50) return emptyList()
        val report = DiversityAudit.report(tracks, { it.releaseDate?.take(3)?.plus("0s") }, { null }, { null })
        return if (report.artistConcentrated) s.profile.preferences.filter { it.axis == "genre" }.map { it.value }.take(2) else emptyList()
    }

    /** Fetch new provider resources into playable_ref + discovery_item. Budget bands from the plan; YouTube search is metered by the quota ledger. */
    private suspend fun fetchResources(s: RunSnapshot, start: RunProgress, reasons: MutableList<String>): RunProgress {
        var p = start; val api = youtube ?: run { reasons += "YOUTUBE_UNCONFIGURED"; return p }
        val seeds = catalog.seeds(s.scope).map { it.domain() }
        val plans = DiscoveryPlanner.plan(seeds, s.profileVersion, clock(), s.profile.exclusions + s.constraints.excludedArtists)
        val budget = CollectionBudget.of(limits.maxInserts); var used = mutableMapOf(BudgetBand.ADJACENT to 0, BudgetBand.EXPANSION to 0, BudgetBand.EXPERIMENT to 0)
        val window = QuotaWindows.localDayKey(clock(), java.util.TimeZone.getDefault().rawOffset.toLong())
        for (plan in plans) {
            currentCoroutineContext().ensureActive()
            if (p.exhausted(limits, clock())) break
            val cap = when (plan.band) { BudgetBand.ADJACENT -> budget.adjacent; BudgetBand.EXPANSION -> budget.expansion; BudgetBand.EXPERIMENT -> budget.experiment }
            if (used.getValue(plan.band) >= cap) continue
            val seed = seeds.first { it.seedId == plan.seedId }
            if (!catalog.reserveQuota("youtube", window, "search.list", Quota.SEARCH_COST, Quota.SEARCH_CALLS_PER_DAY * Quota.SEARCH_COST, (clock() / 86_400_000 + 1) * 86_400_000)) { reasons += "LOCAL_SEARCH_BUDGET"; break }
            val query = when (seed.kind) { SeedKind.PREFERRED_ARTIST, SeedKind.RELATED_ARTIST -> seed.value; SeedKind.GENRE_TAG, SeedKind.COVERAGE_GAP -> seed.value + " music"; else -> seed.value }
            val videos = try { p = p.copy(requests = p.requests + 1); api.searchMusic(query, 25) } finally { catalog.settleQuota("youtube", window, "search.list", Quota.SEARCH_COST) }
            val now = clock(); var inserted = 0; var updated = 0
            db.withTransaction {
                if (catalog.control(s.scope)?.generation != s.generation) throw IllegalStateException("STALE_GENERATION")
                for (v in videos.filter { Policy.playableTrack(it.categoryId, it.live, it.durationSec) && Policy.validTrackId(it.id) }) {
                    if (inserted >= cap - used.getValue(plan.band) || p.inserted + inserted >= limits.maxInserts) break
                    val ref = PlayableRefEntity("youtube", v.id, null, if (TitleNormalizer.shortsHint(v.title)) RefKind.SHORTS.name else RefKind.UNKNOWN.name, TitleNormalizer.versionHint(v.title).name, MatchStatus.UNMATCHED.name, "[]", v.title, v.channel.removeSuffix(" - Topic"), v.durationSec * 1000L, now, now + refTtl, Availability.UNKNOWN.name)
                    if (catalog.insertRef(ref) == -1L) { catalog.ref("youtube", v.id)?.let { catalog.updateRef(it.copy(fetchedAt = now, expiresAt = now + refTtl, title = v.title)) }; updated++ }
                    else if (catalog.insertDiscoveryItem(DiscoveryItemEntity("yt:${v.id}", s.scope, "seed:${seed.seedId}", v.id, "youtube", null, QueueStatus.PENDING.name, 0, 0, s.generation, now, null, plan.band.name)) != -1L) inserted++
                }
                catalog.updateSeed(DiscoverySeedEntity.from(s.scope, seed.copy(lastAttemptAt = now, nextEligibleAt = now + 6 * 3_600_000L)))
            }
            used[plan.band] = used.getValue(plan.band) + inserted
            p = p.copy(inserted = p.inserted + inserted, updated = p.updated + updated)
        }
        return p
    }

    /** Resolve identity (MusicBrainz), enrich (all FETCH_METADATA providers), validate (deterministic). */
    private suspend fun resolveAndValidate(s: RunSnapshot, start: RunProgress, reasons: MutableList<String>): RunProgress {
        var p = start
        val identity = registry.with(Capability.LOOKUP_IDENTITY); val enrichers = registry.with(Capability.FETCH_METADATA)
        if (identity.isEmpty()) reasons += "NO_IDENTITY_PROVIDER"
        for (item in catalog.dueItems(s.scope, s.generation, clock(), 20)) {
            currentCoroutineContext().ensureActive()
            if (p.exhausted(limits, clock())) break
            val ref = catalog.ref(item.provider, item.rawRef)
            if (ref == null) { catalog.updateDiscoveryItem(item.copy(queueStatus = QueueStatus.QUARANTINED.name, lastError = "REF_MISSING")); continue }
            if (ref.kind == RefKind.SHORTS.name) { catalog.updateDiscoveryItem(item.copy(queueStatus = QueueStatus.DONE.name, lastError = "SHORTS_EXCLUDED")); p = p.copy(rejected = p.rejected + 1); continue }
            catalog.updateDiscoveryItem(item.copy(queueStatus = QueueStatus.RESOLVING.name, attempts = item.attempts + 1))
            val result = runCatching {
                val candidates = identity.flatMap { pr -> p = p.copy(requests = p.requests + 1); pr.lookupIdentity(ref.title, ref.channel, ref.durationMs, null) }
                IdentityResolver.resolve(ref.domain(), ref.title, ref.channel, candidates)
            }
            val failure = result.exceptionOrNull()
            if (failure is ProviderAuthRequired || failure is ProviderRateLimited) throw failure
            if (failure != null) { catalog.updateDiscoveryItem(item.copy(queueStatus = QueueStatus.RETRY_WAIT.name, retryAt = clock() + Backoff.delayMs(item.attempts), lastError = failure::class.simpleName)); continue }
            val resolution = result.getOrThrow()
            val now = clock()
            when (resolution.decision) {
                IdentityDecision.AUTO_ACCEPT -> {
                    val best = resolution.best!!; val c = best.candidate
                    val trackId = db.withTransaction {
                        if (catalog.control(s.scope)?.generation != s.generation) throw IllegalStateException("STALE_GENERATION")
                        val existing = catalog.tracksByIdentifier(IdentifierType.RECORDING_MBID.name, c.recordingId).firstOrNull()
                        val id = existing ?: UUID.randomUUID().toString()
                        if (existing == null) {
                            catalog.insertTrack(TrackEntity(id, c.title, c.artist, JSONArray(listOf(c.artist)).toString(), c.durationMs, null, null, c.versionType.name, MetadataStatus.BASIC.name, 1, 1, null, false, now, now))
                            catalog.putIdentifiers(listOfNotNull(TrackIdentifierEntity(id, IdentifierType.RECORDING_MBID.name, c.recordingId, c.source, now), c.isrc?.let { TrackIdentifierEntity(id, IdentifierType.ISRC.name, it, c.source, now) }))
                        }
                        catalog.updateRef(ref.copy(trackId = id, matchStatus = MatchStatus.CONFIRMED.name, matchEvidenceJson = Json.strings(best.evidence.map { "$it:${c.recordingId}" }), availability = Availability.AVAILABLE.name, kind = if (ref.kind == RefKind.UNKNOWN.name) guessKind(ref.title) else ref.kind))
                        catalog.updateDiscoveryItem(item.copy(proposedTrackId = id, queueStatus = QueueStatus.ENRICHING.name))
                        id
                    }
                    p = enrichAndValidate(s, trackId, enrichers, p)
                    catalog.updateDiscoveryItem(item.copy(proposedTrackId = trackId, queueStatus = QueueStatus.DONE.name))
                }
                IdentityDecision.AMBIGUOUS -> { catalog.updateRef(ref.copy(matchStatus = MatchStatus.AMBIGUOUS.name, matchEvidenceJson = Json.strings(resolution.reasons))); catalog.updateDiscoveryItem(item.copy(queueStatus = QueueStatus.RETRY_WAIT.name, retryAt = now + 7 * 86_400_000L, lastError = resolution.reasons.joinToString(","))) }
                IdentityDecision.REJECT -> { catalog.updateRef(ref.copy(matchStatus = MatchStatus.REJECTED.name)); catalog.updateDiscoveryItem(item.copy(queueStatus = QueueStatus.QUARANTINED.name, lastError = "NO_IDENTITY")); p = p.copy(rejected = p.rejected + 1) }
            }
        }
        return p
    }

    private fun guessKind(title: String) = when { TitleNormalizer.shortsHint(title) -> RefKind.SHORTS.name; Regex("lyric", RegexOption.IGNORE_CASE).containsMatchIn(title) -> RefKind.LYRICS.name; Regex("\\b(MV|M/V|music video)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) && TitleNormalizer.officialHint(title) -> RefKind.OFFICIAL_MV.name; Regex("official audio|audio", RegexOption.IGNORE_CASE).containsMatchIn(title) -> RefKind.OFFICIAL_AUDIO.name; else -> RefKind.UNKNOWN.name }

    private suspend fun enrichAndValidate(s: RunSnapshot, trackId: String, enrichers: List<MusicKnowledgeProvider>, start: RunProgress): RunProgress {
        var p = start; val row = catalog.track(trackId) ?: return p
        val record = TrackRecord(row.trackId, row.title, listOf(ArtistCredit(row.primaryArtist)), row.durationMs, row.releaseDate, row.releasePrecision, VersionType.valueOf(row.versionType), MetadataStatus.valueOf(row.metadataStatus), row.identityVersion, row.metadataVersion, catalog.identifiers(trackId).map { TrackIdentifier(IdentifierType.valueOf(it.type), it.value, it.source) })
        for (pr in enrichers) {
            if (p.exhausted(limits, clock())) break
            val assertions = runCatching { p = p.copy(requests = p.requests + 1); pr.fetchMetadata(record) }.getOrElse { e -> if (e is ProviderAuthRequired) emptyList() else if (e is ProviderRateLimited) throw e else emptyList() }
            if (assertions.isNotEmpty()) catalog.putAssertions(assertions.map { MetadataAssertionEntity.from(it) })
        }
        return validate(s, trackId).let { p }
    }

    /** §31 ValidationGate → track.metadataStatus + validation_decision. Genre mapping from tags is COMMUNITY_TAG, so exclusions still require a checkable genre. */
    suspend fun validate(s: RunSnapshot, trackId: String) {
        val now = clock(); val row = catalog.track(trackId) ?: return
        val refs = catalog.refsFor(trackId).map { it.domain() }
        val assertions = catalog.assertions(trackId, now).map { it.domain() } + GenreMapper.fromTags(catalog.assertions(trackId, now).map { it.domain() })
        val sources = catalog.assertionSources(trackId, now)
        val status = MetadataPromotion.next(MetadataPromotion.next(MetadataStatus.valueOf(row.metadataStatus), refs.isNotEmpty(), row.primaryArtist.isNotBlank(), sources), refs.isNotEmpty(), true, sources)
        val record = TrackRecord(row.trackId, row.title, listOf(ArtistCredit(row.primaryArtist)), row.durationMs, row.releaseDate, row.releasePrecision, VersionType.valueOf(row.versionType), status, row.identityVersion, row.metadataVersion)
        val identity = if (refs.any { it.matchStatus == MatchStatus.CONFIRMED }) IdentityDecision.AUTO_ACCEPT else IdentityDecision.AMBIGUOUS
        val result = ValidationGate.validate(ValidationInput(record, identity, refs, assertions, s.constraints, now))
        val input = "${row.identityVersion}|${row.metadataVersion}|${refs.map { it.resourceId }.sorted()}|${assertions.map { it.assertionId }.sorted()}|${s.constraints}"
        val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        val release = assertions.firstOrNull { it.field == "releaseDate" && it.factual }
        db.withTransaction {
            if (catalog.control(s.scope)?.generation != s.generation) return@withTransaction
            catalog.putDecision(ValidationDecisionEntity(UUID.randomUUID().toString(), trackId, row.identityVersion, rulesetVersion, hash, Json.strings(result.matchedEvidenceIds), result.decision.name, Json.strings(result.reasons), now))
            catalog.updateTrack(row.copy(metadataStatus = result.decision.name, eligible = result.decision == MetadataStatus.VALIDATED, releaseDate = release?.value ?: row.releaseDate, releasePrecision = assertions.firstOrNull { it.field == "releasePrecision" }?.value ?: row.releasePrecision, updatedAt = now))
        }
    }

    /** Re-run the gate for every non-final track when constraints or provider data change (§28: revoked evidence removes eligibility). */
    suspend fun revalidateAll(s: RunSnapshot) { (catalog.validatedTracks() + catalog.pendingTracks()).forEach { validate(s, it.trackId) } }

    suspend fun poolHealth(now: Long = clock()): PoolHealth = PoolHealth(catalog.validatedTracks().count { t -> catalog.refsFor(t.trackId).any { it.domain().usable(now) } })
}

/** Community tags → genre assertions stay COMMUNITY_TAG (never PROVIDER_FACT). Only a small allowlist maps; unknown tags stay tags. */
object GenreMapper {
    private val map = mapOf("pop" to "POP", "k-pop" to "POP", "kpop" to "POP", "rnb" to "RNB", "r&b" to "RNB", "soul" to "RNB", "hip-hop" to "HIP_HOP", "hip hop" to "HIP_HOP", "rap" to "HIP_HOP", "rock" to "ROCK", "indie rock" to "ROCK", "jazz" to "JAZZ", "classical" to "CLASSICAL", "electronic" to "ELECTRONIC", "electronica" to "ELECTRONIC", "house" to "ELECTRONIC", "edm" to "ELECTRONIC")
    fun fromTags(assertions: List<MetadataAssertion>): List<MetadataAssertion> = assertions.filter { it.field == "tag" && it.basis == Basis.COMMUNITY_TAG }.mapNotNull { t -> map[t.value.lowercase()]?.let { g -> t.copy(assertionId = t.assertionId + ":genre", field = "genre", value = g, evidenceIds = listOf(t.assertionId)) } }.distinctBy { it.source to it.value }
}

/** §19 periodic worker: unmetered network by default, no exact timing, no playback, no location. */
class MetadataSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val factory = DiscoveryRuntime.factory ?: return Result.success()  // app decides wiring; worker never builds credentials itself
        val runtime = factory(app)
        val snapshot = runtime.snapshot() ?: return Result.success()        // survey not completed, or automation disabled
        val report = runtime.coordinator.run(snapshot, inputData.getString("reason") ?: "PERIODIC") ?: return Result.success()
        return when (report.status) { "NEEDS_AUTH", "QUOTA", "STALE", "SUCCESS", "PARTIAL" -> Result.success(); "RATE_LIMITED" -> Result.retry(); else -> if (runAttemptCount < 3) Result.retry() else Result.success() }
    }
    companion object {
        const val PERIODIC = "drivemuse.metadata-sync"
        fun schedule(context: Context, unmeteredOnly: Boolean, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(PERIODIC); return }
            val constraints = Constraints.Builder().setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<MetadataSyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).setInputData(workDataOf("reason" to "PERIODIC")).build())
        }
        fun topUp(context: Context, reason: String) {
            WorkManager.getInstance(context).enqueueUniqueWork("$PERIODIC.once", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MetadataSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setInputData(workDataOf("reason" to reason)).build())
        }
    }
}

/** Wiring hook so the worker uses the same encrypted config and DB as the app. */
class DiscoveryRuntime(val coordinator: DiscoveryCoordinator, val snapshot: suspend () -> RunSnapshot?) {
    companion object { @Volatile var factory: ((Context) -> DiscoveryRuntime)? = null }
}
