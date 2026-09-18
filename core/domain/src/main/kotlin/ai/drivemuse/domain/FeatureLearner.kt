package ai.drivemuse.domain
import kotlin.math.pow

data class FeatureContribution(val outcome: Outcome, val context: DriveContext, val features: List<VerifiedFeature>)
data class FeatureEvidence(val axis: String,val value: String,val sessionScore: Double,val contextScore: Double,val longTermScore: Double,val supportCount: Int,val distinctSessions: Int)
object FeatureLearner {
    fun summarize(rows: List<FeatureContribution>, session: String, context: DriveContext, now: Long): List<FeatureEvidence> {
        val latest=rows.groupBy { it.outcome.attemptId }.values.map { it.maxBy { row -> row.outcome.version } }.filter { PreferenceLearner.score(it.outcome)!=0.0 }
        val features=latest.flatMap { r -> listOf(("track" to r.outcome.trackId) to r)+r.features.filter { it.valid }.distinctBy { it.axis to it.value }.map { (it.axis to it.value) to r } }.groupBy({it.first},{it.second})
        return features.map { (key,values) ->
            val factor=if(key.first=="track") 1.0 else .25
            val sessions=values.map { it.outcome.sessionId }.distinct().size
            fun aggregate(selected: List<FeatureContribution>, rate: Double, halfLife: Double): Double = selected.groupBy { it.outcome.timestamp/86400000 }.values.sumOf { day -> day.sumOf { r -> PreferenceLearner.score(r.outcome) }.coerceIn(-1.0,1.0)*.5.pow((now-day.maxOf { it.outcome.timestamp }).coerceAtLeast(0)/86400000.0/halfLife) }*rate*factor
            FeatureEvidence(key.first,key.second,aggregate(values.filter { it.outcome.sessionId==session },.2,30.0),aggregate(values.filter { it.context==context },.05,14.0),aggregate(values.filter { it.outcome.explicit!=null || sessions>=3 },.02,30.0),values.size,sessions)
        }
    }
}
