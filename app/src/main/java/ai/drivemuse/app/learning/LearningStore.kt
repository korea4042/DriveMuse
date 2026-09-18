package ai.drivemuse.app.learning

import ai.drivemuse.app.*
import ai.drivemuse.domain.*
import androidx.room.withTransaction
import org.json.JSONObject
import kotlin.math.pow

class LearningStore(private val db: DriveDatabase) {
    fun outcome(r: OutcomeEntity)=Outcome(r.attemptId,r.trackId,r.sessionId,r.version,ListeningTotals(0,0,null,false),EndReason.UNKNOWN,1.0,if(r.explicit) if(r.score>0) 1 else -1 else null,r.createdAt)
    private fun archive(row: IntelligenceState?, now: Long): Map<String,Double> {
        if(row==null) return emptyMap();val j=JSONObject(row.json);val decay=.5.pow((now-row.updatedAt).coerceAtLeast(0)/86400000.0/30)
        return j.keys().asSequence().associateWith { j.getDouble(it)*decay }
    }
    suspend fun prune(now: Long)=db.withTransaction {
        val dao=db.intelligence();val expired=dao.outcomes().filter { it.createdAt<now-2592000000L }
        if(expired.isNotEmpty()) {
            val values=archive(dao.state("learned_archive"),now).toMutableMap()
            PreferenceLearner.trackScores(expired.map(::outcome),now).forEach { (id,value) -> values[id]=((values[id]?:0.0)+value).coerceIn(-1.0,1.0) }
            dao.putState(IntelligenceState("learned_archive",JSONObject(values as Map<*,*>).toString(),now,now))
            dao.pruneOutcomes(now-2592000000L)
        }
        dao.pruneBatches(now-2592000000L)
    }
    suspend fun scores(session: String, now: Long): Map<String,Double> {
        val rows=db.intelligence().outcomes().map(::outcome)
        val long=archive(db.intelligence().state("learned_archive"),now).toMutableMap()
        PreferenceLearner.trackScores(rows,now).forEach { (id,value) -> long[id]=(long[id]?:0.0)+value }
        PreferenceLearner.trackScores(rows,now,session).forEach { (id,value) -> long[id]=(long[id]?:0.0)+value }
        return long.mapValues { it.value.coerceIn(-1.0,1.0) }
    }
}
