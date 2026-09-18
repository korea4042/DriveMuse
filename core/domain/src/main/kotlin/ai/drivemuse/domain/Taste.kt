package ai.drivemuse.domain

enum class AnswerStatus { ANSWERED, UNKNOWN, SKIPPED, ANY, NONE }
enum class Intent { PREFERENCE, EXCLUSION, DISCOVERY, MOOD }
enum class Scope { LONG_TERM, FIRST_SESSION }
data class Choice(val id: String, val label: String, val axis: String, val value: String)
data class Question(val id: String, val text: String, val intent: Intent, val options: List<Choice>, val scope: Scope = Scope.LONG_TERM, val multiple: Boolean = true, val version: Int = 1)
data class SurveyAnswer(val question: Question, val selected: Set<String> = emptySet(), val freeText: String = "", val status: AnswerStatus = AnswerStatus.SKIPPED)
data class Preference(val axis: String, val value: String, val questionId: String, val optionId: String, val scope: Scope, val score: Double = .4)
data class SurveyProfile(val preferences: List<Preference>, val exclusions: Set<String>, val discovery: Double, val unknowns: List<String>)
object Survey {
    private fun options(axis: String, vararg values: Pair<String,String>) = values.map { Choice("${axis}_${it.first}",it.second,axis,it.first) }
    val genres = options("genre", "POP" to "팝", "RNB" to "R&B", "HIP_HOP" to "힙합", "ROCK" to "록", "JAZZ" to "재즈", "CLASSICAL" to "클래식", "ELECTRONIC" to "일렉트로닉")
    val questions = listOf(
        Question("Q1","어느 음악권의 음악을 좋아하나요?",Intent.PREFERENCE,options("market","KR" to "한국", "WESTERN" to "서구권", "JP" to "일본", "OTHER" to "그 외")),
        Question("Q2","좋아하는 장르를 골라 주세요",Intent.PREFERENCE,genres),
        Question("Q3","선호하는 가사 언어가 있나요?",Intent.PREFERENCE,options("language","ko" to "한국어", "en" to "영어", "ja" to "일본어", "instrumental" to "가사 없음")),
        Question("Q4","새로운 곡을 얼마나 만나고 싶나요?",Intent.DISCOVERY,options("discovery","FAMILIAR" to "익숙한 곡 위주", "BALANCED" to "균형 있게", "ADVENTUROUS" to "새로운 곡 위주"),multiple=false),
        Question("Q5","추천에서 꼭 제외할 장르가 있나요?",Intent.EXCLUSION,genres),
        Question("Q6","첫 드라이브는 어떤 분위기가 좋나요?",Intent.MOOD,options("mood","CALM" to "차분하게", "BRIGHT" to "밝게", "ENERGETIC" to "활기차게"),Scope.FIRST_SESSION,false),
        Question("Q7","좋아하는 곡이나 아티스트를 알려 주세요",Intent.PREFERENCE,emptyList())
    )
    fun map(answers: List<SurveyAnswer>): SurveyProfile {
        require(answers.map { it.question.id }.distinct().size == answers.size)
        val prefs = mutableListOf<Preference>(); val excluded = mutableSetOf<String>(); val unknown = mutableListOf<String>(); var ratio=.25
        questions.forEach { q ->
            val a=answers.find { it.question.id==q.id }
            if(a==null) { unknown+=q.id; return@forEach }
            require(a.question==q && a.freeText.length<=240 && a.selected.all { id -> q.options.any { it.id==id } } && (q.multiple || a.selected.size<=1))
            if(a.status==AnswerStatus.UNKNOWN || a.status==AnswerStatus.SKIPPED) unknown+=q.id
            if(a.status!=AnswerStatus.ANSWERED) return@forEach
            q.options.filter { it.id in a.selected }.forEach { c -> when(q.intent) {
                Intent.EXCLUSION -> excluded+=c.value
                Intent.DISCOVERY -> ratio=when(c.value) { "FAMILIAR"->.15; "ADVENTUROUS"->.55; else->.35 }
                else -> prefs+=Preference(c.axis,c.value,q.id,c.id,q.scope)
            } }
        }
        // Free text is an unverified search seed. Do not manufacture genre/market evidence.
        return SurveyProfile(prefs,excluded,ratio,unknown)
    }
}
data class VerifiedFeature(val axis: String, val value: String, val source: String, val confidence: Double) {
    val valid get() = source.isNotBlank() && confidence.isFinite() && confidence in .8..1.0
}
data class Constraints(val excludedIds: Set<String> = emptySet(), val excludedArtists: Set<String> = emptySet(), val excludedGenres: Set<String> = emptySet(), val required: Map<String,Set<String>> = emptyMap()) {
    fun allows(t: Track): Boolean {
        if(t.id in excludedIds || t.artist in excludedArtists || t.skipped) return false
        val f=t.features.filter { it.valid }; val genres=f.filter { it.axis=="genre" }.map { it.value }
        if(excludedGenres.isNotEmpty() && (genres.isEmpty() || genres.any { it in excludedGenres })) return false
        return required.all { (axis,values) -> f.any { it.axis==axis && it.value in values } }
    }
}
object TasteRanker {
    fun prepare(tracks: List<Track>, profile: SurveyProfile, constraints: Constraints, learned: Map<String,Double> = emptyMap()) = tracks.filter { constraints.allows(it) }.map { t ->
        val boost=profile.preferences.filter { p -> p.scope==Scope.LONG_TERM && t.features.any { it.valid && it.axis==p.axis && it.value==p.value } }.sumOf { it.score*.25 }
        val mood=profile.preferences.firstOrNull { it.scope==Scope.FIRST_SESSION && it.axis=="mood" }?.value
        val target=when(mood) { "CALM"->.3;"BRIGHT"->.6;"ENERGETIC"->.8;else->null }
        t.copy(affinity=(t.affinity+boost+(learned[t.id]?:0.0)).coerceIn(0.0,1.0),contextFit=if(target!=null && t.energy!=null) 1.0-kotlin.math.abs(t.energy-target) else t.contextFit)
    }
}
data class DiscoveryProgress(val total: Int = 0, val discoveries: Int = 0) {
    fun append(tracks: List<Track>) = copy(total=total+tracks.size,discoveries=discoveries+tracks.count { !it.familiar })
}
object SessionRanker {
    fun select(tracks: List<Track>, rules: EffectiveRules, progress: DiscoveryProgress, count: Int = 3): List<Track> {
        val pool=tracks.distinctBy { it.id }.filter { !it.skipped && (rules.energyCeiling>=1 || it.energy?.let { e -> e<=rules.energyCeiling }==true) }.sortedByDescending { .4*it.affinity+.25*it.contextFit+.15*it.freshness-it.fatigue }.toMutableList()
        val result=mutableListOf<Track>()
        while(result.size<count && pool.isNotEmpty()) {
            val wantNew=progress.discoveries+result.count { !it.familiar } < (progress.total+result.size+1)*rules.discovery
            val eligible=pool.filter { it.artist!=result.lastOrNull()?.artist }
            if(eligible.isEmpty()) break
            val next=eligible.firstOrNull { !it.familiar==wantNew }?:eligible.first(); result+=next; pool.remove(next)
        }
        return result
    }
    fun adjustedDiscovery(base: Double, adjustment: Double)=(base+adjustment.coerceIn(-.05,.05)).coerceIn(.1,.6)
}
