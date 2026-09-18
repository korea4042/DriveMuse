package ai.drivemuse.app.gemini

import ai.drivemuse.domain.*
import ai.drivemuse.app.onboarding.*
import com.google.firebase.ai.type.Schema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONArray

data class Selection(val tracks: List<Track>, val label: String)
class RecommendationEngine(private val gateway: RoleGateway) {
    private val strings=Schema.array(Schema.string())
    private var contextCache: Pair<String,JSONObject>?=null
    fun clearCache() { contextCache=null }
    suspend fun analyzeSurvey(d: SurveyDraft, store: SurveyStore): JSONObject {
        val p=Survey.map(d.answers)
        val expected=p.preferences.map { "${it.questionId}:${it.optionId}:${it.scope.name}" }.sorted()
        val input=store.encode(d).put("allowedClaimIds",JSONArray(expected)).put("exclusions",JSONArray(p.exclusions.sorted())).put("discovery",p.discovery)
        return gateway.call(Role.SURVEY,input,Schema.obj(mapOf("surveyRevision" to Schema.integer(),"claimIds" to strings,"exclusions" to strings,"unknowns" to strings,"discovery" to Schema.double(),"summary" to Schema.string()))) { j ->
            JsonGate.keys(j,"surveyRevision","claimIds","exclusions","unknowns","discovery","summary")
            require(JsonGate.integer(j,"surveyRevision")==d.revision && JsonGate.strings(j.getJSONArray("claimIds"),30).sorted()==expected && JsonGate.strings(j.getJSONArray("exclusions"),20).sorted()==p.exclusions.sorted() && JsonGate.number(j,"discovery")==p.discovery && JsonGate.strings(j.getJSONArray("unknowns"),7).sorted()==p.unknowns.sorted());JsonGate.string(j,"summary")
        }
    }
    suspend fun select(enabled: Boolean, candidates: List<Track>, fallback: List<Track>, profile: SurveyProfile, semantic: JSONObject, review: List<Outcome>, constraints: Constraints, discovery: Double, progress: DiscoveryProgress): Selection {
        if(!enabled || !gateway.configured) return Selection(fallback,"초기 취향 · 로컬 추천 · L0 열기 전용")
        val result=withTimeoutOrNull(20000) {
            try {
                val key=semantic.toString()
                val context=contextCache?.takeIf { it.first==key }?.second?:gateway.call(Role.CONTEXT,semantic,Schema.obj(mapOf("context" to Schema.string(),"confidence" to Schema.string(),"missing" to strings))) { j ->
                    JsonGate.keys(j,"context","confidence","missing");require(JsonGate.string(j,"context") in setOf("GENERAL_DRIVE","UNKNOWN") && JsonGate.string(j,"confidence")=="LOW");JsonGate.strings(j.getJSONArray("missing"),8)
                }.also { contextCache=key to it }
                val latest=PreferenceLearner.latest(review).takeLast(3)
                val summaries=JSONArray(latest.map { JSONObject().put("attemptId",it.attemptId).put("trackId",it.trackId).put("score",PreferenceLearner.score(it)).put("source",if(it.explicit!=null) "EXPLICIT_RATING" else "OBSERVATION") })
                val pattern=when { latest.isEmpty() || latest.all { PreferenceLearner.score(it)==0.0 } -> "UNKNOWN"; latest.size==3 && latest.all { PreferenceLearner.score(it)<0 } -> "ALL_NEGATIVE";latest.all { PreferenceLearner.score(it)>0 } -> "POSITIVE";else -> "MIXED" }
                val reviewed=if(latest.isEmpty()) JSONObject().put("pattern","UNKNOWN") else gateway.call(Role.REVIEW,JSONObject().put("outcomes",summaries).put("expectedPattern",pattern),Schema.obj(mapOf("pattern" to Schema.string(),"sourceAttemptIds" to strings))) { j ->
                    JsonGate.keys(j,"pattern","sourceAttemptIds");require(JsonGate.string(j,"pattern")==pattern && JsonGate.strings(j.getJSONArray("sourceAttemptIds"),3).sorted()==latest.map { it.attemptId }.sorted())
                }
                val input=JSONObject().put("context",context).put("review",reviewed).put("discoveryTarget",discovery).put("sessionTotal",progress.total).put("sessionDiscoveries",progress.discoveries)
                    .put("preferences",JSONArray(profile.preferences.map { JSONObject().put("axis",it.axis).put("value",it.value).put("scope",it.scope.name) })).put("exclusions",JSONArray(profile.exclusions.sorted()))
                    .put("candidates",JSONArray(candidates.take(40).map { t -> JSONObject().put("id",t.id).put("title",t.title.take(100)).put("artist",t.artist.take(80)).put("familiar",t.familiar).put("affinity",t.affinity).put("fatigue",t.fatigue).put("features",JSONArray(t.features.filter { it.valid }.map { "${it.axis}:${it.value}" })) }))
                val selected=gateway.call(Role.SELECTOR,input,Schema.obj(mapOf("trackIds" to strings,"evidence" to strings))) { j ->
                    JsonGate.keys(j,"trackIds","evidence");val ids=JsonGate.strings(j.getJSONArray("trackIds"),3)
                    require(ids.isNotEmpty() && ids.distinct().size==ids.size && ids.all { id -> candidates.any { it.id==id && constraints.allows(it) } });require(JsonGate.strings(j.getJSONArray("evidence"),3).all { it in setOf("SURVEY","PROVIDER","DIVERSITY","EXPLICIT_RATING","CONTEXT") })
                }
                Selection(JsonGate.strings(selected.getJSONArray("trackIds"),3).map { id -> candidates.single { it.id==id } },"Gemini · 검증된 3곡 · L0 열기 전용")
            } catch(e: CancellationException) { throw e } catch(_: Exception) { Selection(fallback,"AI 응답 미적용 · 로컬 추천 · L0 열기 전용") }
        }
        return result?:Selection(fallback,"AI 시간 초과 · 로컬 추천 · L0 열기 전용")
    }
}
