package ai.drivemuse.app

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ai.drivemuse.domain.*
import ai.drivemuse.app.catalog.*

val Context.driveStore by preferencesDataStore("drivemuse")

/**
 * The gateway endpoint and its bearer token are gone with v1.2 §6.1. What replaces them is
 * not a secret: an account-linked flag, a daily quota counter, and a sync timestamp.
 */
data class Settings(
    val auto: Boolean = false,
    val ratio: Float = .35f,
    val onboarded: Boolean = false,
    val vehicleId: String = "",
    val vehicleName: String = "",
    val connected: Boolean = false,
    val suspendedUntil: Long = 0,
    val accountLinked: Boolean = false,
    val tasteSyncedAt: Long = 0,
    val searchCalls: Int = 0,
    val quotaDay: Long = 0,
    val regionCode: String = "KR"
)
class Preferences(private val context: Context) {
    val flow = context.driveStore.data.map { p ->
        Settings(
            p[booleanPreferencesKey("auto")] ?: false,
            p[floatPreferencesKey("ratio")] ?: .35f,
            p[booleanPreferencesKey("onboarded")] ?: false,
            p[stringPreferencesKey("vehicleId")] ?: "",
            p[stringPreferencesKey("vehicleName")] ?: "",
            p[booleanPreferencesKey("connected")] ?: false,
            p[longPreferencesKey("suspended")] ?: 0,
            p[booleanPreferencesKey("accountLinked")] ?: false,
            p[longPreferencesKey("tasteSyncedAt")] ?: 0,
            p[intPreferencesKey("searchCalls")] ?: 0,
            p[longPreferencesKey("quotaDay")] ?: 0,
            p[stringPreferencesKey("regionCode")] ?: "KR"
        )
    }
    suspend fun flag(key: String, value: Boolean) { context.driveStore.edit { it[booleanPreferencesKey(key)] = value } }
    suspend fun string(key: String, value: String) { context.driveStore.edit { it[stringPreferencesKey(key)] = value } }
    suspend fun long(key: String, value: Long) { context.driveStore.edit { it[longPreferencesKey(key)] = value } }
    suspend fun ratio(value: Float) { context.driveStore.edit { it[floatPreferencesKey("ratio")] = value.coerceIn(0f,1f) } }
    suspend fun suspendUntil(value: Long) { context.driveStore.edit { it[longPreferencesKey("suspended")] = value } }
    /** Counter resets on the calendar day boundary, matching how the API resets quota. */
    suspend fun countSearch(today: Long) { context.driveStore.edit { p ->
        val day = p[longPreferencesKey("quotaDay")] ?: 0
        p[longPreferencesKey("quotaDay")] = today
        p[intPreferencesKey("searchCalls")] = if (day == today) (p[intPreferencesKey("searchCalls")] ?: 0) + 1 else 1
    } }
    suspend fun reserveSearch(today: Long): Boolean {
        var accepted=false
        context.driveStore.edit { p ->
            val day=p[longPreferencesKey("quotaDay")]?:0
            val calls=if(day==today) p[intPreferencesKey("searchCalls")]?:0 else 0
            if(Quota.canSearch(calls)) { p[longPreferencesKey("quotaDay")]=today;p[intPreferencesKey("searchCalls")]=calls+1;accepted=true }
        }
        return accepted
    }
    suspend fun clear() { context.driveStore.edit { it.clear() } }
}

@Entity(tableName = "rules") data class RuleEntity(@PrimaryKey val id: String, val scope: String?, val text: String, val discovery: Double?, val energy: Double?, val enabled: Boolean, val createdAt: Long) {
    fun domain() = MusicRule(id, scope?.let { DriveContext.valueOf(it) }, text, discovery, energy, enabled, createdAt = createdAt)
    companion object { fun from(r: MusicRule) = RuleEntity(r.id,r.scope?.name,r.text,r.discovery,r.energyCeiling,r.enabled,r.createdAt) }
}
@Entity(tableName = "history") data class HistoryEntity(@PrimaryKey val id: String, val context: String, val title: String, val count: Int, val createdAt: Long, val feedback: String = "", val demo: Boolean = false)

/** §6.6 candidate pool. Cached so a drive session needs no network call at all. */
@Entity(tableName = "candidates") data class CandidateEntity(
    @PrimaryKey val videoId: String, val title: String, val artist: String,
    val durationSec: Int, val topics: String, val familiar: Boolean,
    val affinity: Double, val freshness: Double, val energy: Double?,
    val source: String, val fetchedAt: Long, val audioLanguage: String? = null
)
/** Handoff exposure for repetition fatigue only. Never evidence of listening. */
@Entity(tableName = "played") data class PlayedEntity(@PrimaryKey(autoGenerate = true) val rowId: Long = 0, val videoId: String, val playedAt: Long)

@Dao interface DriveDao {
    @Query("SELECT * FROM rules ORDER BY createdAt DESC") fun rules(): Flow<List<RuleEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRule(rule: RuleEntity)
    @Query("DELETE FROM rules WHERE id = :id") suspend fun deleteRule(id: String)
    @Query("SELECT * FROM history ORDER BY createdAt DESC") fun history(): Flow<List<HistoryEntity>>
    @Insert suspend fun putHistory(item: HistoryEntity)
    @Query("UPDATE history SET feedback = :feedback WHERE id = :id") suspend fun feedback(id: String, feedback: String)
    @Query("DELETE FROM history WHERE createdAt < :cutoff") suspend fun prune(cutoff: Long)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("DELETE FROM rules") suspend fun clearRules()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCandidates(rows: List<CandidateEntity>)
    @Query("SELECT * FROM candidates WHERE fetchedAt >= :cutoff") suspend fun candidates(cutoff: Long): List<CandidateEntity>
    @Query("SELECT COUNT(*) FROM candidates WHERE fetchedAt >= :cutoff") suspend fun candidateCount(cutoff: Long): Int
    @Query("DELETE FROM candidates WHERE fetchedAt < :cutoff") suspend fun pruneCandidates(cutoff: Long)
    @Query("DELETE FROM candidates") suspend fun clearCandidates()
    @Query("DELETE FROM candidates WHERE videoId IN (:ids)") suspend fun deleteCandidates(ids: List<String>)

    @Insert suspend fun putPlayed(row: PlayedEntity)
    @Query("SELECT videoId FROM played WHERE playedAt >= :since") suspend fun playedSince(since: Long): List<String>
    @Query("DELETE FROM played WHERE playedAt < :cutoff") suspend fun prunePlayed(cutoff: Long)
    @Query("DELETE FROM played") suspend fun clearPlayed()
}

@Database(entities = [RuleEntity::class, HistoryEntity::class, CandidateEntity::class, PlayedEntity::class, IntelligenceState::class, BatchEntity::class, OutcomeEntity::class,
    TrackEntity::class, TrackIdentifierEntity::class, PlayableRefEntity::class, MetadataAssertionEntity::class, DiscoveryItemEntity::class, EnrichmentJobEntity::class, ValidationDecisionEntity::class,
    IdentityAliasEntity::class, TrackExperienceEntity::class, UserTrackContextEntity::class, DiscoverySeedEntity::class, CollectionRunEntity::class, CollectionControlEntity::class, QuotaLedgerEntity::class, IntegrationConfigEntity::class],
    version = 4, exportSchema = true)
abstract class DriveDatabase: RoomDatabase() {
    abstract fun dao(): DriveDao
    abstract fun intelligence(): IntelligenceDao
    abstract fun catalog(): CatalogDao
    companion object {
        /** Additive only: rules and history from 0.1.0 installs survive the upgrade. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `candidates` (`videoId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSec` INTEGER NOT NULL, `topics` TEXT NOT NULL, `familiar` INTEGER NOT NULL, `affinity` REAL NOT NULL, `freshness` REAL NOT NULL, `energy` REAL, `source` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `played` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `videoId` TEXT NOT NULL, `playedAt` INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2,3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `intelligence_state` (`key` TEXT NOT NULL, `json` TEXT NOT NULL, `version` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `batches` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `generation` INTEGER NOT NULL, `profileVersion` INTEGER NOT NULL, `contextVersion` INTEGER NOT NULL, `evidenceVersion` INTEGER NOT NULL, `candidateSetId` TEXT NOT NULL, `status` TEXT NOT NULL, `json` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_batches_sessionId_generation` ON `batches` (`sessionId`, `generation`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `outcomes` (`attemptId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `version` INTEGER NOT NULL, `score` REAL NOT NULL, `explicit` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`attemptId`))")
                db.execSQL("ALTER TABLE `candidates` ADD COLUMN `audioLanguage` TEXT")
            }
        }
        /**
         * v2.3 §18/§27: Track/Video normalization, discovery queue, experience, collection control,
         * quota ledger, integration config. Additive only. Existing `candidates` rows are copied into
         * playable_ref as UNMATCHED provider resources (staging), never straight into VALIDATED tracks;
         * `played` stays a handoff-exposure log and is not turned into listening evidence.
         */
        private val MIGRATION_3_4 = object : Migration(3,4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `track` (`trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `primaryArtist` TEXT NOT NULL, `artistCreditsJson` TEXT NOT NULL, `durationMs` INTEGER, `releaseDate` TEXT, `releasePrecision` TEXT, `versionType` TEXT NOT NULL, `metadataStatus` TEXT NOT NULL, `identityVersion` INTEGER NOT NULL, `metadataVersion` INTEGER NOT NULL, `workGroupId` TEXT, `eligible` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_metadataStatus` ON `track` (`metadataStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_primaryArtist` ON `track` (`primaryArtist`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `track_identifier` (`trackId` TEXT NOT NULL, `type` TEXT NOT NULL, `value` TEXT NOT NULL, `source` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`, `type`, `value`, `source`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_identifier_type_value` ON `track_identifier` (`type`, `value`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playable_ref` (`provider` TEXT NOT NULL, `resourceId` TEXT NOT NULL, `trackId` TEXT, `kind` TEXT NOT NULL, `versionType` TEXT NOT NULL, `matchStatus` TEXT NOT NULL, `matchEvidenceJson` TEXT NOT NULL, `title` TEXT NOT NULL, `channel` TEXT NOT NULL, `durationMs` INTEGER, `fetchedAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `availability` TEXT NOT NULL, PRIMARY KEY(`provider`, `resourceId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playable_ref_trackId` ON `playable_ref` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playable_ref_expiresAt` ON `playable_ref` (`expiresAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playable_ref_matchStatus` ON `playable_ref` (`matchStatus`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `metadata_assertion` (`assertionId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `field` TEXT NOT NULL, `value` TEXT NOT NULL, `basis` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceRecordId` TEXT, `confidence` REAL NOT NULL, `fetchedAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `licenseRef` TEXT, `evidenceIdsJson` TEXT NOT NULL, `modelId` TEXT, `promptVersion` TEXT, PRIMARY KEY(`assertionId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_assertion_trackId_field` ON `metadata_assertion` (`trackId`, `field`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_assertion_expiresAt` ON `metadata_assertion` (`expiresAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `discovery_item` (`discoveryItemId` TEXT NOT NULL, `scope` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `rawRef` TEXT NOT NULL, `provider` TEXT NOT NULL, `proposedTrackId` TEXT, `queueStatus` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `retryAt` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `discoveredAt` INTEGER NOT NULL, `lastError` TEXT, `budgetBand` TEXT NOT NULL, PRIMARY KEY(`discoveryItemId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_discovery_item_scope_queueStatus` ON `discovery_item` (`scope`, `queueStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_discovery_item_retryAt` ON `discovery_item` (`retryAt`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_discovery_item_scope_sourceKey_rawRef` ON `discovery_item` (`scope`, `sourceKey`, `rawRef`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `enrichment_job` (`jobId` TEXT NOT NULL, `scope` TEXT NOT NULL, `itemId` TEXT NOT NULL, `provider` TEXT NOT NULL, `metadataVersion` INTEGER NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `leaseOwner` TEXT, `leaseUntil` INTEGER NOT NULL, `configVersion` INTEGER NOT NULL, PRIMARY KEY(`jobId`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_enrichment_job_scope_itemId_provider_metadataVersion` ON `enrichment_job` (`scope`, `itemId`, `provider`, `metadataVersion`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_enrichment_job_state` ON `enrichment_job` (`state`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `validation_decision` (`decisionId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `identityVersion` INTEGER NOT NULL, `rulesetVersion` TEXT NOT NULL, `inputHash` TEXT NOT NULL, `matchedEvidenceIdsJson` TEXT NOT NULL, `decision` TEXT NOT NULL, `reasonsJson` TEXT NOT NULL, `decidedAt` INTEGER NOT NULL, PRIMARY KEY(`decisionId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_validation_decision_trackId_identityVersion` ON `validation_decision` (`trackId`, `identityVersion`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `identity_alias` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `oldTrackId` TEXT NOT NULL, `canonicalTrackId` TEXT NOT NULL, `decisionId` TEXT NOT NULL, `effectiveAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_identity_alias_oldTrackId` ON `identity_alias` (`oldTrackId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `track_experience` (`accountScope` TEXT NOT NULL, `trackId` TEXT NOT NULL, `confirmedListenCount` INTEGER NOT NULL, `lastConfirmedListenAt` INTEGER, `explicitPreference` INTEGER, `surveySeed` INTEGER NOT NULL, `exposureCount` INTEGER NOT NULL, `lastExposureAt` INTEGER, `matchAmbiguous` INTEGER NOT NULL, `historyCoverageSince` INTEGER, PRIMARY KEY(`accountScope`, `trackId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_experience_accountScope_lastConfirmedListenAt` ON `track_experience` (`accountScope`, `lastConfirmedListenAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `user_track_context` (`accountScope` TEXT NOT NULL, `trackId` TEXT NOT NULL, `contextType` TEXT NOT NULL, `validAttempts` INTEGER NOT NULL, `positiveWeight` REAL NOT NULL, `negativeWeight` REAL NOT NULL, `lastUpdated` INTEGER NOT NULL, PRIMARY KEY(`accountScope`, `trackId`, `contextType`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `discovery_seed` (`accountScope` TEXT NOT NULL, `seedId` TEXT NOT NULL, `kind` TEXT NOT NULL, `value` TEXT NOT NULL, `profileVersion` INTEGER NOT NULL, `cursor` TEXT, `lastAttemptAt` INTEGER NOT NULL, `nextEligibleAt` INTEGER NOT NULL, `evidenceIdsJson` TEXT NOT NULL, PRIMARY KEY(`accountScope`, `seedId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `collection_run` (`runId` TEXT NOT NULL, `scope` TEXT NOT NULL, `generation` INTEGER NOT NULL, `profileVersion` INTEGER NOT NULL, `configVersion` INTEGER NOT NULL, `status` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `inserted` INTEGER NOT NULL, `updated` INTEGER NOT NULL, `rejected` INTEGER NOT NULL, `requests` INTEGER NOT NULL, `errorCode` TEXT, `reasonsJson` TEXT NOT NULL, PRIMARY KEY(`runId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_run_scope_status` ON `collection_run` (`scope`, `status`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `collection_control` (`scope` TEXT NOT NULL, `generation` INTEGER NOT NULL, `leaseOwner` TEXT, `leaseUntil` INTEGER NOT NULL, `lastSuccessAt` INTEGER, `nextEligibleAt` INTEGER NOT NULL, `autoEnabled` INTEGER NOT NULL, `unmeteredOnly` INTEGER NOT NULL, PRIMARY KEY(`scope`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `quota_ledger` (`budgetScope` TEXT NOT NULL, `windowKey` TEXT NOT NULL, `endpoint` TEXT NOT NULL, `reserved` INTEGER NOT NULL, `consumed` INTEGER NOT NULL, `limit` INTEGER NOT NULL, `resetAt` INTEGER NOT NULL, PRIMARY KEY(`budgetScope`, `windowKey`, `endpoint`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `integration_config` (`provider` TEXT NOT NULL, `authMode` TEXT NOT NULL, `clientId` TEXT, `endpointId` TEXT, `modelId` TEXT, `credentialRef` TEXT, `configVersion` INTEGER NOT NULL, `status` TEXT NOT NULL, `error` TEXT NOT NULL, `lastValidatedAt` INTEGER, `presentKeysJson` TEXT NOT NULL, PRIMARY KEY(`provider`))")
                // Staging copy of the v2 video cache: UNMATCHED refs, 30-day expiry, no Track linkage yet (§27).
                db.execSQL("INSERT OR IGNORE INTO `playable_ref` (`provider`,`resourceId`,`trackId`,`kind`,`versionType`,`matchStatus`,`matchEvidenceJson`,`title`,`channel`,`durationMs`,`fetchedAt`,`expiresAt`,`availability`) SELECT 'youtube', `videoId`, NULL, 'UNKNOWN', 'UNKNOWN', 'UNMATCHED', '[]', `title`, `artist`, `durationSec`*1000, `fetchedAt`, `fetchedAt`+2592000000, 'UNKNOWN' FROM `candidates`")
                // Discovery items for staged refs so the worker resolves them; sourceKey records the legacy origin.
                db.execSQL("INSERT OR IGNORE INTO `discovery_item` (`discoveryItemId`,`scope`,`sourceKey`,`rawRef`,`provider`,`proposedTrackId`,`queueStatus`,`attempts`,`retryAt`,`generation`,`discoveredAt`,`lastError`,`budgetBand`) SELECT 'legacy:'||`videoId`, 'default', 'legacy:'||`source`, `videoId`, 'youtube', NULL, 'PENDING', 0, 0, 1, `fetchedAt`, NULL, 'ADJACENT' FROM `candidates`")
                db.execSQL("INSERT OR IGNORE INTO `collection_control` (`scope`,`generation`,`leaseOwner`,`leaseUntil`,`lastSuccessAt`,`nextEligibleAt`,`autoEnabled`,`unmeteredOnly`) VALUES ('default', 1, NULL, 0, NULL, 0, 1, 1)")
            }
        }
        @Volatile private var instance: DriveDatabase? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,DriveDatabase::class.java,"drive.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
