package ai.drivemuse.app.catalog

import androidx.room.*
import ai.drivemuse.domain.*
import kotlinx.coroutines.flow.Flow

/*
 * Technical design v2.3 §18, §27. Logical contracts from the tables there, fixed as Room schema.
 * Every table is additive over v3. Nothing here migrates `played` into listening evidence.
 */

@Entity(tableName = "track", indices = [Index("metadataStatus"), Index("primaryArtist")])
data class TrackEntity(
    @PrimaryKey val trackId: String, val title: String, val primaryArtist: String, val artistCreditsJson: String,
    val durationMs: Long?, val releaseDate: String?, val releasePrecision: String?, val versionType: String,
    val metadataStatus: String, val identityVersion: Long, val metadataVersion: Long, val workGroupId: String?,
    val eligible: Boolean, val createdAt: Long, val updatedAt: Long
)

@Entity(tableName = "track_identifier", primaryKeys = ["trackId", "type", "value", "source"], indices = [Index("type", "value")])
data class TrackIdentifierEntity(val trackId: String, val type: String, val value: String, val source: String, val fetchedAt: Long)

/** §27: ISRC is never a global unique key — hence no unique index on (type,value). */
@Entity(tableName = "playable_ref", primaryKeys = ["provider", "resourceId"], indices = [Index("trackId"), Index("expiresAt"), Index("matchStatus")])
data class PlayableRefEntity(
    val provider: String, val resourceId: String, val trackId: String?, val kind: String, val versionType: String,
    val matchStatus: String, val matchEvidenceJson: String, val title: String, val channel: String, val durationMs: Long?,
    val fetchedAt: Long, val expiresAt: Long, val availability: String
) {
    fun domain() = PlayableRef(provider, resourceId, trackId, RefKind.valueOf(kind), VersionType.valueOf(versionType), MatchStatus.valueOf(matchStatus), Json.strings(matchEvidenceJson), durationMs, fetchedAt, expiresAt, Availability.valueOf(availability))
}

@Entity(tableName = "metadata_assertion", indices = [Index("trackId", "field"), Index("expiresAt")])
data class MetadataAssertionEntity(
    @PrimaryKey val assertionId: String, val trackId: String, val field: String, val value: String, val basis: String,
    val source: String, val sourceRecordId: String?, val confidence: Double, val fetchedAt: Long, val expiresAt: Long,
    val licenseRef: String?, val evidenceIdsJson: String, val modelId: String? = null, val promptVersion: String? = null
) {
    fun domain() = MetadataAssertion(assertionId, trackId, field, value, Basis.valueOf(basis), source, sourceRecordId, confidence, fetchedAt, expiresAt, licenseRef, Json.strings(evidenceIdsJson))
    companion object { fun from(a: MetadataAssertion, modelId: String? = null, promptVersion: String? = null) = MetadataAssertionEntity(a.assertionId, a.trackId, a.field, a.value, a.basis.name, a.source, a.sourceRecordId, a.confidence, a.fetchedAt, a.expiresAt, a.licenseRef, Json.strings(a.evidenceIds), modelId, promptVersion) }
}

@Entity(tableName = "discovery_item", indices = [Index("scope", "queueStatus"), Index("retryAt"), Index(value = ["scope", "sourceKey", "rawRef"], unique = true)])
data class DiscoveryItemEntity(
    @PrimaryKey val discoveryItemId: String, val scope: String, val sourceKey: String, val rawRef: String, val provider: String,
    val proposedTrackId: String?, val queueStatus: String, val attempts: Int, val retryAt: Long, val generation: Long,
    val discoveredAt: Long, val lastError: String?, val budgetBand: String
)

@Entity(tableName = "enrichment_job", indices = [Index(value = ["scope", "itemId", "provider", "metadataVersion"], unique = true), Index("state")])
data class EnrichmentJobEntity(@PrimaryKey val jobId: String, val scope: String, val itemId: String, val provider: String, val metadataVersion: Long, val state: String, val attempts: Int, val leaseOwner: String?, val leaseUntil: Long, val configVersion: Long)

@Entity(tableName = "validation_decision", indices = [Index("trackId", "identityVersion")])
data class ValidationDecisionEntity(@PrimaryKey val decisionId: String, val trackId: String, val identityVersion: Long, val rulesetVersion: String, val inputHash: String, val matchedEvidenceIdsJson: String, val decision: String, val reasonsJson: String, val decidedAt: Long)

@Entity(tableName = "identity_alias", indices = [Index("oldTrackId")])
data class IdentityAliasEntity(@PrimaryKey(autoGenerate = true) val rowId: Long = 0, val oldTrackId: String, val canonicalTrackId: String, val decisionId: String, val effectiveAt: Long) {
    fun domain() = IdentityAlias(oldTrackId, canonicalTrackId, decisionId, effectiveAt)
}

/** §17/§18 personal experience per canonical track. videoId stays in playable_ref as linkage only. */
@Entity(tableName = "track_experience", primaryKeys = ["accountScope", "trackId"], indices = [Index("accountScope", "lastConfirmedListenAt")])
data class TrackExperienceEntity(
    val accountScope: String, val trackId: String, val confirmedListenCount: Int, val lastConfirmedListenAt: Long?,
    val explicitPreference: Int?, val surveySeed: Boolean, val exposureCount: Int, val lastExposureAt: Long?,
    val matchAmbiguous: Boolean, val historyCoverageSince: Long?
) {
    fun domain() = TrackExperience(trackId, confirmedListenCount, lastConfirmedListenAt, explicitPreference, surveySeed, exposureCount, lastExposureAt, matchAmbiguous, historyCoverageSince)
}

@Entity(tableName = "user_track_context", primaryKeys = ["accountScope", "trackId", "contextType"])
data class UserTrackContextEntity(val accountScope: String, val trackId: String, val contextType: String, val validAttempts: Int, val positiveWeight: Double, val negativeWeight: Double, val lastUpdated: Long)

@Entity(tableName = "discovery_seed", primaryKeys = ["accountScope", "seedId"])
data class DiscoverySeedEntity(val accountScope: String, val seedId: String, val kind: String, val value: String, val profileVersion: Long, val cursor: String?, val lastAttemptAt: Long, val nextEligibleAt: Long, val evidenceIdsJson: String) {
    fun domain() = DiscoverySeed(seedId, SeedKind.valueOf(kind), value, profileVersion, cursor, lastAttemptAt, nextEligibleAt, Json.strings(evidenceIdsJson))
    companion object { fun from(scope: String, s: DiscoverySeed) = DiscoverySeedEntity(scope, s.seedId, s.kind.name, s.value, s.profileVersion, s.cursor, s.lastAttemptAt, s.nextEligibleAt, Json.strings(s.evidenceIds)) }
}

@Entity(tableName = "collection_run", indices = [Index("scope", "status")])
data class CollectionRunEntity(@PrimaryKey val runId: String, val scope: String, val generation: Long, val profileVersion: Long, val configVersion: Long, val status: String, val startedAt: Long, val finishedAt: Long?, val inserted: Int, val updated: Int, val rejected: Int, val requests: Int, val errorCode: String?, val reasonsJson: String)

@Entity(tableName = "collection_control")
data class CollectionControlEntity(@PrimaryKey val scope: String, val generation: Long, val leaseOwner: String?, val leaseUntil: Long, val lastSuccessAt: Long?, val nextEligibleAt: Long, val autoEnabled: Boolean, val unmeteredOnly: Boolean)

@Entity(tableName = "quota_ledger", primaryKeys = ["budgetScope", "windowKey", "endpoint"])
data class QuotaLedgerEntity(val budgetScope: String, val windowKey: String, val endpoint: String, val reserved: Int, val consumed: Int, val limit: Int, val resetAt: Long) {
    fun domain() = QuotaWindow(budgetScope, windowKey, endpoint, reserved, consumed, limit, resetAt)
    companion object { fun from(w: QuotaWindow) = QuotaLedgerEntity(w.budgetScope, w.windowKey, w.endpoint, w.reserved, w.consumed, w.limit, w.resetAt) }
}

/** §23: no key or secret plaintext here — credentialRef points at the encrypted CredentialStore entry. */
@Entity(tableName = "integration_config")
data class IntegrationConfigEntity(@PrimaryKey val provider: String, val authMode: String, val clientId: String?, val endpointId: String?, val modelId: String?, val credentialRef: String?, val configVersion: Long, val status: String, val error: String, val lastValidatedAt: Long?, val presentKeysJson: String) {
    fun domain() = IntegrationConfig(ProviderId.valueOf(provider), AuthMode.valueOf(authMode), clientId, endpointId, modelId, credentialRef, configVersion, IntegrationStatus.valueOf(status), IntegrationError.valueOf(error), lastValidatedAt, Json.strings(presentKeysJson).toSet())
    companion object { fun from(c: IntegrationConfig) = IntegrationConfigEntity(c.provider.name, c.authMode.name, c.clientId, c.endpointId, c.modelId, c.credentialRef, c.configVersion, c.status.name, c.error.name, c.lastValidatedAt, Json.strings(c.presentKeys.toList())) }
}

object Json {
    fun strings(list: List<String>): String = org.json.JSONArray(list).toString()
    fun strings(json: String): List<String> = runCatching { val a = org.json.JSONArray(json); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
}

data class PoolCounts(val validated: Int, val eligible: Int, val noHistory: Int)

@Dao abstract class CatalogDao {
    // track
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertTrack(t: TrackEntity): Long
    @Update abstract suspend fun updateTrack(t: TrackEntity)
    @Query("SELECT * FROM track WHERE trackId = :id") abstract suspend fun track(id: String): TrackEntity?
    @Query("SELECT * FROM track WHERE metadataStatus = 'VALIDATED' AND eligible = 1") abstract suspend fun validatedTracks(): List<TrackEntity>
    @Query("SELECT COUNT(*) FROM track WHERE metadataStatus = 'VALIDATED' AND eligible = 1") abstract suspend fun validatedCount(): Int
    @Query("SELECT * FROM track WHERE metadataStatus IN ('BASIC','ENRICHED')") abstract suspend fun pendingTracks(): List<TrackEntity>
    @Query("UPDATE track SET eligible = :eligible, updatedAt = :now WHERE trackId = :id") abstract suspend fun setEligible(id: String, eligible: Boolean, now: Long)
    @Query("UPDATE track SET metadataStatus = :status, updatedAt = :now WHERE trackId = :id AND identityVersion = :identityVersion") abstract suspend fun setStatus(id: String, status: String, identityVersion: Long, now: Long): Int
    @Query("DELETE FROM track") abstract suspend fun clearTracks()
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun putIdentifiers(rows: List<TrackIdentifierEntity>)
    @Query("SELECT * FROM track_identifier WHERE trackId = :id") abstract suspend fun identifiers(id: String): List<TrackIdentifierEntity>
    @Query("SELECT trackId FROM track_identifier WHERE type = :type AND value = :value LIMIT 5") abstract suspend fun tracksByIdentifier(type: String, value: String): List<String>

    // playable_ref
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertRef(r: PlayableRefEntity): Long
    @Update abstract suspend fun updateRef(r: PlayableRefEntity)
    @Query("SELECT * FROM playable_ref WHERE provider = :provider AND resourceId = :id") abstract suspend fun ref(provider: String, id: String): PlayableRefEntity?
    @Query("SELECT * FROM playable_ref WHERE trackId = :trackId") abstract suspend fun refsFor(trackId: String): List<PlayableRefEntity>
    @Query("SELECT * FROM playable_ref WHERE trackId IN (:ids) AND expiresAt > :now") abstract suspend fun refsFor(ids: List<String>, now: Long): List<PlayableRefEntity>
    @Query("SELECT * FROM playable_ref WHERE matchStatus IN ('UNMATCHED','PROPOSED') AND expiresAt > :now LIMIT :limit") abstract suspend fun unresolvedRefs(now: Long, limit: Int): List<PlayableRefEntity>
    @Query("DELETE FROM playable_ref WHERE expiresAt < :cutoff") abstract suspend fun pruneRefs(cutoff: Long)
    @Query("DELETE FROM playable_ref") abstract suspend fun clearRefs()

    // assertions
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putAssertions(rows: List<MetadataAssertionEntity>)
    @Query("SELECT * FROM metadata_assertion WHERE trackId = :trackId AND expiresAt > :now") abstract suspend fun assertions(trackId: String, now: Long): List<MetadataAssertionEntity>
    @Query("SELECT * FROM metadata_assertion WHERE trackId IN (:ids) AND expiresAt > :now") abstract suspend fun assertions(ids: List<String>, now: Long): List<MetadataAssertionEntity>
    @Query("SELECT COUNT(DISTINCT source) FROM metadata_assertion WHERE trackId = :trackId AND expiresAt > :now AND basis != 'AI_INFERRED'") abstract suspend fun assertionSources(trackId: String, now: Long): Int
    @Query("DELETE FROM metadata_assertion WHERE expiresAt < :cutoff") abstract suspend fun pruneAssertions(cutoff: Long)
    @Query("DELETE FROM metadata_assertion") abstract suspend fun clearAssertions()

    // discovery queue
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertDiscoveryItem(row: DiscoveryItemEntity): Long
    @Update abstract suspend fun updateDiscoveryItem(row: DiscoveryItemEntity)
    @Query("SELECT * FROM discovery_item WHERE scope = :scope AND queueStatus IN ('PENDING','RETRY_WAIT') AND retryAt <= :now AND generation = :generation ORDER BY discoveredAt LIMIT :limit") abstract suspend fun dueItems(scope: String, generation: Long, now: Long, limit: Int): List<DiscoveryItemEntity>
    @Query("SELECT queueStatus, COUNT(*) AS n FROM discovery_item WHERE scope = :scope GROUP BY queueStatus") abstract suspend fun queueCounts(scope: String): List<StatusCount>
    @Query("DELETE FROM discovery_item WHERE scope = :scope") abstract suspend fun clearDiscovery(scope: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putDecision(row: ValidationDecisionEntity)
    @Query("SELECT * FROM validation_decision WHERE trackId = :trackId ORDER BY decidedAt DESC LIMIT 1") abstract suspend fun latestDecision(trackId: String): ValidationDecisionEntity?
    @Insert abstract suspend fun putAlias(row: IdentityAliasEntity)
    @Query("SELECT * FROM identity_alias") abstract suspend fun aliases(): List<IdentityAliasEntity>

    // experience
    @Query("SELECT * FROM track_experience WHERE accountScope = :scope AND trackId = :trackId") abstract suspend fun experience(scope: String, trackId: String): TrackExperienceEntity?
    @Query("SELECT * FROM track_experience WHERE accountScope = :scope AND trackId IN (:ids)") abstract suspend fun experiences(scope: String, ids: List<String>): List<TrackExperienceEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putExperience(row: TrackExperienceEntity)
    @Query("DELETE FROM track_experience WHERE accountScope = :scope") abstract suspend fun clearExperience(scope: String)
    @Transaction open suspend fun recordExposure(scope: String, trackId: String, now: Long, coverageSince: Long) {
        val e = experience(scope, trackId) ?: TrackExperienceEntity(scope, trackId, 0, null, null, false, 0, null, false, coverageSince)
        putExperience(e.copy(exposureCount = e.exposureCount + 1, lastExposureAt = now))
    }
    @Transaction open suspend fun recordConfirmedListen(scope: String, trackId: String, now: Long, coverageSince: Long) {
        val e = experience(scope, trackId) ?: TrackExperienceEntity(scope, trackId, 0, null, null, false, 0, null, false, coverageSince)
        putExperience(e.copy(confirmedListenCount = e.confirmedListenCount + 1, lastConfirmedListenAt = now))
    }
    @Transaction open suspend fun recordExplicit(scope: String, trackId: String, value: Int, coverageSince: Long) {
        val e = experience(scope, trackId) ?: TrackExperienceEntity(scope, trackId, 0, null, null, false, 0, null, false, coverageSince)
        putExperience(e.copy(explicitPreference = value))
    }

    // seeds / runs / control / quota
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertSeeds(rows: List<DiscoverySeedEntity>)
    @Update abstract suspend fun updateSeed(row: DiscoverySeedEntity)
    @Query("SELECT * FROM discovery_seed WHERE accountScope = :scope") abstract suspend fun seeds(scope: String): List<DiscoverySeedEntity>
    @Query("DELETE FROM discovery_seed WHERE accountScope = :scope AND profileVersion != :profileVersion") abstract suspend fun dropStaleSeeds(scope: String, profileVersion: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putRun(row: CollectionRunEntity)
    @Query("SELECT * FROM collection_run WHERE scope = :scope ORDER BY startedAt DESC LIMIT :limit") abstract suspend fun runs(scope: String, limit: Int): List<CollectionRunEntity>
    @Query("SELECT * FROM collection_run WHERE scope = :scope ORDER BY startedAt DESC LIMIT 1") abstract fun observeLastRun(scope: String): Flow<CollectionRunEntity?>
    @Query("SELECT * FROM collection_control WHERE scope = :scope") abstract suspend fun control(scope: String): CollectionControlEntity?
    @Query("SELECT * FROM collection_control WHERE scope = :scope") abstract fun observeControl(scope: String): Flow<CollectionControlEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putControl(row: CollectionControlEntity)
    /** §19 lease: DB compare-and-set. Returns the generation to work under, or null if another owner holds it. */
    @Transaction open suspend fun acquireLease(scope: String, owner: String, now: Long, ttlMs: Long): Long? {
        val c = control(scope) ?: CollectionControlEntity(scope, 1, null, 0, null, 0, true, true).also { putControl(it) }
        val lease = LeasePolicy.acquire(c.leaseOwner?.let { Lease(scope, it, c.leaseUntil, c.generation) }, scope, owner, now, ttlMs, c.generation) ?: return null
        putControl(c.copy(leaseOwner = lease.owner, leaseUntil = lease.until)); return c.generation
    }
    @Transaction open suspend fun releaseLease(scope: String, owner: String, success: Boolean, now: Long, nextEligibleAt: Long) {
        val c = control(scope) ?: return
        if (c.leaseOwner != owner) return   // expired lease holders cannot write (§18)
        putControl(c.copy(leaseOwner = null, leaseUntil = 0, lastSuccessAt = if (success) now else c.lastSuccessAt, nextEligibleAt = nextEligibleAt))
    }
    @Transaction open suspend fun bumpGeneration(scope: String) { val c = control(scope) ?: return; putControl(c.copy(generation = c.generation + 1, leaseOwner = null, leaseUntil = 0)) }
    @Query("SELECT * FROM quota_ledger WHERE budgetScope = :scope AND windowKey = :window AND endpoint = :endpoint") abstract suspend fun quota(scope: String, window: String, endpoint: String): QuotaLedgerEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putQuota(row: QuotaLedgerEntity)
    @Query("DELETE FROM quota_ledger WHERE resetAt < :now") abstract suspend fun pruneQuota(now: Long)
    @Transaction open suspend fun reserveQuota(scope: String, window: String, endpoint: String, cost: Int, limit: Int, resetAt: Long): Boolean {
        val w = quota(scope, window, endpoint)?.domain() ?: QuotaWindow(scope, window, endpoint, limit = limit, resetAt = resetAt)
        val r = w.reserve(cost) ?: return false; putQuota(QuotaLedgerEntity.from(r)); return true
    }
    @Transaction open suspend fun settleQuota(scope: String, window: String, endpoint: String, cost: Int) { quota(scope, window, endpoint)?.let { putQuota(QuotaLedgerEntity.from(it.domain().settle(cost))) } }

    // integration
    @Query("SELECT * FROM integration_config WHERE provider = :provider") abstract suspend fun integration(provider: String): IntegrationConfigEntity?
    @Query("SELECT * FROM integration_config") abstract fun observeIntegrations(): Flow<List<IntegrationConfigEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putIntegration(row: IntegrationConfigEntity)
    @Query("DELETE FROM integration_config WHERE provider = :provider") abstract suspend fun deleteIntegration(provider: String)

    @Query("SELECT COUNT(*) FROM track_experience WHERE accountScope = :scope AND confirmedListenCount > 0") abstract suspend fun confirmedListenedCount(scope: String): Int
}

data class StatusCount(val queueStatus: String, val n: Int)
