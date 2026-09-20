package ai.drivemuse.app.learning

import ai.drivemuse.app.*
import ai.drivemuse.domain.*
import androidx.room.withTransaction
import org.json.JSONObject
import kotlin.math.pow

/** What the agent screen may claim it learned: confirmed listening only, never collection counts. */
data class ListeningSummary(val validTracks: Int = 0, val sessions: Int = 0, val observedAttempts: Int = 0)

class LearningStore(private val db: DriveDatabase) {
    /**
     * Until Phase 1 this returned a stub with no listening time and UNKNOWN as the reason, so every
     * implicit row scored zero. The row now carries what was observed, and explicit ratings are
     * unaffected because §8 gives them priority over any behavioural signal.
     */
    fun outcome(r: OutcomeEntity)=Outcome(
        r.attemptId,r.trackId,r.sessionId,r.version,
        ListeningTotals(r.activeMs?:0,r.coveredMs?:0,r.ratio,r.uncertain?:false),
        r.endReason?.let { runCatching { EndReason.valueOf(it) }.getOrNull() } ?: EndReason.UNKNOWN,
        r.confidence?:1.0,
        if(r.explicit) if(r.score>0) 1 else -1 else null,
        r.createdAt
    )

    /**
     * Stores one finished attempt and its outcome in a single transaction (§13). The score is
     * computed here rather than trusted from the caller, so the §8 table stays the only policy.
     */
    suspend fun record(attempt: PlaybackAttemptEntity, totals: ListeningTotals, judgement: EndJudgement, endedAt: Long): Double {
        val dao=db.intelligence()
        // FIX-A. Closing the attempt is playback control state and stays. Turning what was observed
        // into a listening ratio and a preference score is the derived metric the policy is about,
        // so it is not computed and not stored.
        if(!Policy.SPOTIFY_BEHAVIOR_LEARNING_ALLOWED) {
            dao.putAttempt(attempt.copy(endedAt=endedAt,state="TERMINAL"))
            return 0.0
        }
        val version=(dao.outcome(attempt.attemptId)?.version?:0)+1
        val outcome=Outcome(attempt.attemptId,attempt.trackId,attempt.sessionId,version,totals,judgement.reason,judgement.confidence,null,endedAt)
        val score=PreferenceLearner.score(outcome)
        dao.closeAttempt(
            attempt.copy(endedAt=endedAt,state="TERMINAL"),
            OutcomeEntity(attempt.attemptId,attempt.trackId,attempt.sessionId,version,score,false,endedAt,
                totals.activeMs,totals.coveredMs,totals.ratio,totals.uncertain,judgement.reason.name,judgement.confidence)
        )
        return score
    }

    /** Only attempts whose cause and coverage were good enough to score count as evidence. */
    suspend fun summary(): ListeningSummary {
        // Nothing is being learned, so there is nothing to count. The screen says so in words.
        if(!Policy.SPOTIFY_BEHAVIOR_LEARNING_ALLOWED) return ListeningSummary()
        val rows=db.intelligence().outcomes().filter { !it.explicit }
        val scored=rows.filter { PreferenceLearner.score(outcome(it))!=0.0 }
        return ListeningSummary(scored.map { it.trackId }.distinct().size, scored.map { it.sessionId }.distinct().size, rows.size)
    }

    private fun archive(row: IntelligenceState?, now: Long): Map<String,Double> {
        if(row==null) return emptyMap();val j=JSONObject(row.json);val decay=.5.pow((now-row.updatedAt).coerceAtLeast(0)/86400000.0/30)
        return j.keys().asSequence().associateWith { j.getDouble(it)*decay }
    }
    suspend fun prune(now: Long)=db.withTransaction {
        // Only implicit rows expire. pruneOutcomes would take explicit ratings with them, which
        // made "explicit ratings survive" true only until the next 30 day sweep.
        val dao=db.intelligence();val expired=dao.outcomes().filter { it.createdAt<now-2592000000L && !it.explicit }
        // Expiry used to roll the dying rows into a long-term archive. That archive is exactly the
        // derived profile, so while learning is blocked the rows simply expire.
        if(expired.isNotEmpty() && Policy.SPOTIFY_BEHAVIOR_LEARNING_ALLOWED) {
            val values=archive(dao.state("learned_archive"),now).toMutableMap()
            PreferenceLearner.trackScores(expired.map(::outcome),now).forEach { (id,value) -> values[id]=((values[id]?:0.0)+value).coerceIn(-1.0,1.0) }
            dao.putState(IntelligenceState("learned_archive",JSONObject(values as Map<*,*>).toString(),now,now))
            dao.pruneImplicitOutcomes(now-2592000000L)
        } else if(expired.isNotEmpty()) dao.pruneImplicitOutcomes(now-2592000000L)
        dao.pruneBatches(now-2592000000L)
        dao.pruneEvents(now-2592000000L)
        dao.pruneAttempts(now-2592000000L)
    }
    /**
     * FIX-A. Rows written before the boundary existed are derived metrics too, so they are removed
     * rather than left in place unread. Explicit ratings are the user's own statements and stay.
     * Returns how many implicit rows were dropped, so the removal is reportable rather than silent.
     */
    suspend fun purgeDerivedLearning(): Int = db.withTransaction {
        val dao=db.intelligence()
        val removed=dao.implicitOutcomeCount()
        if(removed>0) dao.clearImplicitOutcomes()
        dao.clearStateKey("learned_archive")
        removed
    }

    suspend fun scores(session: String, now: Long): Map<String,Double> {
        if(!Policy.SPOTIFY_BEHAVIOR_LEARNING_ALLOWED) return emptyMap()
        val rows=db.intelligence().outcomes().map(::outcome)
        val long=archive(db.intelligence().state("learned_archive"),now).toMutableMap()
        PreferenceLearner.trackScores(rows,now).forEach { (id,value) -> long[id]=(long[id]?:0.0)+value }
        PreferenceLearner.trackScores(rows,now,session).forEach { (id,value) -> long[id]=(long[id]?:0.0)+value }
        return long.mapValues { it.value.coerceIn(-1.0,1.0) }
    }
}
