package ai.drivemuse.domain

import kotlin.math.abs
import kotlin.math.pow

enum class MediaState { PLAYING, PAUSED, BUFFERING, STOPPED, UNKNOWN }
enum class EndReason { USER_NEXT, NATURAL_END, USER_REPEAT, INTERRUPTED, UNKNOWN }
data class Observation(val id: String, val attemptId: String, val monotonicMs: Long, val positionMs: Long, val state: MediaState, val speed: Double = 1.0, val confidence: Double = 1.0)
data class ListeningTotals(val activeMs: Long, val coveredMs: Long, val ratio: Double?, val uncertain: Boolean)
object ListeningAggregator {
    fun aggregate(events: List<Observation>, durationMs: Long?): ListeningTotals {
        require(events.map { it.attemptId }.distinct().size<=1)
        val intervals=mutableListOf<Pair<Long,Long>>(); var active=0L; var uncertain=false
        events.distinctBy { it.id }.sortedBy { it.monotonicMs }.zipWithNext().forEach { (a,b) ->
            val dt=b.monotonicMs-a.monotonicMs; val dp=b.positionMs-a.positionMs
            if(dt<=0 || dt>5000 || a.confidence !in .8..1.0 || b.confidence !in .8..1.0 || !a.confidence.isFinite() || !b.confidence.isFinite()) { uncertain=true; return@forEach }
            if(a.state!=MediaState.PLAYING || b.state!=MediaState.PLAYING) return@forEach
            if(!a.speed.isFinite() || a.speed<=0 || a.speed!=b.speed || a.positionMs<0 || dp<0 || abs(dp-dt*a.speed)>500) { uncertain=true; return@forEach }
            active+=dt; intervals+=a.positionMs to b.positionMs
        }
        var covered=0L; var start=-1L; var end=-1L
        intervals.sortedBy { it.first }.forEach { (a,b) -> if(a>end) { if(start>=0) covered+=end-start; start=a;end=b } else end=maxOf(end,b) }
        if(start>=0) covered+=end-start
        return ListeningTotals(active,covered, durationMs?.takeIf { it>0 }?.let { (covered.toDouble()/it).coerceIn(0.0,1.0) },uncertain)
    }
}
data class Outcome(val attemptId: String, val trackId: String, val sessionId: String, val version: Long, val totals: ListeningTotals, val endReason: EndReason, val confidence: Double, val explicit: Int? = null, val timestamp: Long)

/** What ended one attempt, as the app saw it. A track change on its own says nothing about why. */
enum class EndTrigger { TRACK_CHANGED, DISCONNECTED, SESSION_END }
data class AttemptClose(val endedAt: Long, val lastPositionMs: Long, val durationMs: Long?, val trigger: EndTrigger, val appCommandAt: Long? = null)
data class EndJudgement(val reason: EndReason, val confidence: Double)
/**
 * §7: the cause of a skip is not always observable. Only a track the player carried to its end, or
 * one the app itself skipped, gets a confident reason. Anything else stays below the .8 bar §8 sets
 * for scoring, so a steering-wheel button can never be read as dislike.
 */
object EndReasonResolver {
    /** Position callbacks rarely land on the final millisecond; this far in counts as finished. */
    const val COMPLETION = .97
    /** How long after the app's own skip a track change is still attributed to it. */
    const val COMMAND_WINDOW_MS = 10_000L
    const val UNVERIFIED_CONFIDENCE = .6
    fun resolve(close: AttemptClose): EndJudgement {
        val commanded = close.appCommandAt?.let { close.endedAt - it in 0..COMMAND_WINDOW_MS } == true
        val finished = close.durationMs?.takeIf { it > 0 }?.let { close.lastPositionMs >= it * COMPLETION } == true
        return when {
            close.trigger == EndTrigger.DISCONNECTED -> EndJudgement(EndReason.INTERRUPTED, 1.0)
            close.trigger == EndTrigger.SESSION_END -> EndJudgement(EndReason.UNKNOWN, UNVERIFIED_CONFIDENCE)
            finished -> EndJudgement(EndReason.NATURAL_END, 1.0)
            commanded -> EndJudgement(EndReason.USER_NEXT, 1.0)
            else -> EndJudgement(EndReason.USER_NEXT, UNVERIFIED_CONFIDENCE)
        }
    }
}
object PreferenceLearner {
    fun score(o: Outcome): Double {
        o.explicit?.let { require(it==1 || it == -1); return it.toDouble() }
        if(!o.confidence.isFinite() || o.confidence !in .8..1.0 || o.totals.uncertain) return 0.0
        if(o.endReason==EndReason.USER_REPEAT) return .7
        if(o.endReason==EndReason.UNKNOWN || o.endReason==EndReason.INTERRUPTED) return 0.0
        if(o.endReason==EndReason.USER_NEXT && o.totals.activeMs<15000) return -.4
        if(o.endReason==EndReason.USER_NEXT && o.totals.activeMs<30000) return -.2
        val ratio=o.totals.ratio?:return 0.0
        if(o.endReason==EndReason.NATURAL_END && ratio>=.9) return .25
        return if(ratio>=.7) .15 else 0.0
    }
    fun latest(outcomes: List<Outcome>) = outcomes.groupBy { it.attemptId }.values.map { it.maxBy { row -> row.version } }
    fun trackScores(outcomes: List<Outcome>, now: Long, sessionId: String? = null): Map<String,Double> = latest(outcomes).filter { sessionId==null || it.sessionId==sessionId }.groupBy { it.trackId }.mapValues { (_,rows) ->
        val implicitAllowed=sessionId!=null || rows.filter { score(it)!=0.0 }.map { it.sessionId }.distinct().size>=3
        val value=rows.filter { it.explicit!=null || implicitAllowed }.groupBy { it.timestamp/86400000 }.values.sumOf { day ->
            day.sumOf { score(it) }.coerceIn(-1.0,1.0) * .5.pow((now-day.maxOf { it.timestamp }).coerceAtLeast(0)/86400000.0/30)
        }
        value*(if(sessionId==null) .02 else .2)
    }
}
enum class PlaybackLevel { OPEN_ONLY, OBSERVE, CONTROL }
data class QueueVersion(val sessionId: String, val generation: Long, val profileVersion: Long, val contextVersion: Long, val evidenceVersion: Long, val candidateSetId: String)
object ProposalGate {
    fun valid(expected: QueueVersion, actual: QueueVersion, ids: List<String>, candidates: List<Track>, constraints: Constraints): Boolean = expected==actual && ids.size in 1..Policy.BATCH_SIZE && ids.distinct().size==ids.size && ids.all { id -> candidates.any { it.id==id && Policy.validTrackId(id) && constraints.allows(it) } }
}
enum class BatchStatus { PREPARING, PROVISIONAL, READY, ACTIVE, COMPLETED, SUSPENDED, INVALIDATED }
data class Batch(val version: QueueVersion, val ids: List<String>, val status: BatchStatus, val completed: Set<String> = emptySet())
class BatchMachine {
    var current: Batch? = null; private set
    var next: Batch? = null; private set
    private val commands=mutableSetOf<String>()
    fun prepare(version: QueueVersion) { next=Batch(version,emptyList(),BatchStatus.PREPARING) }
    fun provisional(ids: List<String>) { next=next?.copy(ids=ids,status=BatchStatus.PROVISIONAL) }
    fun ready(version: QueueVersion, ids: List<String>): Boolean {
        val n=next?:return false
        if(n.version!=version || n.status !in setOf(BatchStatus.PREPARING,BatchStatus.PROVISIONAL) || ids.size !in 1..Policy.BATCH_SIZE || ids.distinct().size!=ids.size) return false
        next=n.copy(ids=ids,status=BatchStatus.READY);return true
    }
    fun dispatch(level: PlaybackLevel, commandId: String, enabled: Boolean, suspended: Boolean): String? {
        if(level!=PlaybackLevel.CONTROL || !enabled || suspended || current?.status==BatchStatus.ACTIVE || next?.status!=BatchStatus.READY || commandId in commands) return null
        commands+=commandId;return next?.ids?.firstOrNull()
    }
    fun start(version: QueueVersion, observedId: String, level: PlaybackLevel): Boolean {
        val n=next?:return false
        if(level==PlaybackLevel.OPEN_ONLY || n.status!=BatchStatus.READY || version!=n.version || observedId!=n.ids.firstOrNull() || current?.status==BatchStatus.ACTIVE) return false
        current=n.copy(status=BatchStatus.ACTIVE);next=null;return true
    }
    fun finish(id: String) { current=current?.let { b -> if(id !in b.ids || b.status!=BatchStatus.ACTIVE) b else { val done=b.completed+id;b.copy(completed=done,status=if(done.size==b.ids.size) BatchStatus.COMPLETED else BatchStatus.ACTIVE) } } }
    fun suspend() { next=null }
    fun invalidate() { next=null }
}
/**
 * §7: source, observation time, *lookup* time, region and freshness are five separate facts and the
 * screen has to be able to say each of them. `source` no longer defaults to a provider name — the
 * old "KMA" default outlived the provider it described.
 */
data class WeatherFact(val region: String, val temperature: Double, val precipitation: Int, val observedAt: Long, val source: String = "UNKNOWN", val fetchedAt: Long = observedAt) {
    fun usable(regionNow: String, now: Long) = region==regionNow && now-observedAt in 0..3600000 && temperature.isFinite()
    fun stale(now: Long)=now-observedAt>1800000
}
