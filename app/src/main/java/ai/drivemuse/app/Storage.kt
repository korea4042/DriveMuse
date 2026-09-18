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

    @Insert suspend fun putPlayed(row: PlayedEntity)
    @Query("SELECT videoId FROM played WHERE playedAt >= :since") suspend fun playedSince(since: Long): List<String>
    @Query("DELETE FROM played WHERE playedAt < :cutoff") suspend fun prunePlayed(cutoff: Long)
    @Query("DELETE FROM played") suspend fun clearPlayed()
}

@Database(entities = [RuleEntity::class, HistoryEntity::class, CandidateEntity::class, PlayedEntity::class, IntelligenceState::class, BatchEntity::class, OutcomeEntity::class], version = 3, exportSchema = false)
abstract class DriveDatabase: RoomDatabase() {
    abstract fun dao(): DriveDao
    abstract fun intelligence(): IntelligenceDao
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
        @Volatile private var instance: DriveDatabase? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,DriveDatabase::class.java,"drive.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
