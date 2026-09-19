package ai.drivemuse.app

import ai.drivemuse.app.onboarding.OnboardingScreen
import ai.drivemuse.app.ui.*
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.drivemuse.domain.*
import android.content.Intent
import ai.drivemuse.designsystem.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity: ComponentActivity() {
    /** The Spotify consent redirect (drivemuse://spotify-callback) comes back as a new intent. */
    private val redirect = MutableStateFlow<android.net.Uri?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        redirect.value = intent?.data
        setContent { DriveMuseTheme { DriveApp(redirect = redirect) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); redirect.value = intent.data }
}
@Composable fun DriveApp(vm: DriveViewModel = viewModel(), cvm: CatalogViewModel = viewModel(), redirect: MutableStateFlow<android.net.Uri?>? = null) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val catalogMessage by cvm.message.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val survey by vm.survey.collectAsStateWithLifecycle()
    val surveyBusy by vm.surveyBusy.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val diagnostic by ai.drivemuse.app.playback.MediaObservationService.diagnostic.collectAsStateWithLifecycle()
    val appContext=LocalContext.current
    val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if(allowed) vm.refreshWeather() else vm.message("위치 없이 기본 상황으로 추천합니다") }
    val redirectFlow = remember(redirect) { redirect ?: MutableStateFlow<android.net.Uri?>(null) }
    val pendingRedirect by redirectFlow.collectAsStateWithLifecycle()
    // The key must not depend on the value being consumed: clearing the flow to mark the redirect
    // as used would change the key and cancel the token exchange that is still in flight, which
    // left sign-in silently dead. Collect from a keyless effect and clear only once it is done.
    LaunchedEffect(Unit) {
        redirectFlow.filterNotNull().filter { it.scheme == "drivemuse" }.collect { uri ->
            val linked = cvm.onSpotifyRedirect(uri)
            redirectFlow.value = null
            if (linked) vm.onSpotifyLinked()
        }
    }
    // Sideloaded builds have no store behind them: check and fetch on launch, prompt when parked.
    val updateState by UpdateManager.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { UpdateManager.prepare(appContext) }
    val rules by vm.rules.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    (updateState as? UpdateState.Ready)?.takeIf { !ui.driving }?.let { ready ->
        AlertDialog(
            onDismissRequest = { UpdateManager.dismiss() },
            title = { Text("새 버전 ${ready.info.versionName}") },
            text = { Text("설치된 버전은 ${ready.info.installedVersionName}입니다. 지금 설치할까요? 기존 설정과 기록은 그대로 유지됩니다.") },
            confirmButton = { TextButton(onClick = { UpdateManager.install(appContext, ready.file) }) { Text("설치") } },
            dismissButton = { TextButton(onClick = { UpdateManager.dismiss() }) { Text("나중에") } }
        )
    }
    BackHandler(enabled=(survey?.completed==true) && ui.page!="홈" && !ui.driving) { vm.page(if(ui.page.startsWith("설정/")) "설정" else "홈") }
    val snackbar = remember { SnackbarHostState() }
    var deleteWhat by remember { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.message(if(result.values.all { it }) "권한이 허용되었습니다" else "권한 없이도 수동 선곡을 이용할 수 있습니다")
    }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.message(null) } }
    LaunchedEffect(catalogMessage) { catalogMessage?.let { snackbar.showSnackbar(it); cvm.message(null) } }
    Scaffold(containerColor=DriveColors.Carbon, snackbarHost={ SnackbarHost(snackbar) }, bottomBar={
        if((survey?.completed==true) && !ui.driving) NavigationBar(containerColor=DriveColors.Carbon) {
            listOf("홈" to Icons.Outlined.Home,"탐색" to Icons.Outlined.Explore,"에이전트" to Icons.Outlined.AutoAwesome).forEach { (name, icon) ->
                NavigationBarItem(selected=ui.page==name,onClick={ vm.page(name) },icon={ Icon(icon,name) },label={ Text(name) },colors=NavigationBarItemDefaults.colors(indicatorColor=DriveColors.High,selectedIconColor=DriveColors.Cyan))
            }
        }
    }) { padding ->
        if(survey==null) {
            Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
        } else if(survey?.completed!=true) {
            Box(Modifier.padding(padding)) { OnboardingScreen(survey!!,surveyBusy,vm::surveyAnswer,vm::surveyStep,vm::surveyConsent,vm::completeSurvey) }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
            item { Row(verticalAlignment=Alignment.CenterVertically) { Box(Modifier.weight(1f)) { Brand() }; IconButton(onClick={ vm.page("설정") },enabled=!ui.driving) { Icon(Icons.Outlined.Settings,"설정",tint=DriveColors.Muted) } } }
            if (ui.driving) {
                item { Title("지금은, 음악과 길에만.","운전 모드 · 설정과 입력을 잠시 숨겼어요") }
                item { GlassSurface { AgentOrb(active=false); Text("사용자 선택 유지 중",fontSize=24.sp); Text("음악 조작은 Spotify 또는 차량 화면에서 이용하세요.",color=DriveColors.Muted) } }
                item { OutlinedButton(onClick={ vm.driving(false) },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("정차했어요 · 운전 모드 종료") } }
            } else when(ui.page) {
                "홈" -> {
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("YOUR DAILY SOUNDTRACK",color=DriveColors.Muted,fontSize=10.sp,letterSpacing=2.sp); Spacer(Modifier.height(10.dp)); Text("어떤 길을 떠나나요?",fontSize=27.sp,fontWeight=FontWeight.Bold) }; AgentOrb() } }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { ContextPill(if(settings.connected) "차량 연결 감지" else "차량 연결 대기"); if(ui.demo) ContextPill("DEMO") } }
                    item { GlassSurface {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("FOR YOUR ${if(ui.context==DriveContext.COMMUTE_HOME) "EVENING" else "DRIVE"}",color=DriveColors.Muted,fontSize=10.sp,letterSpacing=1.5.sp); Text("✦  ${if(ui.demo) "SAMPLE MIX" else "DRIVE MIX"}",color=DriveColors.Cyan,fontSize=10.sp) }
                        AmbientArtwork(night=ui.context==DriveContext.NIGHT_DRIVE)
                        Text(ui.context.mix,fontSize=34.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp)
                        Text(when(ui.context) { DriveContext.COMMUTE_HOME -> "하루의 속도를, 조금 천천히."; DriveContext.COMMUTE_TO_WORK -> "기분 좋은 시작을 위한 리듬."; DriveContext.TRAVEL -> "익숙한 길 너머, 새로운 음악."; else -> "지금 이 순간에 어울리는 사운드." },color=DriveColors.Muted)
                        Text(ui.reason,fontSize=12.sp,color=DriveColors.Muted,lineHeight=19.sp)
                        DriveButton(if(ui.busy) "음악을 고르고 있어요…" else if(ui.queue.isEmpty()) "오늘의 믹스 고르기" else if(ui.queueStale) "조건이 바뀌었어요 · 다시 고르기" else "Spotify에서 재생",!ui.busy) {
                            if(ui.queue.isEmpty() || ui.queueStale) vm.recommend() else if(ui.demo) vm.message("샘플 곡입니다. 설정에서 데모 모드를 끄고 Google 계정을 연결해 주세요") else vm.handoff(ui.queue.first())
                        }
                        if(ui.queue.isNotEmpty()) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { TextButton(onClick={ vm.recommend() }) { Text("다른 믹스 고르기") }; if(!ui.demo) TextButton(onClick={ vm.skipCurrent() }) { Text("다음 곡으로") } }
                        Text("재생과 차량 디스플레이는 Spotify가 담당해요.",fontSize=11.sp,color=DriveColors.Muted)
                    } }
                    item { GlassSurface { Row(verticalAlignment=Alignment.CenterVertically) { AgentOrb(active=settings.suspendedUntil<System.currentTimeMillis()); Column(Modifier.weight(1f).padding(start=14.dp)) { Text(if(settings.suspendedUntil>System.currentTimeMillis()) "사용자 선택 유지 중" else "당신의 뮤직 에이전트",fontWeight=FontWeight.SemiBold); Text(ui.engineLabel,color=DriveColors.Muted,fontSize=12.sp) }; IconButton(onClick={ vm.page("에이전트") }) { Icon(Icons.Outlined.ChevronRight,"에이전트 설정") } }; Text(ui.connection,fontSize=12.sp,color=DriveColors.Cyan) } }
                    if(ui.queue.isNotEmpty()) { item { Section("이번 드라이브의 음악", if(ui.queueStale) "조건이 바뀌었어요 · 다시 고르기" else "${ui.queue.size}곡") }; items(ui.queue,key={it.id}) { track -> Column { TrackRow(track,ui.demo) { if(ui.demo) vm.message("데모 곡은 재생할 수 없습니다") else vm.handoff(track) };if(!ui.demo) Row { TextButton(onClick={vm.rate(track,true)}) {Text("좋아요")};TextButton(onClick={vm.rate(track,false)}) {Text("싫어요")} } } } }
                    item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { TextButton(onClick={ vm.page("기록") }) { Text("추천 기록") }; TextButton(onClick={ vm.driving(true) }) { Text("운전 모드 시작") } } }
                }
                "탐색" -> {
                    item { Title("길마다, 다른 무드.","지금의 이동을 직접 선택해 보세요") }
                    items(DriveContext.entries.filter { it!=DriveContext.UNKNOWN }) { kind ->
                        GlassSurface(Modifier.clickable { vm.choose(kind); vm.page("홈") }) { Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(kind.label,color=DriveColors.Cyan,fontSize=12.sp); Text(kind.mix,fontSize=25.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp)) }; Icon(Icons.Outlined.ArrowForward,"${kind.label} 선택") }; if(kind==DriveContext.TRAVEL) AmbientArtwork(Modifier.height(120.dp)) }
                    }
                }
                "에이전트" -> {
                    item { Title("내 취향을 아는 방향으로.","규칙은 기기에 저장하고 선곡에 반영해요") }
                    item { GlassSurface { AgentOrb(); Text("익숙함과 새로운 발견",fontSize=21.sp,fontWeight=FontWeight.SemiBold); var ratio by remember(settings.ratio) { mutableFloatStateOf(settings.ratio) }; Text("익숙한 곡 ${(100-ratio*100).toInt()}%  /  새 노래 ${(ratio*100).toInt()}%",color=DriveColors.Cyan); Slider(value=ratio,onValueChange={ratio=it},onValueChangeFinished={vm.ratio(ratio)}); Text("후보 곡에 따라 실제 비율은 달라질 수 있어요.",color=DriveColors.Muted,fontSize=12.sp) } }
                    item { GlassSurface {
                        Text("초기 설문과 행동 학습은 별도로 관리합니다")
                        Text("설문 응답 ${survey?.answers?.count { it.status==AnswerStatus.ANSWERED }?:0}개 · 재생 연동 L0 · 자동 청취 학습 없음")
                        analysis?.takeIf { it.version==survey?.revision }?.let { Text(org.json.JSONObject(it.json).optString("summary")) }
                        TextButton(onClick={vm.editTaste()}) {Text("초기 취향 수정")}
                        TextButton(onClick={deleteWhat="learning"}) {Text("행동 학습 초기화")}
                    } }
                    item { RuleComposer(vm) }
                    items(rules,key={it.id}) { rule -> GlassSurface { Row(verticalAlignment=Alignment.CenterVertically) { Text(rule.text,modifier=Modifier.weight(1f)); Switch(checked=rule.enabled,onCheckedChange={vm.toggleRule(rule)}) }; Text(rule.scope?.let { DriveContext.valueOf(it).label } ?: "모든 이동",color=DriveColors.Muted,fontSize=12.sp); TextButton(onClick={vm.deleteRule(rule.id)}) { Text("규칙 삭제") } } }
                    item { Text("규칙은 기기에서 해석합니다. ‘잔잔하게’는 곡의 장르 정보로만 추정하므로, 장르를 알 수 없는 곡은 선곡에서 빠집니다.",fontSize=12.sp,lineHeight=20.sp,color=DriveColors.Muted) }
                }
                "설정" -> {
                    // v2.3 §24: summary rows first; every detail lives one level down.
                    item { SettingsHome(vm,cvm,settings,survey?.aiConsent==true,ui.weatherLabel,diagnostic.hasMediaId,onOpen={ vm.page(it) }) { permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS)) } }
                }
                "설정/음악 서비스" -> item {
                    MusicServiceDetail(vm, cvm, settings, ui.driving, onSpotifyConnect = {
                        val intent = cvm.spotifyAuthorizeIntent()
                        if (intent == null) vm.message("먼저 Spotify Client ID를 저장해 주세요")
                        else runCatching { appContext.startActivity(intent) }.onFailure { vm.message("브라우저를 열 수 없습니다") }
                    })
                }
                "설정/재생" -> item { PlaybackDetail(diagnostic.state.toString(),diagnostic.hasMediaId,diagnostic.positionMs,ai.drivemuse.app.playback.MediaObservationService.granted(appContext)) { appContext.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
                "설정/AI" -> item { AiDetail(cvm,vm.aiConfigured,survey?.aiConsent==true,ui.driving,vm::surveyConsent) { vm.message("Firebase 콘솔 → 프로젝트 설정에서 프로젝트 ID·앱 ID·웹 API 키를 확인하고, 사용할 모델 ID를 함께 입력하세요") } }
                "설정/위치" -> item { WeatherDetail(vm,cvm,ui.weatherLabel,ui.driving) { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) } }
                "설정/장소" -> item { PlacesDetail(vm,{deleteWhat="home"},{deleteWhat="work"},{deleteWhat="zones"},ui.driving) }
                "설정/수집" -> item { CollectionDetail(vm,cvm,ui.driving) }
                "설정/차량" -> {
                    item { SettingsTitle("차량 연결","연결되면 조용한 알림으로 시작합니다") }
                    item { GlassSurface {
                        Toggle("데모 모드","샘플 곡으로 기능 둘러보기",ui.demo,vm::demo)
                        Toggle("차량 연결 알림","차량 연결 시 조용한 알림으로 시작",settings.auto) { value -> vm.auto(value); if(value) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS)) }
                        Text("자동 재생은 아직 지원하지 않습니다. 연결 알림을 누른 뒤 음악을 선택해 주세요.",fontSize=14.sp,color=DriveColors.Muted,lineHeight=20.sp)
                    } }
                    item { VehicleSettings(vm,settings) { permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) } }
                    item { ConnectionSettings(vm,settings) }
                }
                "개인정보" -> {
                    item { Title("취향은 기억하고,\n위치는 남기지 않아요.","저장하는 데이터와 연결을 한눈에") }
                    item { GlassSurface { ContextPill("원시 이동 경로 저장 없음"); Text("위치 · 주소",fontWeight=FontWeight.Bold); Text("대략 위치는 선택 사항이며 전경에서 요청할 때만 조회합니다. 현재 좌표는 영역·격자로 변환 후 폐기합니다. 집·회사 영역은 기기에 암호화 저장합니다.",color=DriveColors.Muted); Text("추천 기록 · 30일",fontWeight=FontWeight.Bold); Text("기기 내부에만 저장하며 앱 시작 시 만료 기록을 정리합니다.",color=DriveColors.Muted); Text("계정 연결",fontWeight=FontWeight.Bold); Text("Spotify 인증은 기기에서만 이뤄집니다. 갱신 토큰은 암호화 저장하고, 권한은 라이브러리 읽기와 재생 제어로 한정합니다.",color=DriveColors.Muted); Text("외부 전송",fontWeight=FontWeight.Bold); Text("Spotify에 검색·라이브러리 조회와 재생 명령, 날씨 제공자에 지역 격자를 전송합니다. Gemini 사용 동의 시 설문·후보·요약 반응을 Google에 전송합니다. 계정 토큰과 좌표는 AI에 보내지 않습니다.",color=DriveColors.Muted); Text("후보 곡 캐시 · 30일",fontWeight=FontWeight.Bold); Text("조회한 곡 정보는 기기에 저장하고 30일이 지나면 삭제합니다.",color=DriveColors.Muted) } }
                    item { OutlinedButton(onClick={deleteWhat="history"},modifier=Modifier.fillMaxWidth()) { Text("추천 기록 삭제") }; OutlinedButton(onClick={deleteWhat="disconnect"},modifier=Modifier.fillMaxWidth()) { Text("음악 연결 해제") }; TextButton(onClick={deleteWhat="all"},modifier=Modifier.fillMaxWidth()) { Text("앱 데이터 초기화",color=Color(0xFFFF6B6B)) } }
                }
                "기록" -> {
                    item { Title("지나온 길의 음악.","최근 30일의 추천 · 피드백은 기기에 저장돼요") }
                    if(history.isEmpty()) item { GlassSurface { Text("아직 추천 기록이 없어요."); Text("첫 믹스를 고르면 여기에 남겨둘게요.",color=DriveColors.Muted) } }
                    items(history,key={it.id}) { h -> GlassSurface { Text(h.title,fontSize=23.sp,fontWeight=FontWeight.SemiBold); Text("${if(h.demo) "데모 · " else ""}${h.count}곡 · ${DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(h.createdAt))}",color=DriveColors.Muted,fontSize=12.sp); Row { FilterChip(selected=h.feedback=="liked",onClick={vm.feedback(h.id,"liked")},label={Text("좋았어요")}); Spacer(Modifier.width(10.dp)); FilterChip(selected=h.feedback=="not_today",onClick={vm.feedback(h.id,"not_today")},label={Text("오늘은 별로")}) }; Text("피드백 기록은 다음 버전의 학습에 활용할 예정입니다.",fontSize=11.sp,color=DriveColors.Muted) } }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    ui.pendingRule?.let { rule -> if(!ui.driving) AlertDialog(onDismissRequest={vm.confirmRule(false)},title={Text("이 규칙을 적용할까요?")},text={Text("${rule.scope?.label ?: "모든 이동"}\n${rule.discovery?.let { "새 노래 ${(it*100).toInt()}%" } ?: "발견 비율 유지"}\n${if(rule.energyCeiling!=null) "잔잔한 곡 선택 · 분위기 정보가 없는 곡도 포함" else "에너지 제한 없음"}")},confirmButton={TextButton(onClick={vm.confirmRule(true)}) {Text("저장")}},dismissButton={TextButton(onClick={vm.confirmRule(false)}) {Text("취소")}}) }
    deleteWhat?.let { action -> if(!ui.driving) AlertDialog(onDismissRequest={deleteWhat=null},title={Text("${when(action){"learning"->"행동 학습을 초기화할까요?";"zones"->"등록 영역을 삭제할까요?";"home"->"현재 위치를 집으로 등록할까요?";"work"->"현재 위치를 회사로 등록할까요?";"history"->"추천 기록을 삭제할까요?";"disconnect"->"연결을 해제할까요?";else->"앱을 초기화할까요?"}}")},text={Text(if(action=="disconnect") "이 기기의 토큰과 후보 목록을 지웁니다. 앱의 접근 권한 자체는 Google 계정 설정에서 해제할 수 있습니다." else if(action=="home" || action=="work") "대략 위치 권한이 필요합니다. 중심점과 반경을 기기에 암호화 저장합니다." else "삭제한 데이터는 되돌릴 수 없습니다.")},confirmButton={TextButton(onClick={when(action){"learning"->vm.resetLearning();"zones"->vm.deleteZones();"home"->vm.registerZone(Zone.HOME);"work"->vm.registerZone(Zone.WORK);"history"->vm.clearHistory();"disconnect"->vm.disconnect();else->vm.resetAll()};deleteWhat=null}) {Text("확인")}},dismissButton={TextButton(onClick={deleteWhat=null}) {Text("취소")}}) }
}
@Composable private fun Brand() { Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) { Icon(Icons.Outlined.GraphicEq,null,tint=DriveColors.Cyan); Text("DriveMuse",fontSize=21.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.7).sp); Text("AI",fontSize=10.sp,color=DriveColors.Cyan) } }
@Composable private fun Title(title: String, sub: String) { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(title,fontSize=28.sp,lineHeight=38.sp,fontWeight=FontWeight.Bold); Text(sub,color=DriveColors.Muted,fontSize=13.sp,lineHeight=21.sp) } }
@Composable private fun Section(title: String, sub: String) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(title,fontSize=18.sp,fontWeight=FontWeight.SemiBold); Text(sub,color=DriveColors.Muted,fontSize=12.sp) } }
@Composable private fun TrackRow(track: Track,demo: Boolean,onClick: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick=onClick).heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) { Surface(shape=RoundedCornerShape(16.dp),color=DriveColors.High) { Icon(Icons.Outlined.MusicNote,null,tint=DriveColors.Cyan,modifier=Modifier.padding(14.dp)) }; Column(Modifier.weight(1f)) { Text(track.title,fontWeight=FontWeight.Medium); Text(track.artist,color=DriveColors.Muted,fontSize=12.sp) }; Text(if(demo) "샘플" else if(track.familiar) "익숙한 곡" else "발견",color=DriveColors.Muted,fontSize=10.sp) } }
@Composable private fun Toggle(title: String,description: String,value: Boolean,onChange: (Boolean)->Unit) { Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,fontWeight=FontWeight.SemiBold); Text(description,color=DriveColors.Muted,fontSize=12.sp,lineHeight=20.sp) }; Switch(checked=value,onCheckedChange=onChange) } }
@Composable private fun SettingsLink(text: String,icon: androidx.compose.ui.graphics.vector.ImageVector,action: ()->Unit) { Row(Modifier.fillMaxWidth().clickable(onClick=action).heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) { Icon(icon,null,tint=DriveColors.Muted); Text(text,modifier=Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight,null) } }
@Composable private fun RuleComposer(vm: DriveViewModel) { var text by rememberSaveable { mutableStateOf("") }; GlassSurface { Text("음악에게 부탁하기",fontSize=20.sp,fontWeight=FontWeight.SemiBold); OutlinedTextField(value=text,onValueChange={text=it.take(240)},label={Text("나만의 음악 규칙")},placeholder={Text("퇴근길에는 잔잔하게, 새 노래 30%")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)); DriveButton("규칙 확인하기",text.isNotBlank()) {vm.parseRule(text)} } }
@Composable private fun ConnectionSettings(vm: DriveViewModel,settings: Settings) {
    GlassSurface {
        Text("Google 계정 연결",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        Text(if(settings.accountLinked) "좋아요와 구독을 읽어 취향을 만듭니다. 읽기 전용이라 재생목록을 바꾸지 않아요." else "연결하지 않아도 인기 음악으로 선곡할 수 있어요. 연결하면 내 취향을 반영합니다.",color=DriveColors.Muted,fontSize=12.sp,lineHeight=20.sp)
        ContextPill(if(settings.accountLinked) "연결됨 · youtube.readonly" else "미연결")
        if(!vm.spotifyLinked) Text("설정에서 Spotify 계정을 연결해 주세요. 곡 지정 재생에는 Premium이 필요합니다.",color=Color(0xFFF6C85F),fontSize=12.sp,lineHeight=20.sp)
        else TextButton(onClick={ vm.onSpotifyLinked() },modifier=Modifier.fillMaxWidth()) { Text("취향 다시 불러오기") }
        Text("Google 비밀번호와 쿠키는 받지 않습니다. 인증은 Google 화면에서만 진행돼요.",fontSize=11.sp,color=DriveColors.Muted,lineHeight=18.sp)
        if(settings.searchCalls>0) Text("오늘 남은 음악 검색 ${Quota.remainingSearches(settings.searchCalls)}회",fontSize=11.sp,color=DriveColors.Muted)
    }
}
@Composable private fun VehicleSettings(vm: DriveViewModel,settings: Settings,requestPermission: ()->Unit) {
    val context=LocalContext.current
    var devices by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    GlassSurface {
        Text("내 차량",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        Text(settings.vehicleName.ifBlank {"등록된 차량이 없어요"},color=DriveColors.Muted)
        OutlinedButton(onClick={
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) requestPermission()
            else runCatching { context.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty().map {VehicleIdentity.hash(it.address) to (it.name ?: "이름 없는 기기")} }.onSuccess { devices=it; if(it.isEmpty()) vm.message("Android 설정에서 차량을 먼저 페어링해 주세요") }.onFailure {vm.message("Bluetooth 연결 상태와 권한을 확인해 주세요")}
        }) {Text("페어링된 차량 찾기")}
        devices.forEach { (id,name) -> TextButton(onClick={vm.registerVehicle(id,name);devices=emptyList()}) {Text(name)} }
        Text("차량의 Bluetooth 기기를 선택하세요. 주소는 기기별 키로 변환해 저장합니다.",fontSize=12.sp,color=DriveColors.Muted,lineHeight=20.sp)
        TextButton(onClick={vm.classifyNow();vm.page("홈")}) {Text("현재 시간·차량으로 이동 판정")}
    }
}
