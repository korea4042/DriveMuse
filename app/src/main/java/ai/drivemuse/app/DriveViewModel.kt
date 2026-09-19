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
    val engineLabel: String = "초기 취향 · Spotify 재생", val weatherLabel: String = "날씨 정보 없음", val consent: android.app.PendingIntent? = null
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
    private val learning=LearningStore(db)
    private val location=LocationAdapter(application)
    private val weather=WeatherRepository()
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
    // v2.3 §3, Spotify edition: the pool and playback come from one provider that identifies
    // recordings, so there is no video-to-song matching left to get wrong.
    private val repository = MusicRepository(runtime.spotify, dao, prefs)
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
            dao.prune(System.currentTimeMillis()-2592000000L);learning.prune(System.currentTimeMillis())
            val d=surveyStore.load();draftMutable.value=d
            if(d.completed) { val restored=coordinator.restore(d.revision);mutable.update { it.copy(queue=restored,engineLabel="저장된 추천 · Spotify 재생") } }
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
    /** Registered zones, so the screen shows whether saving actually worked (§24). */
    private val zonesMutable = MutableStateFlow(emptySet<Zone>())
    val registeredZones = zonesMutable.asStateFlow()
    /** Pool size and the last refresh failure, shown on the music service screen. */
    private val poolMutable = MutableStateFlow("후보 확인 전")
    val poolStatus = poolMutable.asStateFlow()
    fun refreshPool() {
        viewModelScope.launch {
            // Name what each Spotify source returned; "pool empty" alone never says which step failed.
            val probe = runCatching {
                val me = runtime.spotify.me()
                val product = me?.optString("product").orEmpty()
                val saved = runCatching { runtime.spotify.savedTracks(5).size }.getOrElse { -1 }
                val top = runCatching { runtime.spotify.topTracks(limit = 5).size }.getOrElse { -1 }
                val search = runCatching { runtime.spotify.search("pop", 5).size }.getOrElse { -1 }
                "계정 " + (product.ifBlank { "확인 불가" }) + " · 저장 $saved · 인기 $top · 검색 $search"
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
                    check(spotifyLinked) { "설정에서 Spotify 계정을 연결해 주세요" }
                    if(seedRevision!=revision) {
                        try { repository.addSurveyCandidates(draft.answers);seedRevision=revision } catch(e: CancellationException) { throw e } catch(_: Exception) { /* Existing pool remains usable. */ }
                    }
                    repository.candidates(snapshot.context, config)
                }
                val p=profile();val constraints=Constraints(excludedGenres=p.exclusions)
                val scores = learning.scores(sessionId,System.currentTimeMillis())
                fun rank(pool: List<Track>) = TasteRanker.prepare(pool,p,constraints,scores).filter { effective.allowsEnergy(it) }.sortedByDescending { it.affinity-it.fatigue }.take(40)
                var prepared = rank(tracks)
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

    private fun cancelSelection() { selectionGeneration++; selectionJob?.cancel(); selectionJob=null; mutable.update { it.copy(busy=false) } }
    fun suspendAgent() { cancelSelection(); viewModelScope.launch { coordinator.invalidate();prefs.suspendUntil(System.currentTimeMillis()+30*60*1000); message("30분 동안 사용자 선택을 유지합니다") } }

    /** One in-flight playback request at a time: a double tap must not queue the batch twice (QUE02). */
    private var playbackJob: Job? = null

    /**
     * §30, PLAY01/02, QUE02: play the selected recording, confirm that it actually started, then
     * append only the tracks that follow it in the batch. Both transports follow this same plan.
     */
    fun handoff(track: Track?) {
        if (track == null) { message("재생할 곡이 없습니다"); return }
        if (!ai.drivemuse.app.spotify.SpotifyIds.isTrackId(track.id)) { message("예전 목록의 곡이에요. 설정에서 후보를 새로 불러와 주세요"); return }
        if (!Constraints(excludedGenres = profile().exclusions).allows(track)) { message("현재 제외 조건에 맞지 않는 곡입니다"); return }
        if (playbackJob?.isActive == true) { message("재생 요청을 처리하는 중이에요"); return }
        val batch = ui.value.queue
        val following = batch.dropWhile { it.id != track.id }.drop(1).filter { it.id != track.id }.distinctBy { it.id }
        playbackJob = viewModelScope.launch {
          // A hard ceiling on the whole request: nothing here may leave the button locked.
          val finished = kotlinx.coroutines.withTimeoutOrNull(60_000) {
            message("Spotify에 연결하는 중…")
            val remote = runtime.spotifyRemote
            var transport = "App Remote"
            var failure = remote.connect(getApplication())
            if (failure == null) failure = remote.playAndConfirm(track.id)
            if (failure != null) {
                // A device that is already awake can still take a Web API command; same plan, other pipe.
                transport = "Web API"
                val web = runCatching { runtime.spotify.play(track.id) }
                val confirmed = web.isSuccess && confirmViaWebApi(track.id)
                if (!confirmed) {
                    message(failure + (web.exceptionOrNull()?.let { " · Web API: ${it.message}" } ?: " · Web API: 시작 확인 실패"))
                    return@withTimeoutOrNull false
                }
            }
            // Exposure only (§17 EXPOSED_ONLY); the listening outcome comes from observation.
            if (!ui.value.demo) repository.recordPlay(track.id)
            var queued = 0
            for (next in following) {
                val err = if (transport == "App Remote") remote.queueAwait(next.id) else runCatching { runtime.spotify.queue(next.id) }.exceptionOrNull()?.message
                if (err == null) queued++
            }
            message("${track.artist} ${track.title} 재생 시작" + (if (following.isEmpty()) "" else " · 이어서 ${queued}/${following.size}곡 대기") + " ($transport)")
            true
          }
          if (finished == null) message("재생 요청이 60초 안에 끝나지 않아 중단했어요. Spotify 앱 상태를 확인해 주세요")
        }
    }

    /** Polls /me/player briefly until the requested track is the current one. */
    private suspend fun confirmViaWebApi(trackId: String): Boolean {
        repeat(5) {
            val state = runCatching { runtime.spotify.playback() }.getOrNull()
            if (state?.trackId == trackId && state.playing) return true
            kotlinx.coroutines.delay(1500)
        }
        return false
    }

    fun parseRule(text: String) {
        if(ui.value.driving) return
        val parsed = RuleEngine.parse(text.take(240),UUID.randomUUID().toString(),System.currentTimeMillis())
        if (parsed==null) message("‘퇴근길/출근길/여행/야간’, ‘잔잔하게·신나게’, ‘새 노래 30%’ 중 하나는 포함해 주세요")
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
        edits.trySend { coordinator.invalidate();runtime.spotifyAuth.signOut();runtime.spotifyRemote.disconnect();repository.clearCache();dao.clearHistory();dao.clearRules();intelligence.clearOutcomes();intelligence.clearBatches();intelligence.clearState();prefs.clear();location.deleteZones();location.clear();weather.clear();region=null;weatherFact=null;progress=DiscoveryProgress();seedRevision=-1L;firstMoodSession=null;contextVersion++;draftMutable.value=SurveyDraft();mutable.value=UiState() }
    }
}
