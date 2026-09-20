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
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

data class UiState(
    val page: String = "홈", val demo: Boolean = false, val context: DriveContext = DriveContext.GENERAL_DRIVE,
    val queue: List<Track> = emptyList(), val busy: Boolean = false, val message: String? = null,
    val driving: Boolean = false, val pendingRule: MusicRule? = null,
    val connection: String = "미연결", val reason: String = "좋아하는 음악과 새로운 발견 사이",
    val engineLabel: String = "초기 취향 · Spotify 재생", val weatherLabel: String = "날씨 정보 없음", val consent: android.app.PendingIntent? = null,
    /**
     * What the app is doing right now, in the user's words. Tapping a button that talks to the
     * network or the GPS used to change nothing on screen until it finished, so the only way to
     * tell it had registered was to wait and see.
     */
    val working: String? = null,
    /**
     * §7 QUE01, FIX-B: why the list no longer matches its conditions. A set rather than a flag,
     * because a recovery from an off-plan recording must not also clear an outstanding rule change.
     */
    val stale: Set<StaleReason> = emptySet(),
    /**
     * UX04: every condition this batch could not meet, together. This used to be one snackbar
     * chosen by an if/else chain, so a batch that was short *and* had an unapplied energy rule
     * *and* had to break the artist cap reported exactly one of the three and hid the rest.
     */
    val notices: List<String> = emptyList(),
    /** §7: source, region, observation time, lookup time and freshness, each stated separately. */
    val weatherDetail: String = "아직 조회하지 않았어요",
    /**
     * §4: purpose, basis, time band, distance and route status together. `context` above is the
     * lossy one-value projection the rule engine still reads; this is the whole judgement.
     */
    val assessment: ContextAssessment? = null
) {
    /** Re-selection fixes conditions that moved; it does not fix a queue awaiting verification. */
    val needsReselect get() = stale.any { it.fixedByReselection }
    val staleLabel get() = when {
        needsReselect -> "조건이 바뀌었어요 · 다시 고르기"
        StaleReason.CONTROL_LOST in stale -> "다른 곡이 재생됐어요 · 목록에서 다시 선택"
        StaleReason.DELIVERY_UNCERTAIN in stale -> "대기 곡 전달 결과를 확인하지 못했어요"
        StaleReason.RESTORE_UNVERIFIED in stale -> "저장된 목록 · 재생하면 이어집니다"
        else -> null
    }
}
/** Candidate pool had nothing eligible. Distinct from a network or database failure (FIX-C). */
private class PoolExhausted(message: String): IllegalStateException(message)

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
    private val learning=LearningStore(db)
    // Phase 1 §4/§6: the App Remote stream finally has a consumer, so listening becomes evidence
    // and the last track of a batch triggers the next one.
    private val scheduler=NextBatchScheduler({ base -> prepareNextBatch(base) }, viewModelScope)
    private val observer=PlaybackObserver(db,learning,{ sessionId },System::currentTimeMillis,
        { id,ordinal,size -> onPlannedStart(id,ordinal,size) },
        { id -> onUnplannedPlayback(id) })
    /**
     * §3: one entry per control, so two buttons cannot overwrite each other's progress and a
     * settled result stays readable after the snackbar has gone.
     */
    private val operations=OperationRegistry(viewModelScope,{ e -> explain(e) })
    val operationStates=operations.flow
    fun cancelOperation(target: String) = operations.cancel(target)
    fun dismissOperation(target: String) = operations.dismiss(target)
    /** Repeats whatever failed on this control, with the arguments it originally carried. */
    fun retryOperation(target: String) { operations.retry(target) }
    fun canRetryOperation(target: String) = operations.canRetry(target)
    private val location=LocationAdapter(application)
    private val weather=WeatherRepository()
    private var region: Region?=null
    private var weatherFact: WeatherFact?=null
    /** §7: null until a fix has been attempted at all. */
    private val locationStatusMutable=MutableStateFlow<LocationStatus?>(null)
    val locationStatus=locationStatusMutable.asStateFlow()
    private var contextVersion=0L
    private var contextJob: Job?=null
    /**
     * §7: the id is loaded from disk and only rolls over after 30 minutes of inactivity, so session
     * scores survive a restart instead of resetting every time the app is reopened.
     */
    @Volatile private var sessionId=UUID.randomUUID().toString()
    /**
     * Everything already offered this session. "다른 믹스" re-ranked the same pool with the same
     * deterministic comparator, so it returned the same tracks and looked broken. Remembering what
     * was shown is what makes a re-roll actually roll.
     */
    private val offered=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** FIX-B: every loss of control advances it, so a callback from before cannot clear one after. */
    private val controlEpoch=ControlEpoch()
    /** FIX-B follow-up: which queued slots are still unaccounted for, one by one. */
    private val delivery=DeliveryLedger()
    private suspend fun touchSession() {
        val previous=sessionId
        runCatching { sessionId=prefs.session(System.currentTimeMillis()) { UUID.randomUUID().toString() } }
        // A new session clears the re-roll memory. It deliberately does not clear controlLost:
        // the id rolls over on an inactivity timer, and a timer must not hand the queue back.
        if(sessionId!=previous) { offered.clear(); manualContext = null }
    }
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
    private val listeningMutable=MutableStateFlow(ListeningSummary())
    /** Confirmed listening only. Collection counts never appear here (§20). */
    val listening=listeningMutable.asStateFlow()
    // v2.3 §3, Spotify edition: the pool and playback come from one provider that identifies
    // recordings, so there is no video-to-song matching left to get wrong.
    private val repository = runtime.music
    val settings = prefs.flow.stateIn(viewModelScope,SharingStarted.Eagerly,Settings())
    /** Declared after `settings`: a property initialiser cannot read one defined below it. */
    val schedules = settings.map { CommuteCodec.decode(it.commuteJson) }
        .stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val capabilities = settings.map { CapabilityCodec.decode(it.steeringJson) }
        .stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val rules = dao.rules().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val history = dao.history().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    private val mutable = MutableStateFlow(UiState())
    val ui = mutable.asStateFlow()
    private var selectionJob: Job? = null
    private var accountJob: Job? = null
    private var selectionGeneration = 0
    /** Live check against the runtime config (T14: no rebuild needed after entering a key). */
    val spotifyLinked get() = runtime.spotifyAuth.linked

    init {
        viewModelScope.launch {
            for(edit in edits) { surveyBusyMutable.value=true;try { edit() } catch(e: CancellationException) { throw e } catch(e: Exception) { message(explain(e)) } finally { surveyBusyMutable.value=false } }
        }
        // §3: the bottom notice is auxiliary. The authoritative state lives next to the control
        // that started the work; this only keeps the old global line honest.
        viewModelScope.launch {
            // 0.14.0 wrote this key as JSON. Convert once, verify, and only then keep the new
            // form; a failed conversion leaves the original where it is rather than clearing it.
            val stored = prefs.flow.first().commuteJson
            if (CommuteCodec.isLegacy(stored)) {
                // Convert and check the round trip in memory, then write once. Writing first and
                // checking afterwards means a failed check has to undo a write, and an empty
                // result cannot be told apart from records that simply could not be read.
                val migration = CommuteCodec.migrate(stored)
                val converted = migration.verified
                if (migration.complete && converted != null) prefs.string("commuteSchedules",converted)
                else message("출퇴근 일정 일부를 새 형식으로 옮기지 못해 기존 설정을 그대로 두었어요. 설정에서 확인해 주세요")
            }
        }
        viewModelScope.launch {
            settings.distinctUntilChangedBy { it.connected }.collect { config ->
                val now = System.currentTimeMillis()
                if (!config.connected) {
                    // Mark the end rather than erase: §5 lets a reconnection inside ten minutes
                    // continue the same drive, and that includes where it started.
                    operations.cancel(OperationRegistry.DEPARTURE)
                    if (config.departureAt != 0L && config.departureEndedAt == 0L)
                        prefs.departure(config.departureAt, config.departureZone, now)
                    classifyNow(); return@collect
                }
                val resumable = config.departureAt != 0L &&
                    (config.departureEndedAt == 0L || now - config.departureEndedAt <= ContextFreshness.SESSION_RESUME_MS)
                if (resumable) {
                    // Also the ViewModel-recreated-mid-drive case: there is already a departure,
                    // so taking a fresh fix here would move it to wherever the car has reached.
                    if (config.departureEndedAt != 0L) prefs.departure(config.departureAt, config.departureZone, 0)
                    classifyNow()
                } else captureDeparture()
            }
        }
        viewModelScope.launch {
            operations.flow.collect { ops -> mutable.update { it.copy(working=ops.values.firstOrNull { o -> o.running }?.kind?.verb?.plus("…")) } }
        }
        viewModelScope.launch {
            // FIX-A: the observer is not collecting yet, so nothing can write a new implicit row
            // between the purge and the restart.
            val removed=runCatching { learning.purgeDerivedLearning() }.getOrDefault(0)
            observer.start(viewModelScope,runtime.spotifyRemote.state)
            if(removed>0) message("청취 기반 학습을 보류하면서 기존 관측 학습 기록 ${removed}건을 삭제했습니다. 설문과 직접 평가는 유지합니다")
            touchSession()
            dao.prune(System.currentTimeMillis()-2592000000L);learning.prune(System.currentTimeMillis())
            refreshListening()
            val d=surveyStore.load();draftMutable.value=d
            if(d.completed) { val restored=coordinator.restore(d.revision);mutable.update { it.copy(queue=restored,stale=if(restored.isEmpty()) it.stale else it.stale+StaleReason.RESTORE_UNVERIFIED,engineLabel="저장된 추천 · Spotify 재생") } }
        }
    }
    private fun changeSurvey(transform: (SurveyDraft)->SurveyDraft) {
        if(ui.value.driving) return
        cancelSelection();surveyJob?.cancel();engine.clearCache();markQueueStale(StaleReason.PROFILE_CHANGED)
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
        contextJob?.cancel();contextJob=operations.start(OperationKind.CONTEXT,OperationRegistry.CONTEXT) { op ->
            op.stage(OperationStage.CONNECTING)
            // The repository serves its cache while the five-minute throttle holds, and that cache
            // is identical to a fresh answer in every field but this one.
            val fetchedBefore=weatherFact?.fetchedAt
            val outcome=location.refresh()
            region=outcome.region ?: location.lastKnown()
            // A failed fix does not invalidate a forecast already held for the same region: the
            // two have separate lifetimes (§4). Only a region we actually have can be looked up.
            op.stage(OperationStage.PREPARING)
            outcome.region?.let { weatherFact=weather.get(it) }
            locationStatusMutable.value=outcome.status
            contextVersion++
            cancelSelection();coordinator.invalidate()
            val now=System.currentTimeMillis()
            val fact=weatherFact?.takeIf { f -> region?.let { ContextFreshness.regionUsableForWeather(it.measuredAt,now) && f.usable(it.id,now) }==true }
            fun clock(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)
            mutable.update { it.copy(
                weatherLabel=if(fact==null) "날씨 정보 없음 · 기본 상황으로 추천" else "${fact.source} · ${fact.temperature}°C · ${if(fact.precipitation>0) "강수" else "강수 없음"}${if(fact.stale(now)) " · 오래된 관측" else ""}",
                weatherDetail=if(fact==null) "날씨 없음 · 지역 ${region?.id ?: "미확인"}"
                    else "출처 ${fact.source} · 지역 ${fact.region} · 관측 ${clock(fact.observedAt)} · 조회 ${clock(fact.fetchedAt)} · ${if(fact.stale(now)) "오래된 관측" else "최신"}") }
            val refresh=ContextRefresh.of(fact!=null,fact!=null && fact.fetchedAt!=fetchedBefore)
            // Only a position failure the app observed is named as the cause; a reuse caused by
            // the lookup throttle gets the plain sentence.
            val described=refresh.describe(outcome.status.takeIf { it!=LocationStatus.AVAILABLE }?.advice)
            message(described)
            when(refresh) {
                ContextRefresh.REFRESHED, ContextRefresh.REUSED -> op.confirm(described)
                // There is something actionable to say here, so it is a failure with advice, not a
                // success that happens to have no weather in it.
                ContextRefresh.NONE -> error(outcome.status.advice.ifBlank { refresh.detail })
            }
        }
    }
    /** Registered zones, so the screen shows whether saving actually worked (§24). */
    private val zonesMutable = MutableStateFlow(emptySet<Zone>())
    val registeredZones = zonesMutable.asStateFlow()
    /** Pool size and the last refresh failure, shown on the music service screen. */
    private val poolMutable = MutableStateFlow("후보 확인 전")
    val poolStatus = poolMutable.asStateFlow()
    fun refreshPool() {
        if(operations.start(OperationKind.POOL,OperationRegistry.POOL) { op ->
            op.stage(OperationStage.CONNECTING)
            // Name what each Spotify source returned; "pool empty" alone never says which step failed.
            // Name every precondition separately. "Spotify가 안 돼요" is usually one of four
            // different things, and a single pass/fail hides which.
            val probe = runCatching {
                val me = runtime.spotify.me()
                val product = me?.optString("product").orEmpty()
                val country = me?.optString("country").orEmpty()
                val saved = runCatching { runtime.spotify.savedTracks(5).size }.getOrElse { -1 }
                val top = runCatching { runtime.spotify.topTracks(limit = 5).size }.getOrElse { -1 }
                val search = runCatching { runtime.spotify.search("pop", 5).size }.getOrElse { -1 }
                val missing = runtime.spotifyAuth.missingPlaybackScopes
                val scopeNote = when {
                    runtime.spotifyAuth.grantedScopes.isEmpty() -> ""
                    missing.isEmpty() -> " · 재생 권한 있음"
                    // The stored authorization predates the scope; only re-linking can add it.
                    else -> " · 재생 권한 없음(${missing.joinToString(",")}) · 연결을 해제하고 다시 연결해 주세요"
                }
                "계정 " + (product.ifBlank { "확인 불가" }) + (if (country.isBlank()) "" else " · $country") +
                    " · 저장 $saved · 인기 $top · 검색 $search" + scopeNote +
                    (if (product == "premium") "" else " · 곡 지정 재생에는 Premium이 필요해요")
            }.getOrElse { "Spotify 조회 실패: " + (it.message ?: it::class.simpleName) }
            val before = dao.candidateCount(0)
            val outcome = runCatching { repository.refreshReport(settings.value) }
            val after = dao.candidateCount(0)
            poolMutable.value = outcome.fold(
                onSuccess = { "후보 ${after}곡 · " + it.describe() },
                onFailure = { "후보 ${after}곡 · $probe · 실패: " + (it.message ?: it::class.simpleName) }
            )
            message(poolMutable.value)
            // The count is what survived, not what this refresh achieved. Confirming on the count
            // reported a failed refresh as a success whenever the old pool was still there.
            when(val result = PoolRefresh.of(before, after, outcome.exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "조회 실패" })) {
                is PoolRefresh.Refreshed -> op.confirm(result.detail)
                is PoolRefresh.Failed -> error(result.detail)
            }
        }==null) message("이미 처리 중이에요")
    }
    fun refreshZones() { zonesMutable.value = runCatching { location.registeredZones() }.getOrDefault(emptySet()) }
    fun registerZone(zone: Zone) { if(ui.value.driving) return;contextJob?.cancel()
        contextJob=operations.start(OperationKind.LOCATION,OperationRegistry.zone(zone.name)) { op ->
            op.stage(OperationStage.CONNECTING)
            val status = location.register(zone)
            refreshZones()
            val where = if (zone == Zone.HOME) "집" else "회사"
            // UX03: the confirmation is the stored zone coming back, not the call returning.
            if(status==LocationStatus.AVAILABLE && zone in location.registeredZones()) op.confirm("${where}을(를) 등록했어요")
            else error("등록하지 못했어요 · ${status.advice}")
        }
    }
    fun deleteZones() { if(ui.value.driving) return;contextJob?.cancel();location.deleteZones();region=null;weatherFact=null;viewModelScope.launch { prefs.departure(0,Zone.UNKNOWN.name,0) };weather.clear();contextVersion++;cancelSelection();viewModelScope.launch { coordinator.invalidate() };message("등록 영역을 삭제했습니다") }
    fun rate(track: Track, positive: Boolean) {
        if(ui.value.driving || ui.value.demo) return
        cancelSelection()
        viewModelScope.launch {
            val id="explicit:$sessionId:${track.id}"
            db.withTransaction { val version=(intelligence.outcome(id)?.version?:0)+1;intelligence.acceptOutcome(OutcomeEntity(id,track.id,sessionId,version,if(positive) 1.0 else -1.0,true,System.currentTimeMillis())) }
            refreshListening()
            coordinator.invalidate()
            // FIX-A item 5: stored, but nothing in direct-input mode consumes it yet. Saying it is
            // applied would be the same overclaim the mode exists to stop making.
            message(if(Policy.DIRECT_INPUT_ONLY) "평가를 기록했습니다. 현재 선곡 모드에는 아직 반영되지 않습니다" else "명시적 평가를 다음 추천에 반영합니다")
        }
    }
    private fun refreshListening() { viewModelScope.launch { listeningMutable.value=runCatching { learning.summary() }.getOrDefault(ListeningSummary()) } }
    /**
     * The one skip whose cause the app can prove (§7). The command is logged before it is sent, so
     * the track change that follows is attributed to it rather than guessed at.
     *
     * It plays the app's own next slot rather than calling skipNext. Spotify's next is whatever is
     * next in Spotify's queue, which is only the same thing when nothing has interfered — and if
     * something has interfered, that is exactly when the two differ.
     */
    fun skipCurrent() {
        if(ui.value.demo) { message("데모 곡은 재생할 수 없습니다"); return }
        if(playbackJob?.isActive == true) { message("재생 요청을 처리하는 중이에요"); return }
        val queue = ui.value.queue
        val current = runtime.spotifyRemote.state.value?.trackId
        val index = queue.indexOfFirst { it.id == current }
        when {
            // Only reasons a confirmed playback cannot clear block the next button. A queue merely
            // waiting to be re-verified is unblocked by playing from it, which is what this does.
            ui.value.stale.any { it.fixedByReselection } ->
                message("목록이 현재 조건과 달라요. 다시 선곡한 뒤 이어서 들어 주세요")
            settings.value.controlLost ->
                message("자동 선곡이 멈춘 상태예요. 목록에서 곡을 선택하면 다시 시작합니다")
            // Not our playback: advancing would hand the driver whatever Spotify queued.
            index < 0 ->
                message("재생 중인 곡이 목록에 없어요. 목록에서 곡을 선택해 주세요")
            index == queue.lastIndex ->
                message("목록의 마지막 곡이에요. 다음 묶음을 준비한 뒤 이어집니다")
            else -> launchPlayback(queue[index + 1], markSkip = true)
        }
    }
    fun resetLearning() { if(ui.value.driving) return;cancelSelection();surveyJob?.cancel();engine.clearCache();viewModelScope.launch { db.withTransaction { intelligence.clearOutcomes();intelligence.clearBatches();intelligence.clearEvents();intelligence.clearAttempts();intelligence.clearAnalysis() };progress=DiscoveryProgress();refreshListening();message("학습 기록을 초기화했습니다. 설문은 유지합니다") } }


    fun page(page: String) {
        if (ui.value.driving && page != "홈") { message("설정과 탐색은 정차 후 이용해 주세요"); return }
        mutable.update { it.copy(page = page) }
    }
    fun message(text: String?) { mutable.update { it.copy(message=text) } }

    fun onboard(demo: Boolean) { viewModelScope.launch { prefs.flag("onboarded",true); mutable.update { it.copy(demo=demo,context=if(demo) DriveContext.COMMUTE_HOME else DriveContext.GENERAL_DRIVE) }; if (demo) recommend() } }
    fun demo(value: Boolean) { cancelSelection();progress=DiscoveryProgress(); mutable.update { it.copy(demo=value,queue=emptyList(),notices=emptyList(),connection=if(value) "데모 · 계정 미연결" else connectionLabel(settings.value)) } }
    fun driving(value: Boolean) { if(value) { cancelSelection();contextJob?.cancel() }; mutable.update { it.copy(driving=value,page="홈") } }
    fun auto(value: Boolean) { if (ui.value.driving) return; viewModelScope.launch { prefs.flag("auto",value);message(if(value) "차량 연결 시 알림을 켰습니다" else "차량 연결 알림을 껐습니다") } }
    fun ratio(value: Float) { if(ui.value.driving) return;cancelSelection();markQueueStale(StaleReason.RULE_CHANGED);viewModelScope.launch { coordinator.invalidate();prefs.ratio(value);message("새 노래 비율을 ${(value*100).toInt()}%로 바꿨습니다") } }
    /**
     * CTX05. Choosing a mood used to call suspendAgent, which stopped automatic selection for
     * thirty minutes. Picking a context is a statement about what to play, not a request for the
     * app to stop choosing; the two were conflated because both invalidate the current plan.
     * The choice is held for this drive and automation carries on.
     */
    fun choose(context: DriveContext) {
        if (ui.value.driving) return
        cancelSelection()
        contextVersion++
        manualContext = context
        viewModelScope.launch { coordinator.invalidate() }
        mutable.update { it.copy(context=context) }
        recommend()
    }
    /** Held for this drive. A new session clears it; a reconnection within the session does not. */
    @Volatile private var manualContext: DriveContext? = null

    /** §8.5 — sign-in never appears while driving; Spotify consent runs in the browser when parked. */
    fun linkAccount(interactive: Boolean = true) {
        message("설정 화면의 Spotify 연결을 사용해 주세요")
    }

    /** Called after the Spotify redirect so the pool is built from the account straight away. */
    fun onSpotifyLinked() {
        cancelSelection()
        viewModelScope.launch {
            coordinator.invalidate()
            mutable.update { it.copy(demo = false, connection = "Spotify 연결됨") }
            message("연결했습니다. 저장한 곡과 자주 듣는 곡을 불러오는 중이에요")
            runCatching { repository.refresh(settings.value) }
                .onSuccess { message("취향을 불러왔습니다") }
                .onFailure { message(explain(it)) }
        }
    }

    fun recommend() {
        if(survey.value?.completed!=true) return
        if(ui.value.driving) { message("정차 후 선곡을 시작해 주세요"); return }
        cancelSelection()
        val generation = selectionGeneration
        // UX03: the operation only reports success once a batch has actually been committed.
        // A stale generation settles as UNKNOWN, which is what it is: the conditions moved while
        // the request was in the air and nobody can say what the user would have got.
        selectionJob = operations.start(OperationKind.SELECTION,OperationRegistry.SELECTION) { op ->
            op.stage(OperationStage.PREPARING)
            when(val outcome = runSelection(generation, append = false, baseRevision = null)) {
                PrepareOutcome.Prepared -> op.confirm("${ui.value.queue.size}곡을 준비했어요")
                is PrepareOutcome.RetryableFailure -> error(outcome.reason)
                is PrepareOutcome.Exhausted -> error(outcome.reason)
                // Every branch is stated. SELECTION requires confirmation, so a branch that
                // forgot to say what happened would settle UNKNOWN rather than quietly succeed.
                PrepareOutcome.Stale -> op.discard("조건이 바뀌어 이번 결과는 적용하지 않았어요 · 다시 골라 주세요")
            }
        }
    }

    /**
     * Phase 1 §6. Called by the scheduler when the last track of the current batch starts, so the
     * next three are chosen with the first two tracks' outcomes already counted and reach Spotify's
     * queue before the current track ends. Unlike the manual path this runs while driving: the
     * driver asked for nothing, which is the whole point.
     */
    private suspend fun prepareNextBatch(baseRevision: Long): PrepareOutcome {
        if(survey.value?.completed!=true || ui.value.demo || !spotifyLinked) return PrepareOutcome.Stale
        if(settings.value.suspendedUntil>System.currentTimeMillis()) return PrepareOutcome.Stale
        // R09: automation does not resume on its own after losing the queue.
        if(settings.value.controlLost) return PrepareOutcome.Stale
        return runSelection(selectionGeneration, append = true, baseRevision = baseRevision)
    }

    private suspend fun runSelection(generation: Int, append: Boolean, baseRevision: Long?): PrepareOutcome {
            touchSession()
            if(!append) mutable.update { it.copy(busy=true) }
            val snapshot = ui.value
            val draft=survey.value?:return PrepareOutcome.Stale
            val revision=draft.revision
            // Appending must not repeat what is already queued or still playing. A manual re-roll
            // must not repeat what this session has already been offered, or it is not a re-roll.
            var excluded = if(append) snapshot.queue.map { it.id }.toSet()
                else synchronized(offered) { offered.toSet() } + snapshot.queue.map { it.id }
            val carried = if(append) snapshot.queue.takeLast(Policy.BATCH_SIZE) else emptyList()
            val capturedEpoch = controlEpoch.current
            try {
                val config = prefs.flow.first()
                val ruleSnapshot = dao.rules().first().map { it.domain() }
                val effective = RuleEngine.resolve(ruleSnapshot,snapshot.context,config.ratio.toDouble())
                val tracks = if(snapshot.demo) DemoTracks else {
                    check(spotifyLinked) { "설정에서 Spotify 계정을 연결해 주세요" }
                    if(seedRevision!=revision) {
                        try { repository.addSurveyCandidates(draft.answers);seedRevision=revision } catch(e: CancellationException) { throw e } catch(_: Exception) { /* Existing pool remains usable. */ }
                    }
                    repository.candidates(snapshot.context, config)
                }
                val p=profile();val constraints=Constraints(excludedGenres=p.exclusions)
                // Reduced mode reads no learned scores at all: not blocking new writes only, but
                // not reading the stored ones either (III.13).
                val scores = if(Policy.DIRECT_INPUT_ONLY) emptyMap() else learning.scores(sessionId,System.currentTimeMillis())
                // Recognisability belongs in the cut to forty too: a well-known track that never
                // reaches the shortlist can never be chosen from it.
                fun rank(pool: List<Track>) =
                    if(Policy.DIRECT_INPUT_ONLY) pool.filter { it.id !in excluded && constraints.allows(it) }.distinctBy { it.id }
                    else TasteRanker.prepare(pool.filter { it.id !in excluded },p,constraints,scores).filter { effective.allowsEnergy(it) }
                        .sortedByDescending { it.affinity + Policy.RECOGNISABILITY_WEIGHT*it.recognisability - it.fatigue }.take(40)
                var prepared = rank(tracks)
                if (prepared.isEmpty() && !snapshot.demo && offered.isNotEmpty() && !append) {
                    // Every remaining candidate has already been offered. Starting the rotation over
                    // is the right answer; refusing to play anything is not.
                    offered.clear()
                    excluded = snapshot.queue.map { it.id }.toSet()
                    prepared = rank(tracks)
                }
                if (prepared.isEmpty() && !snapshot.demo) {
                    // SEL04: top up once, then re-read and re-rank inside the same request. Never loop.
                    val report = runCatching { repository.refreshReport(config) }.getOrNull()
                    val refilled = repository.candidates(snapshot.context, config)
                    prepared = rank(refilled)
                    if (prepared.isEmpty()) {
                        val reason = when {
                            refilled.isEmpty() -> "Spotify에서 가져온 후보가 없어요 · " + (report?.describe() ?: "조회 실패")
                            else -> "후보 ${refilled.size}곡이 모두 확인된 제외 조건에 걸렸어요. 설문의 제외 장르를 확인해 주세요"
                        }
                        throw PoolExhausted(reason)
                    }
                }
                val direct=if(Policy.DIRECT_INPUT_ONLY) DirectInputSelector.select(prepared,constraints,sessionId,excluded) else null
                val fallback=direct?.tracks ?: SessionRanker.select(prepared,effective,progress)
                // FIX-A: not read, not merely unused. Both of these are derived from observation.
                val outcomes=if(Policy.SPOTIFY_BEHAVIOR_LEARNING_ALLOWED) intelligence.outcomes().filter { it.sessionId==sessionId }.sortedBy { it.createdAt }.map(learning::outcome) else emptyList()
                val version=QueueVersion(sessionId,generation.toLong(),revision,contextVersion,outcomes.maxOfOrNull { it.version }?:0,UUID.randomUUID().toString())
                coordinator.begin(version)
                val now=System.currentTimeMillis();val lastFix=region
                // The zone is where the car is *now*, so it expires in two minutes. The weather
                // region is an ~11 km cell, so it does not — and a valid forecast for that cell no
                // longer dies just because the fix behind it aged out.
                val weatherRegion=lastFix?.takeIf { ContextFreshness.regionUsableForWeather(it.measuredAt,now) }
                val validWeather=weatherRegion?.let { r -> weatherFact?.takeIf { it.usable(r.id,now) } }
                // §9: purpose, time band, distance mode and a trustworthy local weather summary are
                // the whole permitted payload. The registered zone names used to go out here; they
                // are home and workplace by another name and the engine has no use for them.
                val judged=ui.value.assessment
                val semantic=JSONObject()
                    .put("purpose",(judged?.purpose ?: ContextPurpose.UNKNOWN).name)
                    .put("basis",(judged?.basis ?: ContextBasis.ESTIMATED).name)
                    .put("timeBand",(judged?.timeBand ?: TimeBand.DAY).name)
                    .put("distanceMode",(judged?.distanceMode ?: DistanceMode.NORMAL).name)
                validWeather?.let { semantic.put("weather",JSONObject().put("temperature",it.temperature).put("precipitation",it.precipitation).put("stale",it.stale(now))) }
                val novelty=if(snapshot.demo || !Policy.SPOTIFY_BEHAVIOR_LEARNING_ALLOWED) emptyMap() else runCatching { ai.drivemuse.app.catalog.NoveltyAnnotator(db.catalog()).annotate(prepared,emptySet(),runtime.historyCoverageSince()) }.getOrDefault(emptyMap())
                val selection=engine.select(draft.aiConsent && !snapshot.demo,prepared,fallback,p,semantic,outcomes,constraints,effective.discovery,progress,version,0,(0 until Policy.BATCH_SIZE).toList(),novelty,MixTarget.resolve(p))
                val queue=selection.tracks
                if(generation!=selectionGeneration || survey.value?.revision!=revision) return PrepareOutcome.Stale
                // Saying "adjust your rules" is wrong when the pool itself is empty, which is the
                // common case right after switching providers.
                check(queue.isNotEmpty()) {
                    val pool = tracks.size
                    when {
                        pool == 0 -> "후보가 비어 있어요 · 설정 → 음악 서비스에서 ‘후보 다시 불러오기’를 눌러 주세요" + (repository.lastError?.let { " ($it)" } ?: "")
                        effective.energyCeiling < 1.0 -> "잔잔한 곡 조건에 맞는 후보가 없습니다. 규칙을 조정해 주세요"
                        else -> "후보 ${pool}곡 중 조건을 통과한 곡이 없습니다. 규칙과 제외 조건을 확인해 주세요"
                    }
                }
                // FIX-C: the revision and epoch are checked BEFORE the commit, not after it. The
                // old order stored a plan built on a queue that had already moved and only then
                // noticed, leaving a READY row nobody wanted.
                if(append && !scheduler.accepts(baseRevision!!)) return PrepareOutcome.Stale
                if(append && !controlEpoch.stillCurrent(capturedEpoch)) return PrepareOutcome.Stale
                if(generation!=selectionGeneration || survey.value?.revision!=revision) return PrepareOutcome.Stale
                if(!snapshot.demo && !coordinator.commit(version,queue,prepared,constraints)) return PrepareOutcome.Stale
                if(append && !scheduler.accepts(baseRevision!!)) return PrepareOutcome.Stale
                progress=progress.append(queue)
                offered.addAll(queue.map { it.id })
                if(append) appendToPlayer(carried,queue)
                val notices = buildList {
                    // Direct-input mode does not read the genre-approximated energy, so a
                    // quiet/lively rule has nothing to act on. Dropping it silently would
                    // misreport the user's own rule as applied.
                    direct?.takeIf { it.short }?.let { add("조건을 통과한 후보가 ${it.eligible}곡이라 ${queue.size}곡만 준비했어요 · 제외 조건을 확인하거나 후보를 더 불러와 주세요") }
                    if(direct!=null && effective.energyCeiling<1.0) add("직접 입력 모드에서는 ‘잔잔하게’ 같은 세기 규칙을 적용할 수 없어요 · 이 곡들에는 반영되지 않았습니다")
                    if(direct?.capRelaxed==true) add("한 아티스트 ${Policy.MAX_PER_ARTIST}곡 제한을 지킬 만큼 후보가 다양하지 않아 제한을 완화했어요")
                    if("NOVEL_POOL_SHORTAGE" in selection.unmet) add("아직 듣지 않은 후보가 부족해 익숙한 곡의 비중이 높아요")
                    if("INSUFFICIENT_CANDIDATES" in selection.unmet) add("조건을 통과한 후보 자체가 적어요 · 설정에서 후보를 더 불러와 주세요")
                    if("EXCLUSION_CONFLICT" in selection.unmet) add("설문의 제외 조건끼리 충돌해 일부 조건만 적용했어요 · 제외 장르를 확인해 주세요")
                    if("REQUIRED_FEATURE_UNAVAILABLE" in selection.unmet) add("요청한 특성을 확인할 수 있는 후보가 없어 그 조건은 적용하지 못했어요")
                    if(validWeather==null && !snapshot.demo) add("날씨를 확인하지 못해 날씨 조건 없이 골랐어요")
                }
                mutable.update { it.copy(queue=if(append) carried+queue else queue,stale=emptySet(),engineLabel=if(direct!=null) "설문 조건 · 직접 입력 선곡" else selection.label,connection=if(snapshot.demo) "데모 · 계정 미연결" else connectionLabel(config),reason=reasonLine(snapshot.context,effective,direct,selection.adjustment),notices=notices) }
                dao.putHistory(HistoryEntity(UUID.randomUUID().toString(),snapshot.context.name,snapshot.context.mix,queue.size,System.currentTimeMillis(),demo=snapshot.demo))
                // The notices stay on the card instead of racing each other through one snackbar.
                return PrepareOutcome.Prepared
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                // FIX-C: the append path used to swallow this entirely, so a failing automatic
                // top-up looked exactly like a working one.
                message(explain(e))
                return PrepareOutcome.RetryableFailure(e.message ?: e::class.simpleName ?: "알 수 없는 오류")
            }
            finally { if(!append && generation == selectionGeneration) mutable.update { it.copy(busy=false) } }
    }

    /**
     * Sends the new batch to Spotify's queue. Spotify offers no way to remove a queued item, so a
     * track that lands here is locked (§6): the batch is only ever sent once, at the moment the
     * last track of the previous batch starts.
     */
    private suspend fun appendToPlayer(carried: List<Track>, queue: List<Track>) {
        observer.plan(null, carried + queue)
        var queued = 0
        val uncertain = mutableListOf<String>()
        for (track in queue) when (runtime.spotifyRemote.queue(track.id)) {
            is ai.drivemuse.app.spotify.DispatchResult.Accepted -> queued++
            is ai.drivemuse.app.spotify.DispatchResult.Unknown -> uncertain += track.id
            is ai.drivemuse.app.spotify.DispatchResult.Rejected -> Unit
        }
        delivery.dispatched(uncertain)
        val unclear = uncertain.size
        if (unclear > 0) mutable.update { it.copy(stale=it.stale+StaleReason.DELIVERY_UNCERTAIN) }
        message(when {
            queued == queue.size -> "다음 ${queued}곡을 이어서 준비했어요"
            // R02/§7: an unconfirmed item is not a waiting track. Say so rather than count it.
            unclear > 0 -> "다음 ${queued}/${queue.size}곡 확인 · ${unclear}곡은 결과 불명"
            else -> "다음 ${queued}/${queue.size}곡만 준비했어요 · Spotify 연결을 확인해 주세요"
        })
    }

    /** §6.8 — every failure has one recovery path and only re-auth is worth surfacing. */
    /**
     * §4: say what was actually applied. Direct-input mode does not read the discovery ratio at
     * all, so printing "새 노래 목표 40%" over a batch that never consulted it reported an
     * unapplied setting as an achievement.
     */
    private fun reasonLine(context: DriveContext, effective: EffectiveRules, direct: DirectInputSelector.Outcome?, adjustment: String): String {
        if(direct!=null) {
            val applied=buildList {
                add("설문의 제외 조건")
                add(if(direct.capRelaxed) "아티스트 분산(완화)" else "아티스트 분산")
                if(direct.seeded>0) add("직접 입력한 이름 ${direct.seeded}곡")
            }
            return "${context.label} · ${applied.joinToString(" · ")}을 적용했어요"
        }
        return "${context.label} · 새 노래 목표 ${(effective.discovery*100).toInt()}% · "+when(adjustment) {
            "REDUCE_RECENT_SKIP"->"최근 넘긴 곡을 피해서 골랐어요"
            "FAVOR_SUPPORTED_FEATURE"->"반응이 좋았던 특성을 우선했어요"
            "EXPLORE_ALTERNATIVE"->"다른 방향의 곡을 섞었어요"
            else->"설정된 취향을 바탕으로 골랐어요"
        }
    }
    private fun explain(e: Throwable): String = when (e) {
        is QuotaExceededException -> "오늘의 조회 한도를 모두 사용했습니다. 저장된 후보로 계속 재생할 수 있어요"
        is ai.drivemuse.app.spotify.SpotifyAuthRequired -> "Spotify 계정 연결을 다시 확인해 주세요"
        is ai.drivemuse.app.spotify.SpotifyPremiumRequired -> "곡 지정 재생에는 Spotify Premium이 필요해요"
        is ApiNotConfiguredException -> e.message ?: "API 설정을 확인해 주세요"
        is IllegalStateException, is IllegalArgumentException -> e.message ?: "추천 실패"
        else -> "연결할 수 없습니다. 기존 음악은 그대로 유지합니다"
    }
    private fun connectionLabel(s: Settings) = when {
        !spotifyLinked -> "Spotify 미연결"
        runtime.spotifyRemote.connected -> "Spotify 연결됨 · 재생 제어"
        else -> "Spotify 연결됨"
    }

    private fun cancelSelection() { selectionGeneration++; selectionJob?.cancel(); selectionJob=null; viewModelScope.launch { scheduler.reset() }; mutable.update { it.copy(busy=false) } }
    /**
     * QUE01: rules and taste changed, so the visible list no longer matches the conditions. It is
     * marked, not cleared: playing a track from it is still allowed and still checks exclusions,
     * and a failed re-selection leaves the user with the list they had.
     */
    private fun markQueueStale(reason: StaleReason) { if(ui.value.queue.isNotEmpty()) mutable.update { it.copy(stale=it.stale+reason) } }
    fun suspendAgent() { cancelSelection(); controlEpoch.advance(); viewModelScope.launch { coordinator.invalidate();observer.release();prefs.suspendUntil(System.currentTimeMillis()+30*60*1000); message("30분 동안 사용자 선택을 유지합니다") } }

    /**
     * R09, §7. Something the app did not queue is playing. The app stands down rather than trying
     * to win the player back: re-sending play or skip here is the loop that fights the driver.
     *
     * Deliberately writes no outcome. A recording nobody planned is evidence about control, not
     * about taste, and the policy boundary keeps Spotify observations out of learning anyway.
     */
    private suspend fun onUnplannedPlayback(trackId: String) {
        if (ui.value.demo) return
        if (settings.value.controlLost) return
        cancelSelection()
        scheduler.reset()
        coordinator.invalidate()
        // No expiry. Waiting out a clock is not consent, so the flag is cleared by an explicit
        // request to play or by a new drive session, never by time passing.
        controlEpoch.advance()
        delivery.clear()
        prefs.flag("controlLost", true)
        mutable.update { it.copy(stale=it.stale+StaleReason.CONTROL_LOST) }
        message("목록에 없는 곡이 재생돼 자동 선곡을 멈췄어요. 목록에서 곡을 선택하면 다시 시작합니다")
    }

    /**
     * Called only after the intended recording is confirmed playing. Asking is not regaining: if
     * the start failed or its result was unclear, the app does not know what is in the queue and
     * has no business filling it.
     */
    /**
     * A planned recording started. Anything past the first slot came out of the queue the app sent,
     * which is the only evidence that those commands actually landed — DELIVERY_UNCERTAIN is raised
     * on an unclear queue result and can be cleared by nothing else.
     */
    private suspend fun onPlannedStart(trackId: String, ordinal: Int, plannedSize: Int) {
        // Only the slot actually observed is settled. One track arriving proves that one command
        // landed and nothing about the others, so the flag lifts when the last of them is seen.
        if (delivery.observed(trackId) && StaleReason.DELIVERY_UNCERTAIN in ui.value.stale) {
            mutable.update { it.copy(stale=it.stale-StaleReason.DELIVERY_UNCERTAIN) }
        }
        scheduler.onStarted(trackId, ordinal, plannedSize)
    }

    /** The driver asked for playback or a new session began: automation may own the queue again. */
    private suspend fun regainControl(capturedEpoch: Long) {
        // FIX-B: a start confirmed after a fresh intervention is confirming the wrong world. The
        // late callback is ignored rather than allowed to hand the queue back.
        if (!controlEpoch.stillCurrent(capturedEpoch)) return
        // Read the store rather than the StateFlow: this runs during init, before the flow has
        // necessarily emitted, and a stale `false` there would silently keep the stop in place.
        if (runCatching { prefs.flow.first().controlLost }.getOrDefault(false)) prefs.flag("controlLost", false)
        mutable.update { it.copy(stale=it.stale.filterNot { r -> r.clearedByConfirmedPlayback }.toSet()) }
    }

    /** One in-flight playback request at a time: a double tap must not queue the batch twice (QUE02). */
    private var playbackJob: Job? = null

    /**
     * §30, PLAY01/02, QUE02: play the selected recording, confirm that it actually started, then
     * append only the tracks that follow it in the batch. App Remote is the only transport (FIX-D).
     */
    fun handoff(track: Track?) {
        if (track == null) { message("재생할 곡이 없습니다"); return }
        if (!ai.drivemuse.app.spotify.SpotifyIds.isTrackId(track.id)) { message("예전 목록의 곡이에요. 설정에서 후보를 새로 불러와 주세요"); return }
        if (!Constraints(excludedGenres = profile().exclusions).allows(track)) { message("현재 제외 조건에 맞지 않는 곡입니다"); return }
        if (playbackJob?.isActive == true) { message("재생 요청을 처리하는 중이에요"); return }
        launchPlayback(track, markSkip = false)
    }

    /**
     * The single place a recording is started. Both the list tap and the next button come through
     * here, so the double-tap guard, the plan registration and the start confirmation are shared
     * rather than reimplemented per entry point (QUE02).
     */
    private fun launchPlayback(track: Track, markSkip: Boolean) {
        val batch = ui.value.queue
        // FIX-B: the rules may have moved while control was lost, so what follows is re-checked
        // here rather than trusted because it was valid when the batch was built. Both entry
        // points go through this same check.
        val constraints = Constraints(excludedGenres = profile().exclusions)
        val planned = batch.dropWhile { it.id != track.id }.drop(1).filter { it.id != track.id }.distinctBy { it.id }
        val following = planned.filter { constraints.allows(it) }
        val dropped = planned.size - following.size
        val capturedEpoch = controlEpoch.current
        playbackJob = viewModelScope.launch {
          // A hard ceiling on the whole request: nothing here may leave the button locked.
          val finished = kotlinx.coroutines.withTimeoutOrNull(60_000) {
            message("Spotify에 연결하는 중…")
            // Before plan(), so the close of the attempt now ending carries the command that caused it.
            if (markSkip) observer.commandedSkip()
            // Registered before the command so the very first callback for this track is observed.
            // Only what the app queued counts; Spotify's own autoplay never scores (§4).
            observer.plan(null, listOf(track) + following); scheduler.reset()
            val remote = runtime.spotifyRemote
            val connectFailure = remote.connect(getApplication())
            val start = if (connectFailure != null) ai.drivemuse.app.spotify.StartResult.Failed(connectFailure)
                        else remote.playAndConfirm(track.id)
            when (start) {
                is ai.drivemuse.app.spotify.StartResult.Confirmed -> Unit
                // FIX-D: the Web API fallback is gone. Choosing a target used to fall through to
                // the first device in the list, which on a phone in a car reaches a desktop that
                // happens to be awake, and nothing in that API identifies this handset. Without a
                // device we can verify, App Remote is the only transport this build supports.
                is ai.drivemuse.app.spotify.StartResult.Failed -> {
                    message(start.reason + " · 이 버전은 휴대전화의 Spotify 앱을 통해서만 재생해요")
                    return@withTimeoutOrNull false
                }
                // The command may already be playing. Re-sending it would restart the track.
                is ai.drivemuse.app.spotify.StartResult.Indeterminate -> {
                    message(start.reason + " · 명령 결과가 불명확해 같은 곡을 다시 보내지 않았어요. Spotify 앱 상태를 확인해 주세요")
                    return@withTimeoutOrNull false
                }
            }
            // Exposure only (§17 EXPOSED_ONLY); the listening outcome comes from observation.
            if (!ui.value.demo) repository.recordPlay(track.id)
            var queued = 0
            val uncertain = mutableListOf<String>()
            for (next in following) when (remote.queue(next.id)) {
                is ai.drivemuse.app.spotify.DispatchResult.Accepted -> queued++
                // Re-queueing on Unknown is how the same track lands twice (§7).
                is ai.drivemuse.app.spotify.DispatchResult.Unknown -> uncertain += next.id
                is ai.drivemuse.app.spotify.DispatchResult.Rejected -> Unit
            }
            // A new dispatch replaces the ledger, so a track the driver picked from the list
            // cannot settle slots that belonged to an earlier plan.
            delivery.dispatched(uncertain)
            val unclear = uncertain.size

            // R09: the request was not the recovery. A confirmed start of the intended recording
            // is. On failure or an unclear result the app stays out of the queue.
            regainControl(capturedEpoch)
            // Raised after recovery, not before: control is back, but what Spotify did with the
            // queue commands is still unknown and only observing a later track can settle it.
            if (unclear > 0) mutable.update { it.copy(stale=it.stale+StaleReason.DELIVERY_UNCERTAIN) }
            refreshListening()
            message("${track.artist} ${track.title} 재생 시작" +
                (if (following.isEmpty()) "" else " · 이어서 ${queued}/${following.size}곡 대기" + (if (unclear > 0) " · ${unclear}곡 결과 불명" else "")) +
                (if (dropped > 0) " · 조건에 맞지 않는 ${dropped}곡은 보내지 않았어요" else ""))
            true
          }
          if (finished == null) message("재생 요청이 60초 안에 끝나지 않아 중단했어요. Spotify 앱 상태를 확인해 주세요")
        }
    }

    fun parseRule(text: String) {
        if(ui.value.driving) return
        val parsed = RuleEngine.parse(text.take(240),UUID.randomUUID().toString(),System.currentTimeMillis())
        if (parsed==null) message("‘퇴근길/출근길/여행/야간’, ‘잔잔하게·신나게’, ‘새 노래 30%’ 중 하나는 포함해 주세요")
        else mutable.update { it.copy(pendingRule=parsed) }
    }
    fun confirmRule(save: Boolean) { if(save) { cancelSelection();markQueueStale(StaleReason.RULE_CHANGED) }; val r=ui.value.pendingRule; mutable.update { it.copy(pendingRule=null) }; if(save && r!=null && !ui.value.driving) viewModelScope.launch { coordinator.invalidate();dao.putRule(RuleEntity.from(r));message("규칙을 저장했습니다. 다음 선곡부터 적용됩니다") } }
    fun deleteRule(id: String) { cancelSelection();markQueueStale(StaleReason.RULE_CHANGED); if (!ui.value.driving) viewModelScope.launch { coordinator.invalidate();dao.deleteRule(id);message("규칙을 삭제했습니다") } }
    fun toggleRule(rule: RuleEntity) { cancelSelection();markQueueStale(StaleReason.RULE_CHANGED); if(!ui.value.driving) viewModelScope.launch { coordinator.invalidate();dao.putRule(rule.copy(enabled=!rule.enabled));message(if(rule.enabled) "규칙을 껐습니다" else "규칙을 켰습니다") } }
    fun feedback(id: String, feedback: String) { if(!ui.value.driving) viewModelScope.launch { dao.feedback(id,feedback);message("의견을 기록했습니다") } }
    fun registerVehicle(id: String,name: String) { if(ui.value.driving) return; viewModelScope.launch { prefs.string("vehicleId",id); prefs.string("vehicleName",name); prefs.flag("connected",false); message("차량을 등록했습니다. 다음 연결부터 감지합니다") } }
    /**
     * §2: the estimate no longer collapses to GENERAL_DRIVE below an arbitrary .8. That threshold
     * was throwing away a correct commute reading because a weighted sum of hand-picked constants
     * came to .75, and the number itself was then shown as "확신도 75%" as though it were measured.
     * The judgement stands on its stated evidence, and separately does not authorise playback —
     * the playback controller checks connection, stop state and epoch for itself.
     */
    /**
     * [origin], [scheduleList] and [departedAt] override what the stored settings say, for the
     * moment just after a write when DataStore has not yet emitted the new value. Passing the
     * values that were verified on disk beats assessing against a flow that is still catching up.
     */
    fun classifyNow(origin: Zone? = null, scheduleList: List<CommuteSchedule>? = null, departedAt: Long? = null) {
        val config = settings.value
        val manual = manualContext
        val departure = departedAt ?: config.departureAt.takeIf { it != 0L }
        val assessment = ContextEstimator.assess(ContextInput(
            connected = config.connected,
            at = ZonedDateTime.now(),
            manual = manual?.let { purposeOf(it) },
            originZone = origin ?: runCatching { Zone.valueOf(config.departureZone) }.getOrDefault(Zone.UNKNOWN),
            departedAt = departure?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()) },
            schedules = scheduleList ?: schedules.value,
            nightStart = LocalTime.ofSecondOfDay(config.nightStartMinutes * 60L),
            nightEnd = LocalTime.ofSecondOfDay(config.nightEndMinutes * 60L),
            assessedAt = System.currentTimeMillis(),
            contextRevision = contextVersion))
        // A manual "야간 드라이브" is kept as chosen; night is a time attribute in the assessment
        // and the projection would otherwise drop it whenever the clock disagreed.
        mutable.update { it.copy(context=manual ?: assessment.driveContext,assessment=assessment,reason=assessment.describe()) }
    }
    private fun purposeOf(context: DriveContext) = when (context) {
        DriveContext.COMMUTE_TO_WORK -> ContextPurpose.COMMUTE_TO_WORK
        DriveContext.COMMUTE_HOME -> ContextPurpose.COMMUTE_HOME
        DriveContext.TRAVEL -> ContextPurpose.TRAVEL
        DriveContext.NIGHT_DRIVE, DriveContext.GENERAL_DRIVE -> ContextPurpose.GENERAL
        DriveContext.UNKNOWN -> ContextPurpose.UNKNOWN
    }

    /**
     * §5: records departureAt and takes one fix for the origin zone. A fix arriving outside the
     * two-minute window describes where the car has got to, not where it set off, so it is not
     * back-dated into the departure.
     */
    private fun captureDeparture() {
        val startedAt = System.currentTimeMillis()
        operations.start(OperationKind.LOCATION,OperationRegistry.DEPARTURE) { op ->
            prefs.departure(startedAt, Zone.UNKNOWN.name, 0)
            classifyNow(origin = Zone.UNKNOWN, departedAt = startedAt)
            op.stage(OperationStage.CONNECTING)
            val outcome = location.refresh()
            locationStatusMutable.value = outcome.status
            val now = System.currentTimeMillis()
            val offered = outcome.region?.takeIf { ContextFreshness.zoneUsable(it.measuredAt,now) }?.zone
            // Re-read rather than trust the snapshot taken before the wait: the car may have been
            // unplugged during it, and writing then would clear the end time and resurrect the
            // finished episode.
            val current = prefs.flow.first()
            when (val decision = DepartureGate.decide(startedAt,now,current.connected,current.departureAt,current.departureEndedAt,offered)) {
                is DepartureDecision.Adopt -> {
                    prefs.departure(startedAt,decision.zone.name,0)
                    region = outcome.region
                    contextVersion++
                    classifyNow(origin = decision.zone, departedAt = startedAt)
                    op.confirm(when (decision.zone) {
                        Zone.HOME -> "집에서 출발"
                        Zone.WORK -> "회사에서 출발"
                        else -> "등록하지 않은 장소에서 출발"
                    })
                }
                DepartureDecision.Superseded -> {
                    classifyNow()
                    op.discard("연결이 끝나 이 출발 기록은 사용하지 않았어요")
                }
                is DepartureDecision.Rejected -> {
                    contextVersion++
                    classifyNow(origin = Zone.UNKNOWN, departedAt = startedAt)
                    op.confirm(if (decision.reason == DepartureRejection.NO_FIX)
                        "${decision.reason.detail} · ${outcome.status.advice.ifBlank { "위치를 확인하지 못했어요" }}"
                        else decision.reason.detail)
                }
            }
        }
    }

    /**
     * The result of one stationary diagnostic, saved deliberately rather than inferred from the
     * run. A foreground test can never justify SUPPORTED, so the caller passes what it observed
     * and the record carries the conditions it was observed under.
     */
    fun saveCapability(record: CapabilityRecord) {
        if (ui.value.driving) { message("정차 후 저장해 주세요"); return }
        operations.start(OperationKind.SAVE,OperationRegistry.capability(record.shortcut.name),
            retry = { saveCapability(record) }) { op ->
            // Merged inside the edit, not from the flow snapshot: two mappings saved in quick
            // succession from the diagnostic screen would otherwise drop one another's record.
            prefs.merge("steeringCapabilities") { raw ->
                CapabilityCodec.encode(CapabilityCodec.decode(raw).filterNot {
                    it.vehicleId == record.vehicleId && it.transport == record.transport && it.shortcut == record.shortcut
                } + record)
            }
            val readBack = CapabilityCodec.decode(prefs.flow.first().steeringJson)
            if (readBack.none { it == record }) error("결과를 저장하지 못했어요 · 다시 시도해 주세요")
            op.confirm("${record.shortcut.label} · ${record.capability.label}")
        }
    }

    /** The lab master switch. Turning it off abandons nothing, because nothing is wired yet. */
    fun steeringEnabled(value: Boolean) {
        if (ui.value.driving) { message("정차 후 설정해 주세요"); return }
        viewModelScope.launch { prefs.flag("steeringEnabled",value) }
    }

    /** §3: saved schedules take effect on the next assessment and never interrupt the current song. */
    fun saveSchedule(schedule: CommuteSchedule) {
        if (ui.value.driving) { message("정차 후 설정해 주세요"); return }
        operations.start(OperationKind.SAVE,OperationRegistry.schedule(schedule.direction.name),
            retry = { saveSchedule(schedule) }) { op ->
            val intended = schedule.copy(revision = schedule.revision + 1)
            // Same reason as saveCapability: merge against what is on disk at write time, so a
            // concurrent save of the other direction is not erased by this one's stale snapshot.
            prefs.merge("commuteSchedules") { raw ->
                CommuteCodec.encode(CommuteCodec.decode(raw).filterNot { it.id == intended.id } + intended)
            }
            // The evidence is the record on disk equalling what was meant to be written. Checking
            // only that the id came back would confirm a save that changed nothing.
            val readBack = CommuteCodec.decode(prefs.flow.first().commuteJson)
            if (readBack.none { it == intended }) error("일정을 저장하지 못했어요 · 다시 시도해 주세요")
            contextVersion++
            // Assess against what was verified on disk; the schedules flow may not have emitted yet.
            classifyNow(scheduleList = readBack)
            op.confirm("${intended.direction.label} 일정을 저장했어요 · ${intended.windowLabel}")
        }
    }

    fun deleteSchedule(direction: CommuteDirection, id: String) {
        if (ui.value.driving) { message("정차 후 설정해 주세요"); return }
        operations.start(OperationKind.SAVE,OperationRegistry.schedule(direction.name),
            retry = { deleteSchedule(direction, id) }) { op ->
            prefs.merge("commuteSchedules") { raw ->
                CommuteCodec.encode(CommuteCodec.decode(raw).filterNot { it.id == id })
            }
            // The property a delete has to prove is that this record is gone. Whole-list equality
            // would now fail whenever another save legitimately landed alongside it.
            val readBack = CommuteCodec.decode(prefs.flow.first().commuteJson)
            if (readBack.any { it.id == id }) error("일정을 삭제하지 못했어요 · 다시 시도해 주세요")
            contextVersion++
            classifyNow(scheduleList = readBack)
            op.confirm("${direction.label} 일정을 삭제했어요")
        }
    }
    fun disconnect() {
        if(ui.value.driving) return
        cancelSelection();accountJob?.cancel()
        viewModelScope.launch {
            coordinator.invalidate(); runtime.spotifyAuth.signOut(); runtime.spotifyRemote.disconnect(); repository.clearCache()
            prefs.flag("accountLinked", false); prefs.long("tasteSyncedAt", 0)
            mutable.update { it.copy(connection="미연결",queue=emptyList()) }
            message("이 기기의 연결과 후보 목록을 삭제했습니다. 앱 접근 권한은 Spotify 계정 설정에서도 해제할 수 있어요")
        }
    }
    fun clearHistory() { if(ui.value.driving) return;resetLearning();viewModelScope.launch { dao.clearHistory();dao.clearPlayed();message("추천 기록과 관련 학습 기여분을 삭제했습니다") } }
    fun resetAll() {
        if(ui.value.driving) return
        cancelSelection();accountJob?.cancel();surveyJob?.cancel();contextJob?.cancel();engine.clearCache()
        edits.trySend { operations.run(OperationKind.RESET,OperationRegistry.RESET) { coordinator.invalidate();runtime.spotifyAuth.signOut();runtime.spotifyRemote.disconnect();repository.clearCache();dao.clearHistory();dao.clearRules();intelligence.clearOutcomes();intelligence.clearBatches();intelligence.clearEvents();intelligence.clearAttempts();intelligence.clearState();prefs.clear();observer.release();listeningMutable.value=ListeningSummary();location.deleteZones();location.clear();weather.clear();region=null;weatherFact=null;prefs.departure(0,Zone.UNKNOWN.name,0);progress=DiscoveryProgress();seedRevision=-1L;firstMoodSession=null;contextVersion++;draftMutable.value=SurveyDraft();mutable.value=UiState() };message("앱을 초기화했습니다") }
    }
}
