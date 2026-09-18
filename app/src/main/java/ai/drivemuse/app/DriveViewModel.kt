package ai.drivemuse.app

import ai.drivemuse.app.onboarding.*
import ai.drivemuse.app.gemini.*
import ai.drivemuse.app.playback.*
import ai.drivemuse.app.learning.*
import ai.drivemuse.app.context.*
import androidx.room.withTransaction
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.drivemuse.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID

data class UiState(
    val page: String = "홈", val demo: Boolean = false, val context: DriveContext = DriveContext.GENERAL_DRIVE,
    val queue: List<Track> = emptyList(), val busy: Boolean = false, val message: String? = null,
    val driving: Boolean = false, val pendingRule: MusicRule? = null,
    val connection: String = "미연결", val reason: String = "좋아하는 음악과 새로운 발견 사이",
    val engineLabel: String = "초기 취향 · L0 열기 전용", val weatherLabel: String = "날씨 정보 없음", val consent: android.app.PendingIntent? = null
)
class DriveViewModel(application: Application): AndroidViewModel(application) {
    private val prefs = Preferences(application)
    private val db=DriveDatabase.get(application)
    private val dao=db.dao()
    private val intelligence=db.intelligence()
    private val surveyStore=SurveyStore(db)
    private val runtime = IntegrationRuntime.get(application)
    private val gateway=RoleGateway(application,intelligence,{ runtime.secrets(ProviderId.FIREBASE_AI) },{ runtime.secrets(ProviderId.GEMINI_DIRECT) })
    private val engine=RecommendationEngine(gateway)
    private val coordinator=QueueCoordinator(db)
    private val playback=YouTubeMusicAdapter(application)
    private val learning=LearningStore(db)
    private val location=LocationAdapter(application)
    private val weather=WeatherRepository { runtime.secret(ProviderId.WEATHER,"apiKey") ?: "" }
    private var region: Region?=null
    private var weatherFact: WeatherFact?=null
    private var contextVersion=0L
    private var contextJob: Job?=null
    private val sessionId=UUID.randomUUID().toString()
    private var firstMoodSession: String?=null
    private var progress=DiscoveryProgress()
    private var seedRevision=-1L
    private val draftMutable=MutableStateFlow<SurveyDraft?>(null)
    val survey=draftMutable.asStateFlow()
    private val surveyBusyMutable=MutableStateFlow(false)
    val surveyBusy=surveyBusyMutable.asStateFlow()
    private val edits=Channel<suspend ()->Unit>(Channel.UNLIMITED)
    private var surveyJob: Job?=null
    val aiConfigured get()=gateway.configured
    val weatherConfigured get()=weather.configured
    val analysis=intelligence.observeState("survey_analysis").stateIn(viewModelScope,SharingStarted.Eagerly,null)
    private val tokens = runtime.tokens
    private val auth = YouTubeAuth(application)
    private val api = runtime.youtube   // v2.3 §22: key comes from the encrypted runtime config, BuildConfig only as default
    private val repository = MusicRepository(api, dao, prefs)
    val settings = prefs.flow.stateIn(viewModelScope,SharingStarted.Eagerly,Settings())
    val rules = dao.rules().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val history = dao.history().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    private val mutable = MutableStateFlow(UiState())
    val ui = mutable.asStateFlow()
    private var selectionJob: Job? = null
    private var accountJob: Job? = null
    private var selectionGeneration = 0
    /** Live check against the runtime config (T14: no rebuild needed after entering a key). */
    val apiKeyConfigured get() = runtime.secret(ProviderId.YOUTUBE, "apiKey")?.isNotBlank() == true
    /** §22: a linked Google account authorizes public reads, so either path is enough to query YouTube. */
    val youtubeUsable get() = apiKeyConfigured || settings.value.accountLinked

    init {
        viewModelScope.launch {
            for(edit in edits) { surveyBusyMutable.value=true;try { edit() } catch(e: CancellationException) { throw e } catch(e: Exception) { message(explain(e)) } finally { surveyBusyMutable.value=false } }
        }
        viewModelScope.launch {
            dao.prune(System.currentTimeMillis()-2592000000L);learning.prune(System.currentTimeMillis())
            val d=surveyStore.load();draftMutable.value=d
            if(d.completed) { val restored=coordinator.restore(d.revision);mutable.update { it.copy(queue=restored,engineLabel="저장된 추천 · L0 열기 전용") } }
        }
    }
    private fun changeSurvey(transform: (SurveyDraft)->SurveyDraft) {
        if(ui.value.driving) return
        cancelSelection();surveyJob?.cancel();engine.clearCache()
        edits.trySend { coordinator.invalidate();val d=draftMutable.value?:surveyStore.load();draftMutable.value=surveyStore.save(transform(d)) }
    }
    fun surveyAnswer(a: SurveyAnswer)=changeSurvey { it.copy(answers=it.answers.filter { old -> old.question.id!=a.question.id }+a) }
    fun surveyStep(step: Int)=changeSurvey { it.copy(step=step.coerceIn(0,Survey.questions.size)) }
    fun surveyConsent(value: Boolean)=changeSurvey { it.copy(aiConsent=value) }
    fun editTaste()=changeSurvey { it.copy(completed=false,step=0) }
    fun completeSurvey(demo: Boolean) {
        if(ui.value.driving) return
        cancelSelection()
        edits.trySend {
            val d=surveyStore.save((draftMutable.value?:surveyStore.load()).copy(completed=true,step=Survey.questions.size));draftMutable.value=d
            prefs.ratio(Survey.map(d.answers).discovery.toFloat());prefs.flag("onboarded",true)
            firstMoodSession=sessionId;mutable.update { it.copy(demo=demo,queue=emptyList()) }
            if(d.aiConsent && gateway.configured) surveyJob=viewModelScope.launch {
                try { val result=engine.analyzeSurvey(d,surveyStore)
                    db.withTransaction { if(intelligence.state("survey")?.version==d.revision) intelligence.putState(IntelligenceState("survey_analysis",result.toString(),d.revision,System.currentTimeMillis())) }
                } catch(e: CancellationException) { throw e } catch(_: Exception) { message("설문 답변을 직접 반영했습니다. AI 분석은 적용하지 않았습니다") }
            }
            if(demo) recommend()
        }
    }
    private fun profile(): SurveyProfile {
        val p=Survey.map(survey.value?.answers?:emptyList())
        return if(firstMoodSession==sessionId) p else p.copy(preferences=p.preferences.filter { it.scope==Scope.LONG_TERM })
    }
    fun refreshWeather() {
        if(ui.value.driving) return
        contextJob?.cancel();contextJob=viewModelScope.launch {
            region=location.refresh();weatherFact=region?.let { weather.get(it) };contextVersion++
            cancelSelection();coordinator.invalidate()
            val fact=weatherFact
            mutable.update { it.copy(weatherLabel=if(fact==null) "날씨 정보 없음 · 기본 상황으로 추천" else "기상청 · ${fact.temperature}°C · ${if(fact.precipitation>0) "강수" else "강수 없음"} · ${java.time.Instant.ofEpochMilli(fact.observedAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime()}${if(fact.stale(System.currentTimeMillis())) " · 오래된 관측" else ""}") }
        }
    }
    fun registerZone(zone: Zone) { if(ui.value.driving) return;contextJob?.cancel();contextJob=viewModelScope.launch { message(if(location.register(zone)) "영역을 암호화하여 저장했습니다" else "위치 권한과 정확도를 확인해 주세요") } }
    fun deleteZones() { if(ui.value.driving) return;contextJob?.cancel();location.deleteZones();region=null;weatherFact=null;weather.clear();contextVersion++;cancelSelection();viewModelScope.launch { coordinator.invalidate() };message("등록 영역을 삭제했습니다") }
    fun rate(track: Track, positive: Boolean) {
        if(ui.value.driving || ui.value.demo) return
        cancelSelection()
        viewModelScope.launch {
            val id="explicit:$sessionId:${track.id}"
            db.withTransaction { val version=(intelligence.outcome(id)?.version?:0)+1;intelligence.acceptOutcome(OutcomeEntity(id,track.id,sessionId,version,if(positive) 1.0 else -1.0,true,System.currentTimeMillis())) }
            coordinator.invalidate();message("명시적 평가를 다음 추천에 반영합니다")
        }
    }
    fun resetLearning() { if(ui.value.driving) return;cancelSelection();surveyJob?.cancel();engine.clearCache();viewModelScope.launch { db.withTransaction { intelligence.clearOutcomes();intelligence.clearBatches();intelligence.clearAnalysis() };progress=DiscoveryProgress();message("학습 기록을 초기화했습니다. 설문은 유지합니다") } }


    fun page(page: String) {
        if (ui.value.driving && page != "홈") { message("설정과 탐색은 정차 후 이용해 주세요"); return }
        mutable.update { it.copy(page = page) }
    }
    fun message(text: String?) { mutable.update { it.copy(message=text) } }
    fun onboard(demo: Boolean) { viewModelScope.launch { prefs.flag("onboarded",true); mutable.update { it.copy(demo=demo,context=if(demo) DriveContext.COMMUTE_HOME else DriveContext.GENERAL_DRIVE) }; if (demo) recommend() } }
    fun demo(value: Boolean) { cancelSelection();progress=DiscoveryProgress(); mutable.update { it.copy(demo=value,queue=emptyList(),connection=if(value) "데모 · 계정 미연결" else connectionLabel(settings.value)) } }
    fun driving(value: Boolean) { if(value) { cancelSelection();contextJob?.cancel() }; mutable.update { it.copy(driving=value,page="홈") } }
    fun auto(value: Boolean) { if (ui.value.driving) return; viewModelScope.launch { prefs.flag("auto",value) } }
    fun ratio(value: Float) { if(ui.value.driving) return;cancelSelection();viewModelScope.launch { coordinator.invalidate();prefs.ratio(value) } }
    fun choose(context: DriveContext) { if (ui.value.driving) return; suspendAgent();contextVersion++;mutable.update { it.copy(context=context) }; recommend() }

    /**
     * §8.5 — consent UI never appears while driving, so a silent grant is attempted and a
     * required consent screen is deferred to the phone instead.
     */
    fun linkAccount(interactive: Boolean = true) {
        if (ui.value.driving) { message("계정 연결은 정차 후 진행해 주세요"); return }
        accountJob?.cancel();accountJob=viewModelScope.launch {
            when (val state = auth.authorize()) {
                is AuthState.Authorized -> onAuthorized(state.token)
                is AuthState.NeedsConsent -> if (interactive) mutable.update { it.copy(consent = state.pendingIntent) } else message("Google 계정 연결을 다시 확인해 주세요")
                AuthState.Unavailable -> message("Google 계정 연결을 시작할 수 없습니다. Play 서비스와 네트워크를 확인해 주세요")
            }
        }
    }
    fun consentResult(data: Intent?) {
        mutable.update { it.copy(consent = null) }
        accountJob?.cancel();accountJob=viewModelScope.launch {
            when (val state = auth.fromIntent(data)) {
                is AuthState.Authorized -> onAuthorized(state.token)
                else -> message("계정 연결이 취소되었습니다")
            }
        }
    }
    private suspend fun onAuthorized(token: String) {
        cancelSelection();coordinator.invalidate()
        tokens.put(token)
        prefs.flag("accountLinked", true)
        mutable.update { it.copy(demo=false, connection="Google 계정 연결됨") }
        message("연결했습니다. 좋아요와 구독을 불러오는 중이에요")
        runCatching { repository.refresh(settings.value.copy(accountLinked = true)) }
            .onSuccess { message("취향을 불러왔습니다") }
            .onFailure { message(explain(it)) }
    }

    fun recommend() {
        if(survey.value?.completed!=true) return
        if(ui.value.driving) { message("정차 후 선곡을 시작해 주세요"); return }
        cancelSelection()
        val generation = selectionGeneration
        selectionJob = viewModelScope.launch {
            mutable.update { it.copy(busy=true) }
            val snapshot = ui.value
            val draft=survey.value?:return@launch
            val revision=draft.revision
            try {
                val config = prefs.flow.first()
                val ruleSnapshot = dao.rules().first().map { it.domain() }
                val effective = RuleEngine.resolve(ruleSnapshot,snapshot.context,config.ratio.toDouble())
                val tracks = if(snapshot.demo) DemoTracks else {
                    check(youtubeUsable) { "설정에서 Google 계정을 연결하거나 YouTube Data API 키를 입력해 주세요" }
                    // A silent token top-up: the linked account may simply have an expired token.
                    if (config.accountLinked && tokens.current() == null) linkAccountSilently()
                    if(seedRevision!=revision) {
                        try { repository.addSurveyCandidates(draft.answers);seedRevision=revision } catch(e: CancellationException) { throw e } catch(_: Exception) { /* Existing pool remains usable. */ }
                    }
                    repository.candidates(snapshot.context, config)
                }
                val p=profile();val constraints=Constraints(excludedGenres=p.exclusions)
                val prepared=TasteRanker.prepare(tracks,p,constraints,learning.scores(sessionId,System.currentTimeMillis())).filter { effective.energyCeiling>=1 || it.energy?.let { e -> e<=effective.energyCeiling }==true }.sortedByDescending { it.affinity-it.fatigue }.take(40)
                val fallback=SessionRanker.select(prepared,effective,progress)
                val outcomes=intelligence.outcomes().filter { it.sessionId==sessionId }.sortedBy { it.createdAt }.map(learning::outcome)
                val version=QueueVersion(sessionId,generation.toLong(),revision,contextVersion,outcomes.maxOfOrNull { it.version }?:0,UUID.randomUUID().toString())
                coordinator.begin(version)
                val now=System.currentTimeMillis();val validRegion=region?.takeIf { now-it.measuredAt in 0..120000 }
                val validWeather=validRegion?.let { r -> weatherFact?.takeIf { it.usable(r.id,now) } }
                val semantic=JSONObject().put("zone",validRegion?.zone?.name?:"UNKNOWN").put("timeOfDay",LocalDateTime.now().hour).put("origin","UNKNOWN").put("direction","UNKNOWN")
                validWeather?.let { semantic.put("weather",JSONObject().put("temperature",it.temperature).put("precipitation",it.precipitation).put("stale",it.stale(now))) }
                val novelty=if(snapshot.demo) emptyMap() else runCatching { ai.drivemuse.app.catalog.NoveltyAnnotator(db.catalog()).annotate(prepared,emptySet(),runtime.historyCoverageSince()) }.getOrDefault(emptyMap())
                val selection=engine.select(draft.aiConsent && !snapshot.demo,prepared,fallback,p,semantic,outcomes,constraints,effective.discovery,progress,version,0,listOf(0,1,2),novelty,MixTarget.resolve(p))
                val queue=selection.tracks
                if(generation!=selectionGeneration || survey.value?.revision!=revision) return@launch
                check(queue.isNotEmpty()) { if (effective.energyCeiling < 1.0) "잔잔한 곡 조건에 맞는 후보가 없습니다. 규칙을 조정해 주세요" else "조건에 맞는 곡이 없습니다. 규칙을 조정해 주세요" }
                if(!snapshot.demo && !coordinator.commit(version,queue,prepared,constraints)) return@launch
                if(generation!=selectionGeneration || survey.value?.revision!=revision) return@launch
                progress=progress.append(queue)
                mutable.update { it.copy(queue=queue,engineLabel=selection.label,connection=if(snapshot.demo) "데모 · 계정 미연결" else connectionLabel(config),reason="${snapshot.context.label} · 새 노래 목표 ${(effective.discovery*100).toInt()}% · "+when(selection.adjustment) { "REDUCE_RECENT_SKIP"->"최근 넘긴 곡을 피해서 골랐어요";"FAVOR_SUPPORTED_FEATURE"->"반응이 좋았던 특성을 우선했어요";"EXPLORE_ALTERNATIVE"->"다른 방향의 곡을 섞었어요";else->"설정된 취향을 바탕으로 골랐어요" }+(if("NOVEL_POOL_SHORTAGE" in selection.unmet) " · 새 후보가 부족해요" else "")) }
                dao.putHistory(HistoryEntity(UUID.randomUUID().toString(),snapshot.context.name,snapshot.context.mix,queue.size,System.currentTimeMillis(),demo=snapshot.demo))
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { message(explain(e)) }
            finally { if(generation == selectionGeneration) mutable.update { it.copy(busy=false) } }
        }
    }
    private suspend fun linkAccountSilently() {
        when (val state = auth.authorize()) {
            is AuthState.Authorized -> tokens.put(state.token)
            // Deferred, per §6.8: degrade to public data rather than interrupting.
            else -> Unit
        }
    }

    /** §6.8 — every failure has one recovery path and only re-auth is worth surfacing. */
    private fun explain(e: Throwable): String = when (e) {
        is QuotaExceededException -> "오늘의 조회 한도를 모두 사용했습니다. 저장된 후보로 계속 재생할 수 있어요"
        is AuthExpiredException, is UserAuthRequiredException -> "Google 계정 연결을 다시 확인해 주세요"
        is ApiNotConfiguredException -> e.message ?: "API 설정을 확인해 주세요"
        is IllegalStateException, is IllegalArgumentException -> e.message ?: "추천 실패"
        else -> "연결할 수 없습니다. 기존 음악은 그대로 유지합니다"
    }
    private fun connectionLabel(s: Settings) = when {
        !apiKeyConfigured && !s.accountLinked -> "연결 필요 · 계정 또는 API 키"
        s.accountLinked -> "Google 계정 연결됨 · 읽기 전용"
        else -> "계정 미연결 · 인기 음악만 사용"
    }

    private fun cancelSelection() { selectionGeneration++; selectionJob?.cancel(); selectionJob=null; mutable.update { it.copy(busy=false) } }
    fun suspendAgent() { cancelSelection(); viewModelScope.launch { coordinator.invalidate();prefs.suspendUntil(System.currentTimeMillis()+30*60*1000); message("30분 동안 사용자 선택을 유지합니다") } }

    /** Handoff is exposure only. It never creates a listening outcome. */
    fun handoff(track: Track?) {
        suspendAgent()
        if(track!=null && !Constraints(excludedGenres=profile().exclusions).allows(track)) { message("현재 제외 조건에 맞지 않는 곡입니다");return }
        val result = playback.open(track)
        // Exposure only (§17 EXPOSED_ONLY): counted for fatigue and novelty, never as listening.
        if (track != null && !ui.value.demo) viewModelScope.launch { repository.recordPlay(track.id); db.catalog().ref("youtube",track.id)?.trackId?.let { db.catalog().recordExposure("default",it,System.currentTimeMillis(),runtime.historyCoverageSince()) } }
        message(result)
    }

    fun parseRule(text: String) {
        if(ui.value.driving) return
        val parsed = RuleEngine.parse(text.take(240),UUID.randomUUID().toString(),System.currentTimeMillis())
        if (parsed==null) message("예: ‘퇴근길에는 잔잔하게, 새 노래 30%’처럼 입력해 주세요")
        else mutable.update { it.copy(pendingRule=parsed) }
    }
    fun confirmRule(save: Boolean) { if(save) cancelSelection(); val r=ui.value.pendingRule; mutable.update { it.copy(pendingRule=null) }; if(save && r!=null && !ui.value.driving) viewModelScope.launch { coordinator.invalidate();dao.putRule(RuleEntity.from(r)) } }
    fun deleteRule(id: String) { cancelSelection(); if (!ui.value.driving) viewModelScope.launch { coordinator.invalidate();dao.deleteRule(id) } }
    fun toggleRule(rule: RuleEntity) { cancelSelection(); if(!ui.value.driving) viewModelScope.launch { coordinator.invalidate();dao.putRule(rule.copy(enabled=!rule.enabled)) } }
    fun feedback(id: String, feedback: String) { if(!ui.value.driving) viewModelScope.launch { dao.feedback(id,feedback) } }
    fun registerVehicle(id: String,name: String) { if(ui.value.driving) return; viewModelScope.launch { prefs.string("vehicleId",id); prefs.string("vehicleName",name); prefs.flag("connected",false); message("차량을 등록했습니다. 다음 연결부터 감지합니다") } }
    fun classifyNow() {
        val result = ContextEngine.classify(Signals(settings.value.connected,LocalDateTime.now()))
        mutable.update { it.copy(context=if(result.confidence>=.8) result.context else DriveContext.GENERAL_DRIVE,reason=result.reasons.joinToString(" · ")+" · 확신도 ${(result.confidence*100).toInt()}%") }
    }
    fun disconnect() {
        if(ui.value.driving) return
        cancelSelection();accountJob?.cancel()
        viewModelScope.launch {
            coordinator.invalidate();tokens.clear(); repository.clearCache()
            prefs.flag("accountLinked", false); prefs.long("tasteSyncedAt", 0)
            mutable.update { it.copy(connection="미연결",queue=emptyList()) }
            message("이 기기의 연결과 후보 목록을 삭제했습니다. 접근 권한 자체는 Google 계정 설정에서 해제해 주세요")
        }
    }
    fun clearHistory() { if(ui.value.driving) return;resetLearning();viewModelScope.launch { dao.clearHistory();dao.clearPlayed();message("추천 기록과 관련 학습 기여분을 삭제했습니다") } }
    fun resetAll() {
        if(ui.value.driving) return
        cancelSelection();accountJob?.cancel();surveyJob?.cancel();contextJob?.cancel();engine.clearCache()
        edits.trySend { coordinator.invalidate();tokens.clear();repository.clearCache();dao.clearHistory();dao.clearRules();intelligence.clearOutcomes();intelligence.clearBatches();intelligence.clearState();prefs.clear();location.deleteZones();location.clear();weather.clear();region=null;weatherFact=null;progress=DiscoveryProgress();seedRevision=-1L;firstMoodSession=null;contextVersion++;draftMutable.value=SurveyDraft();mutable.value=UiState() }
    }
}
