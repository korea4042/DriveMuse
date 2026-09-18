package ai.drivemuse.app

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="intelligence_state") data class IntelligenceState(@PrimaryKey val key: String, val json: String, val version: Long, val updatedAt: Long)
@Entity(tableName="batches", indices=[Index(value=["sessionId","generation"],unique=true)])
data class BatchEntity(@PrimaryKey val id: String, val sessionId: String, val generation: Long, val profileVersion: Long, val contextVersion: Long, val evidenceVersion: Long, val candidateSetId: String, val status: String, val json: String, val createdAt: Long)
@Entity(tableName="outcomes") data class OutcomeEntity(@PrimaryKey val attemptId: String, val trackId: String, val sessionId: String, val version: Long, val score: Double, val explicit: Boolean, val createdAt: Long)
@Dao abstract class IntelligenceDao {
    @Query("SELECT * FROM intelligence_state WHERE `key`=:key") abstract suspend fun state(key: String): IntelligenceState?
    @Query("SELECT * FROM intelligence_state WHERE `key`=:key") abstract fun observeState(key: String): Flow<IntelligenceState?>
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun putState(row: IntelligenceState)
    @Insert abstract suspend fun putBatch(row: BatchEntity)
    @Query("SELECT * FROM batches WHERE status='READY' ORDER BY createdAt DESC LIMIT 1") abstract suspend fun ready(): BatchEntity?
    @Query("UPDATE batches SET status='INVALIDATED' WHERE status IN ('READY','PREPARING','PROVISIONAL')") abstract suspend fun invalidate()
    @Query("SELECT * FROM outcomes") abstract suspend fun outcomes(): List<OutcomeEntity>
    @Query("SELECT * FROM outcomes WHERE attemptId=:id") abstract suspend fun outcome(id: String): OutcomeEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun putOutcome(row: OutcomeEntity)
    @Transaction open suspend fun acceptOutcome(row: OutcomeEntity): Boolean {
        if((outcome(row.attemptId)?.version?:-1)>=row.version) return false
        putOutcome(row);invalidate();return true
    }
    @Query("DELETE FROM outcomes WHERE createdAt<:cutoff") abstract suspend fun pruneOutcomes(cutoff: Long)
    @Query("DELETE FROM batches WHERE createdAt<:cutoff") abstract suspend fun pruneBatches(cutoff: Long)
    @Query("DELETE FROM outcomes") abstract suspend fun clearOutcomes()
    @Query("DELETE FROM batches") abstract suspend fun clearBatches()
    @Query("DELETE FROM intelligence_state WHERE `key` NOT IN ('survey','survey_profile','ai_quota')") abstract suspend fun clearAnalysis()
    @Query("DELETE FROM intelligence_state WHERE `key` != 'ai_quota'") abstract suspend fun clearState()
}
