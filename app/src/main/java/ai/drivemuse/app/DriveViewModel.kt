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
    val engineLabel: String = "초기 취향 · Spotify 재생", val weatherLabel: String = "날씨 정보 없음", val consent: android.app.PendingIntent? = null,
    /**
     * §7 QUE01, FIX-B: why the list no longer matches its conditions. A set rather than a flag,
     * because a recovery from an off-plan recording must not also clear an outstanding rule change.
     */
    val stale: Set<StaleReason> = emptySet()
) {
    /** Re-selection fixes conditions that moved; it does not fix a queue awaiting verification. */
    val needsReselect get() = stale.any { !it.clearedByConfirmedPlayback }
    val staleLabel get() = when {
        needsReselect -> "조건이 바뀌었어요 · 다시 고르기"
        StaleReason.CONTROL_LOST in stale -> "다른 곡이 재생됐어요 · 목록에서 다시 선택"
        StaleReason.DELIVERY_UNCERTAIN in stale -> "대기 곡 전달 결과를 확인하지 못했어요"
        StaleReason.RESTORE_UNVERIFIED in stale -> "저장된 목록 · 재생하면 이어집니다"
        else -> null
    }
}
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
    private val scheduler=NextBatchScheduler { base -> prepareNextBatch(base) }
    private val observer=PlaybackObserver(db,learning,{ sessionId },System::currentTimeMillis,
        { id,ordinal,size -> scheduler.onStarted(id,ordinal,size) },
        { id -> onUnplannedPlayback(id) })
    private val location=LocationAdapter(application)
    private val weather=WeatherRepository()
    private var region: Region?=null
    private var weatherFact: WeatherFact?=null
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
    private suspend fun touchSession() {
        val previous=sessionId
        runCatching { sessionId=prefs.session(System.currentTimeMillis()) { UUID.randomUUID().toString() } }
        // A new session clears the re-roll memory. It deliberately does not clear controlLost:
        // the id rolls over on an inactivity timer, and a timer must not hand the queue back.
        if(sessionId!=previous) offered.clear()
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
        contextJob?.cancel();contextJob=viewModelScope.launch {
            region=location.refresh();weatherFact=region?.let { weather.get(it) };contextVersion++
            cancelSelection();coordinator.invalidate()
            val fact=weatherFact
            mutable.update { it.copy(weatherLabel=if(fact==null) "날씨 정보 없음 · 기본 상황으로 추천" else "기상청 · ${fact.temperature}°C · ${if(fact.precipitation>0) "강수" else "강수 없음"} · ${java.time.Instant.ofEpochMilli(fact.observedAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime()}${if(fact.stale(System.currentTimeMillis())) " · 오래된 관측" else ""}") }
        }
    }
    /** Registered zones, so the screen shows whether saving actually worked (§24). */
    private val zonesMutable = MutableStateFlow(emptySet<Zone>())
    val registeredZones = zonesMutable.asStateFlow()
    /** Pool size and the last refresh failure, shown on the music service screen. */
    private val poolMutable = MutableStateFlow("후보 확인 전")
    val poolStatus = poolMutable.asStateFlow()
    fun refreshPool() {
        viewModelScope.launch {
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
        }
    }
    fun refreshZones() { zonesMutable.value = runCatching { location.registeredZones() }.getOrDefault(emptySet()) }
    fun registerZone(zone: Zone) { if(ui.value.driving) return;contextJob?.cancel();contextJob=viewModelScope.launch {
        val ok = location.register(zone)
        refreshZones()
        message(if(ok) "${if (zone == Zone.HOME) "집" else "회사"}을(를) 등록했습니다" else "위치 권한과 정확도를 확인해 주세요. 실외에서 다시 시도하면 잘 잡혀요")
    } }
    fun deleteZones() { if(ui.value.driving) return;contextJob?.cancel();location.deleteZones();region=null;weatherFact=null;weather.clear();contextVersion++;cancelSelection();viewModelScope.launch { coordinator.invalidate() };message("등록 영역을 삭제했습니다") }
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
            ui.value.stale.any { !it.clearedByConfirmedPlayback } ->
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
    fun demo(value: Boolean) { cancelSelection();progress=DiscoveryProgress(); mutable.update { it.copy(demo=value,queue=emptyList(),connection=if(value) "데모 · 계정 미연결" else connectionLabel(settings.value)) } }
    fun driving(value: Boolean) { if(value) { cancelSelection();contextJob?.cancel() }; mutable.update { it.copy(driving=value,page="홈") } }
    fun auto(value: Boolean) { if (ui.value.driving) return; viewModelScope.launch { prefs.flag("auto",value) } }
    fun ratio(value: Float) { if(ui.value.driving) return;cancelSelection();markQueueStale(StaleReason.RULE_CHANGED);viewModelScope.launch { coordinator.invalidate();prefs.ratio(value) } }
    fun choose(context: DriveContext) { if (ui.value.driving) return; suspendAgent();contextVersion++;mutable.update { it.copy(context=context) }; recommend() }

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
        selectionJob = viewModelScope.launch { runSelection(generation, append = false, baseRevision = null) }
    }

    /**
     * Phase 1 §6. Called by the scheduler when the last track of the current batch starts, so the
     * next three are chosen with the first two tracks' outcomes already counted and reach Spotify's
     * queue before the current track ends. Unlike the manual path this runs while driving: the
     * driver asked for nothing, which is the whole point.
     */
    private suspend fun prepareNextBatch(baseRevision: Long) {
        if(survey.value?.completed!=true || ui.value.demo || !spotifyLinked) return
        if(settings.value.suspendedUntil>System.currentTimeMillis()) return
        // R09: automation does not resume on its own after losing the queue.
        if(settings.value.controlLost) return
        runSelection(selectionGeneration, append = true, baseRevision = baseRevision)
    }

    private suspend fun runSelection(generation: Int, append: Boolean, baseRevision: Long?) {
            touchSession()
            if(!append) mutable.update { it.copy(busy=true) }
            val snapshot = ui.value
            val draft=survey.value?:return
            val revision=draft.revision
            // Appending must not repeat what is already queued or still playing. A manual re-roll
            // must not repeat what this session has already been offered, or it is not a re-roll.
            var excluded = if(append) snapshot.queue.map { it.id }.toSet()
                else synchronized(offered) { offered.toSet() } + snapshot.queue.map { it.id }
            val carried = if(append) snapshot.queue.takeLast(Policy.BATCH_SIZE) else emptyList()
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
                        error(reason)
                    }
                }
                val direct=if(Policy.DIRECT_INPUT_ONLY) DirectInputSelector.select(prepared,constraints,sessionId,excluded) else null
                val fallback=direct?.tracks ?: SessionRanker.select(prepared,effective,progress)
                // FIX-A: not read, not merely unused. Both of these are derived from observation.
                val outcomes=if(Policy.SPOTIFY_BEHAVIOR_LEARNING_ALLOWED) intelligence.outcomes().filter { it.sessionId==sessionId }.sortedBy { it.createdAt }.map(learning::outcome) else emptyList()
                val version=QueueVersion(sessionId,generation.toLong(),revision,contextVersion,outcomes.maxOfOrNull { it.version }?:0,UUID.randomUUID().toString())
                coordinator.begin(version)
                val now=System.currentTimeMillis();val validRegion=region?.takeIf { now-it.measuredAt in 0..120000 }
                val validWeather=validRegion?.let { r -> weatherFact?.takeIf { it.usable(r.id,now) } }
                val semantic=JSONObject().put("zone",validRegion?.zone?.name?:"UNKNOWN").put("timeOfDay",LocalDateTime.now().hour).put("origin","UNKNOWN").put("direction","UNKNOWN")
                validWeather?.let { semantic.put("weather",JSONObject().put("temperature",it.temperature).put("precipitation",it.precipitation).put("stale",it.stale(now))) }
                val novelty=if(snapshot.demo || !Policy.SPOTIFY_BEHAVIOR_LEARNING_ALLOWED) emptyMap() else runCatching { ai.drivemuse.app.catalog.NoveltyAnnotator(db.catalog()).annotate(prepared,emptySet(),runtime.historyCoverageSince()) }.getOrDefault(emptyMap())
                val selection=engine.select(draft.aiConsent && !snapshot.demo,prepared,fallback,p,semantic,outcomes,constraints,effective.discovery,progress,version,0,(0 until Policy.BATCH_SIZE).toList(),novelty,MixTarget.resolve(p))
                val queue=selection.tracks
                if(generation!=selectionGeneration || survey.value?.revision!=revision) return
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
                if(!snapshot.demo && !coordinator.commit(version,queue,prepared,constraints)) return
                if(generation!=selectionGeneration || survey.value?.revision!=revision) return
                // T22: a proposal built on a queue revision that has since moved is discarded whole.
                if(append && !scheduler.accepts(baseRevision!!)) return
                progress=progress.append(queue)
                offered.addAll(queue.map { it.id })
                if(append) appendToPlayer(carried,queue)
                val shortfall=direct?.takeIf { it.short }
                // Direct-input mode does not read the genre-approximated energy, so a quiet/lively
                // rule has nothing to act on. Dropping it silently would misreport the user's own
                // rule as applied.
                val energyRuleUnapplied=direct!=null && effective.energyCeiling<1.0
                mutable.update { it.copy(queue=if(append) carried+queue else queue,stale=emptySet(),engineLabel=if(direct!=null) "설문 조건 · 직접 입력 선곡" else selection.label,connection=if(snapshot.demo) "데모 · 계정 미연결" else connectionLabel(config),reason="${snapshot.context.label} · 새 노래 목표 ${(effective.discovery*100).toInt()}% · "+when(selection.adjustment) { "REDUCE_RECENT_SKIP"->"최근 넘긴 곡을 피해서 골랐어요";"FAVOR_SUPPORTED_FEATURE"->"반응이 좋았던 특성을 우선했어요";"EXPLORE_ALTERNATIVE"->"다른 방향의 곡을 섞었어요";else->"설정된 취향을 바탕으로 골랐어요" }+(if("NOVEL_POOL_SHORTAGE" in selection.unmet) " · 새 후보가 부족해요" else "")) }
                dao.putHistory(HistoryEntity(UUID.randomUUID().toString(),snapshot.context.name,snapshot.context.mix,queue.size,System.currentTimeMillis(),demo=snapshot.demo))
                // Say the pool is short rather than padding it out of the scored ranking.
                if(shortfall!=null && !append) message("조건을 통과한 후보가 ${shortfall.eligible}곡이라 ${queue.size}곡만 준비했어요. 제외 조건을 확인하거나 후보를 더 불러와 주세요")
                else if(energyRuleUnapplied && !append) message("직접 입력 모드에서는 ‘잔잔하게’ 같은 세기 규칙을 적용할 수 없어요. 이 곡들은 규칙을 반영하지 않았습니다")
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if(!append) message(explain(e)) }
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
        var unclear = 0
        for (track in queue) when (runtime.spotifyRemote.queue(track.id)) {
            is ai.drivemuse.app.spotify.DispatchResult.Accepted -> queued++
            is ai.drivemuse.app.spotify.DispatchResult.Unknown -> unclear++
            is ai.drivemuse.app.spotify.DispatchResult.Rejected -> Unit
        }
        message(when {
            queued == queue.size -> "다음 ${queued}곡을 이어서 준비했어요"
            // R02/§7: an unconfirmed item is not a waiting track. Say so rather than count it.
            unclear > 0 -> "다음 ${queued}/${queue.size}곡 확인 · ${unclear}곡은 결과 불명"
            else -> "다음 ${queued}/${queue.size}곡만 준비했어요 · Spotify 연결을 확인해 주세요"
        })
    }

    /** §6.8 — every failure has one recovery path and only re-auth is worth surfacing. */
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
        prefs.flag("controlLost", true)
        mutable.update { it.copy(stale=it.stale+StaleReason.CONTROL_LOST) }
        message("목록에 없는 곡이 재생돼 자동 선곡을 멈췄어요. 목록에서 곡을 선택하면 다시 시작합니다")
    }

    /**
     * Called only after the intended recording is confirmed playing. Asking is not regaining: if
     * the start failed or its result was unclear, the app does not know what is in the queue and
     * has no business filling it.
     */
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
            var unclear = 0
            for (next in following) when (remote.queue(next.id)) {
                is ai.drivemuse.app.spotify.DispatchResult.Accepted -> queued++
                // Re-queueing on Unknown is how the same track lands twice (§7).
                is ai.drivemuse.app.spotify.DispatchResult.Unknown -> unclear++
                is ai.drivemuse.app.spotify.DispatchResult.Rejected -> Unit
            }
            if (unclear > 0) mutable.update { it.copy(stale=it.stale+StaleReason.DELIVERY_UNCERTAIN) }
            // R09: the request was not the recovery. A confirmed start of the intended recording
            // is. On failure or an unclear result the app stays out of the queue.
            regainControl(capturedEpoch)
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
    fun confirmRule(save: Boolean) { if(save) { cancelSelection();markQueueStale(StaleReason.RULE_CHANGED) }; val r=ui.value.pendingRule; mutable.update { it.copy(pendingRule=null) }; if(save && r!=null && !ui.value.driving) viewModelScope.launch { coordinator.invalidate();dao.putRule(RuleEntity.from(r)) } }
    fun deleteRule(id: String) { cancelSelection();markQueueStale(StaleReason.RULE_CHANGED); if (!ui.value.driving) viewModelScope.launch { coordinator.invalidate();dao.deleteRule(id) } }
    fun toggleRule(rule: RuleEntity) { cancelSelection();markQueueStale(StaleReason.RULE_CHANGED); if(!ui.value.driving) viewModelScope.launch { coordinator.invalidate();dao.putRule(rule.copy(enabled=!rule.enabled)) } }
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
        edits.trySend { coordinator.invalidate();runtime.spotifyAuth.signOut();runtime.spotifyRemote.disconnect();repository.clearCache();dao.clearHistory();dao.clearRules();intelligence.clearOutcomes();intelligence.clearBatches();intelligence.clearEvents();intelligence.clearAttempts();intelligence.clearState();prefs.clear();observer.release();listeningMutable.value=ListeningSummary();location.deleteZones();location.clear();weather.clear();region=null;weatherFact=null;progress=DiscoveryProgress();seedRevision=-1L;firstMoodSession=null;contextVersion++;draftMutable.value=SurveyDraft();mutable.value=UiState() }
    }
}
