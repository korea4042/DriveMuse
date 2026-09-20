package ai.drivemuse.app

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="intelligence_state") data class IntelligenceState(@PrimaryKey val key: String, val json: String, val version: Long, val updatedAt: Long)
/**
 * R02. The unique key used to be (sessionId, generation). `invalidate()` only flips `status`, so
 * the old row stays in the index and the second batch of a session could never be inserted — the
 * constraint failure was swallowed by the append path and the queue simply stopped being filled.
 * `batchSeq` is the batch number inside one generation; `generation` still means "the conditions
 * changed" and is not bumped per batch.
 */
@Entity(tableName="batches", indices=[Index(value=["sessionId","generation","batchSeq"],unique=true)])
data class BatchEntity(@PrimaryKey val id: String, val sessionId: String, val generation: Long, val profileVersion: Long, val contextVersion: Long, val evidenceVersion: Long, val candidateSetId: String, val status: String, val json: String, val createdAt: Long, val batchSeq: Long = 0)
/**
 * v2.3 §7 and §13. The observation columns are additive: rows written before Phase 1 are explicit
 * ratings, which read their score from `explicit` and never touch the listening fields.
 */
@Entity(tableName="outcomes") data class OutcomeEntity(
    @PrimaryKey val attemptId: String, val trackId: String, val sessionId: String, val version: Long,
    val score: Double, val explicit: Boolean, val createdAt: Long,
    // Nullable rather than defaulted: a column default has to match Room's expectation exactly, and
    // "this row predates observation" is what null already means.
    val activeMs: Long? = null, val coveredMs: Long? = null, val ratio: Double? = null,
    val uncertain: Boolean? = null, val endReason: String? = null, val confidence: Double? = null
)
/** One try at playing one recording. Separate from the batch item: a retry is a new attempt. */
@Entity(tableName="playback_attempt", indices=[Index("sessionId"), Index("startedAt")])
data class PlaybackAttemptEntity(@PrimaryKey val attemptId: String, val trackId: String, val sessionId: String, val batchId: String?, val ordinal: Int, val commandId: String?, val startedAt: Long, val confirmedAt: Long?, val endedAt: Long?, val state: String)
/** Raw player callbacks, kept 30 days so a disputed outcome can be recomputed from its evidence. */
@Entity(tableName="playback_event", indices=[Index("attemptId"), Index("observedAt")])
data class PlaybackEventEntity(@PrimaryKey val eventId: String, val attemptId: String, val observedAt: Long, val positionMs: Long, val durationMs: Long, val paused: Boolean, val source: String)
@Dao abstract class IntelligenceDao {
    @Query("SELECT * FROM intelligence_state WHERE `key`=:key") abstract suspend fun state(key: String): IntelligenceState?
    @Query("SELECT * FROM intelligence_state WHERE `key`=:key") abstract fun observeState(key: String): Flow<IntelligenceState?>
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun putState(row: IntelligenceState)
    @Insert abstract suspend fun putBatch(row: BatchEntity)
    /** Next number in this generation. Called inside the same transaction as the insert (R02). */
    @Query("SELECT COALESCE(MAX(batchSeq), -1) + 1 FROM batches WHERE sessionId=:sessionId AND generation=:generation")
    abstract suspend fun nextBatchSeq(sessionId: String, generation: Long): Long
    @Query("SELECT * FROM batches WHERE status='READY' ORDER BY createdAt DESC LIMIT 1") abstract suspend fun ready(): BatchEntity?
    @Query("UPDATE batches SET status='INVALIDATED' WHERE status IN ('READY','PREPARING','PROVISIONAL')") abstract suspend fun invalidate()
    @Query("SELECT * FROM outcomes") abstract suspend fun outcomes(): List<OutcomeEntity>
    @Query("SELECT * FROM outcomes WHERE attemptId=:id") abstract suspend fun outcome(id: String): OutcomeEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun putOutcome(row: OutcomeEntity)
    @Transaction open suspend fun acceptOutcome(row: OutcomeEntity): Boolean {
        if((outcome(row.attemptId)?.version?:-1)>=row.version) return false
        putOutcome(row);invalidate();return true
    }
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun putAttempt(row: PlaybackAttemptEntity)
    @Query("SELECT * FROM playback_attempt WHERE attemptId=:id") abstract suspend fun attempt(id: String): PlaybackAttemptEntity?
    @Insert(onConflict=OnConflictStrategy.IGNORE) abstract suspend fun putEvent(row: PlaybackEventEntity)
    @Query("SELECT * FROM playback_event WHERE attemptId=:id ORDER BY observedAt") abstract suspend fun events(id: String): List<PlaybackEventEntity>
    /**
     * Ending a track records what happened; it does not throw away a queue the user can still see.
     * Reacting to the new evidence is the scheduler's job (§30), not the observer's.
     */
    @Transaction open suspend fun closeAttempt(attempt: PlaybackAttemptEntity, row: OutcomeEntity): Boolean {
        putAttempt(attempt)
        if((outcome(row.attemptId)?.version?:-1)>=row.version) return false
        putOutcome(row);return true
    }
    @Query("DELETE FROM playback_event WHERE observedAt<:cutoff") abstract suspend fun pruneEvents(cutoff: Long)
    @Query("DELETE FROM playback_attempt WHERE startedAt<:cutoff") abstract suspend fun pruneAttempts(cutoff: Long)
    @Query("DELETE FROM playback_event") abstract suspend fun clearEvents()
    @Query("DELETE FROM playback_attempt") abstract suspend fun clearAttempts()
    @Query("DELETE FROM outcomes WHERE createdAt<:cutoff") abstract suspend fun pruneOutcomes(cutoff: Long)
    @Query("DELETE FROM batches WHERE createdAt<:cutoff") abstract suspend fun pruneBatches(cutoff: Long)
    @Query("DELETE FROM outcomes") abstract suspend fun clearOutcomes()
    /** Implicit rows are the derived listening metrics; explicit ratings are the user's own words. */
    @Query("DELETE FROM outcomes WHERE explicit=0") abstract suspend fun clearImplicitOutcomes()
    @Query("SELECT count(*) FROM outcomes WHERE explicit=0") abstract suspend fun implicitOutcomeCount(): Int
    @Query("DELETE FROM intelligence_state WHERE `key`=:key") abstract suspend fun clearStateKey(key: String)
    @Query("DELETE FROM batches") abstract suspend fun clearBatches()
    @Query("DELETE FROM intelligence_state WHERE `key` NOT IN ('survey','survey_profile','ai_quota')") abstract suspend fun clearAnalysis()
    @Query("DELETE FROM intelligence_state WHERE `key` != 'ai_quota'") abstract suspend fun clearState()
}
