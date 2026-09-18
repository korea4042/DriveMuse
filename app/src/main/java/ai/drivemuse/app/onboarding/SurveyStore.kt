package ai.drivemuse.app.onboarding

import ai.drivemuse.app.*
import ai.drivemuse.domain.*
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

data class SurveyDraft(val step: Int=0, val revision: Long=0, val completed: Boolean=false, val answers: List<SurveyAnswer> = emptyList(), val aiConsent: Boolean=false)
class SurveyStore(private val db: DriveDatabase) {
    suspend fun load(): SurveyDraft = db.intelligence().state("survey")?.let { decode(JSONObject(it.json)) }?:SurveyDraft()
    fun encode(d: SurveyDraft): JSONObject = JSONObject().put("step",d.step).put("revision",d.revision).put("completed",d.completed).put("aiConsent",d.aiConsent).put("schemaVersion","2.1").put("answers",JSONArray(d.answers.map { a ->
        JSONObject().put("questionId",a.question.id).put("questionVersion",a.question.version).put("questionText",a.question.text).put("intent",a.question.intent.name).put("scope",a.question.scope.name).put("answerType",if(a.question.options.isEmpty()) "FREE_TEXT" else if(a.question.multiple) "MULTIPLE" else "SINGLE")
            .put("options",JSONArray(a.question.options.map { JSONObject().put("id",it.id).put("label",it.label).put("axis",it.axis).put("value",it.value) }))
            .put("selectedOptionIds",JSONArray(a.selected.sorted())).put("freeText",a.freeText).put("answerStatus",a.status.name)
    }))
    private fun decode(j: JSONObject): SurveyDraft {
        val arr=j.getJSONArray("answers")
        val answers=(0 until arr.length()).map { i -> val a=arr.getJSONObject(i);val q=Survey.questions.single { it.id==a.getString("questionId") };val selected=a.getJSONArray("selectedOptionIds");SurveyAnswer(q,(0 until selected.length()).map { selected.getString(it) }.toSet(),a.getString("freeText"),AnswerStatus.valueOf(a.getString("answerStatus"))) }
        Survey.map(answers)
        return SurveyDraft(j.getInt("step").coerceIn(0,Survey.questions.size),j.getLong("revision"),j.getBoolean("completed"),answers,j.optBoolean("aiConsent",false))
    }
    suspend fun save(d: SurveyDraft): SurveyDraft = db.withTransaction {
        Survey.map(d.answers)
        val saved=d.copy(revision=(db.intelligence().state("survey")?.version?:0)+1)
        db.intelligence().putState(IntelligenceState("survey",encode(saved).toString(),saved.revision,System.currentTimeMillis()))
        if(saved.completed) {
            val p=Survey.map(saved.answers)
            db.intelligence().putState(IntelligenceState("survey_profile",JSONObject().put("preferences",JSONArray(p.preferences.map { JSONObject().put("axis",it.axis).put("value",it.value).put("questionId",it.questionId).put("optionId",it.optionId).put("scope",it.scope.name).put("score",it.score).put("behaviourSupportCount",0) })).put("exclusions",JSONArray(p.exclusions.sorted())).put("discovery",p.discovery).toString(),saved.revision,System.currentTimeMillis()))
        }
        db.intelligence().invalidate();saved
    }
}
