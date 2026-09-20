package ai.drivemuse.app.ui

import ai.drivemuse.app.*
import ai.drivemuse.designsystem.*
import ai.drivemuse.domain.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Technical design v2.3 §24. Type scale: title 24sp, section 18–20sp, body 16/24sp, caption 14/20sp.
 * Spacing: screen 20dp, card 20dp, group gap 24dp, item gap 12–16dp. One primary action per row,
 * touch targets ≥ 48dp, status never conveyed by colour alone, dev terms only in "진단" expander.
 */

@Composable fun SettingsHome(
    vm: DriveViewModel, cvm: CatalogViewModel, settings: Settings, aiConsent: Boolean,
    weatherLabel: String, observationAvailable: Boolean, onOpen: (String) -> Unit, onRequestBluetooth: () -> Unit
) {
    val integrations by cvm.integrations.collectAsStateWithLifecycleCompat()
    val collection by cvm.collection.collectAsStateWithLifecycleCompat()
    val ai = integrations.getValue(ProviderId.FIREBASE_AI)
    val aiReady = vm.aiConfigured || ai.ready || integrations.getValue(ProviderId.GEMINI_DIRECT).ready
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsTitle("나에게 맞게.", "상태를 보고, 필요한 것만 바꾸세요")
        GlassSurface {
            SummaryRow(Icons.Outlined.MusicNote, "음악 서비스", if (vm.spotifyLinked) "Spotify 연결됨 · 재생과 취향" else "Spotify 미연결", if (vm.spotifyLinked) null else "설정 필요") { onOpen("설정/음악 서비스") }
            SummaryRow(Icons.Outlined.PlayCircle, "재생과 청취 학습", if (observationAvailable) "음악 앱에서 재생할 수 있어요 · 청취 상태 확인 중" else "음악 앱에서 재생할 수 있어요 · 자동 학습 꺼짐", null) { onOpen("설정/재생") }
            SummaryRow(Icons.Outlined.AutoAwesome, "AI 추천", when { !aiReady -> "설정 필요"; IntegrationPolicy.effectiveEnabled(aiConsent, aiReady) -> "사용 중"; else -> "꺼짐" }, if (!aiReady) "설정 필요" else null) { onOpen("설정/AI") }
            SummaryRow(Icons.Outlined.WbSunny, "위치와 날씨", weatherLabel, if (!vm.weatherConfigured) "날씨 설정 필요" else null) { onOpen("설정/위치") }
            SummaryRow(Icons.Outlined.Home, "집과 회사", "장소를 등록하면 출퇴근 상황을 더 잘 이해해요", null) { onOpen("설정/장소") }
            SummaryRow(Icons.Outlined.LibraryMusic, "음악 정보 수집", collection.lastSuccessAt?.let { "마지막 갱신 ${time(it)} · ${collection.phase}" } ?: collection.phase, null) { onOpen("설정/수집") }
        }
        GlassSurface {
            SummaryRow(Icons.Outlined.DirectionsCar, "차량 연결", if (settings.vehicleName.isNotBlank()) settings.vehicleName else "차량 미등록", null) { onOpen("설정/차량") }
            SummaryRow(Icons.Outlined.Shield, "개인정보", "저장하는 데이터와 삭제", null) { onOpen("개인정보") }
            SummaryRow(Icons.Outlined.History, "추천 기록", "최근 30일", null) { onOpen("기록") }
        }
        Text("데모 모드·차량 알림·권한은 각 상세 화면에서 바꿀 수 있어요.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
        Spacer(Modifier.height(4.dp)); TextButton(onClick = onRequestBluetooth, modifier = Modifier.heightIn(min = 48.dp)) { Text("권한 다시 요청") }
        UpdateRow()
        BuildStamp()
    }
}

/** Detail: music service integration (§22). Spotify only: a Client ID plus PKCE sign-in, no secret. */
@Composable fun MusicServiceDetail(vm: DriveViewModel, cvm: CatalogViewModel, settings: Settings, driving: Boolean, onSpotifyConnect: () -> Unit) {
    val integrations by cvm.integrations.collectAsStateWithLifecycleCompat(); val busy by cvm.busy.collectAsStateWithLifecycleCompat()
    val linked by cvm.spotifyLinkedFlow.collectAsStateWithLifecycleCompat()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsTitle("음악 서비스", "곡 정보를 어디서 가져올지 정합니다")
        SectionTitle("Spotify")
        IntegrationCard(cvm, ProviderId.SPOTIFY, integrations.getValue(ProviderId.SPOTIFY), busy == ProviderId.SPOTIFY, driving,
            "Spotify 개발자 대시보드에서 만든 앱의 Client ID를 넣으세요. Client Secret은 쓰지 않습니다. 곡을 지정해 재생하려면 Premium 계정이 필요해요.")
        GlassSurface {
            Text(if (linked) "계정 연결됨" else "계정 미연결", fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            Text("연결하면 저장한 곡과 자주 듣는 곡을 읽고, 재생을 제어할 수 있어요.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            val pool by vm.poolStatus.collectAsStateWithLifecycle()
            Text(pool, fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            if (linked) DriveButton("후보 다시 불러오기", !driving) { vm.refreshPool() }
            if (linked) OutlinedButton(onClick = { cvm.spotifySignOut() }, enabled = !driving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Spotify 연결 해제") }
            else DriveButton("Spotify 계정 연결", !driving && integrations.getValue(ProviderId.SPOTIFY).ready) { onSpotifyConnect() }
        }
        // Sign-in fails in the browser, not in the app, when these three do not match the
        // dashboard exactly — so the app has to show what it is actually sending.
        var showRegistration by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { showRegistration = !showRegistration }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showRegistration) "등록 정보 숨기기" else "연결이 안 되나요? 등록 정보 보기") }
        if (showRegistration) GlassSurface {
            val appContext = LocalContext.current
            val identity = remember(appContext) { ai.drivemuse.app.AndroidClientIdentity.of(appContext) }
            Text("Spotify 대시보드에 아래 값이 그대로 있어야 합니다.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            Metric("Redirect URI", ai.drivemuse.app.spotify.SpotifyAuth.REDIRECT)
            Metric("패키지명", identity?.packageName ?: "확인 불가")
            Metric("SHA-1", identity?.sha1?.chunked(2)?.joinToString(":") ?: "확인 불가")
            Text("셋 다 맞는데도 실패하면, 대시보드 User Management에 로그인하려는 계정의 이메일이 등록돼 있는지 확인해 주세요. 개발 모드 앱은 등록된 계정만 로그인할 수 있어요.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
        }
        SectionTitle("음악 지식 (선택)")
        IntegrationCard(cvm, ProviderId.MUSICBRAINZ, integrations.getValue(ProviderId.MUSICBRAINZ), false, driving, "곡 식별에 사용합니다. 별도 키 없이 사용하며, 앱이 요청 속도를 관리합니다.")
        IntegrationCard(cvm, ProviderId.LASTFM, integrations.getValue(ProviderId.LASTFM), busy == ProviderId.LASTFM, driving, "장르 태그에 사용합니다. API 키만 필요하고 Last.fm 로그인이나 Shared Secret은 필요하지 않아요.")
        IntegrationCard(cvm, ProviderId.LISTENBRAINZ, integrations.getValue(ProviderId.LISTENBRAINZ), busy == ProviderId.LISTENBRAINZ, driving, "선택 연결입니다. 사용자명을 입력하면 그 계정의 추천을 발견 seed로 사용하고, 입력하지 않으면 공개 정보만 씁니다.")
    }
}

@Composable private fun IntegrationCard(cvm: CatalogViewModel, p: ProviderId, cfg: IntegrationConfig, busy: Boolean, driving: Boolean, help: String) {
    val fields = ProviderRequirements.fields(p)
    val values = remember(p) { mutableStateMapOf<String, String>() }
    val reveal = remember { mutableStateMapOf<String, Boolean>() }
    GlassSurface {
        SectionTitle(cvm.label(p))
        Text(when (cfg.status) { IntegrationStatus.READY -> "연결됨" + (cfg.lastValidatedAt?.let { " · ${time(it)} 확인" } ?: ""); IntegrationStatus.VALIDATING -> "확인 중"; IntegrationStatus.ERROR -> "연결 실패 · ${cvm.explain(cfg.error)}"; IntegrationStatus.DRAFT -> "저장 전"; IntegrationStatus.UNCONFIGURED -> if (fields.any { it.required }) "설정 필요" else "별도 키 없이 사용" }, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        Text(help, fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
        if (driving) { Text("정차 후 설정에서 입력할 수 있어요.", fontSize = 14.sp, color = DriveColors.Muted); return@GlassSurface }
        fields.forEach { f ->
            OutlinedTextField(
                value = values[f.key] ?: "", onValueChange = { values[f.key] = it.take(512) }, label = { Text(f.label + if (f.required) "" else " · 선택") }, singleLine = true,
                visualTransformation = if (f.secret && reveal[f.key] != true) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (f.secret) KeyboardType.Password else KeyboardType.Text, autoCorrect = false),
                trailingIcon = if (f.secret) { { IconButton(onClick = { reveal[f.key] = reveal[f.key] != true }, modifier = Modifier.size(48.dp)) { Icon(if (reveal[f.key] == true) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal[f.key] == true) "숨기기" else "보기") } } } else null,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (fields.isNotEmpty()) {
            val canSave = fields.filter { it.required }.all { !values[it.key].isNullOrBlank() }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            DriveButton(if (cfg.ready) "새 값으로 다시 확인" else "저장하고 연결 테스트", canSave, busy = busy) { cvm.saveIntegration(p, values.toMap()); values.keys.filter { k -> fields.first { it.key == k }.secret }.forEach { values[it] = "" } }
            if (cfg.ready || cfg.status == IntegrationStatus.ERROR) TextButton(onClick = { cvm.removeIntegration(p) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("이 연결 삭제") }
        }
    }
}

/** Detail: playback & listening (§24 wording; L0/L1/L2 only in the diagnostics expander). */
@Composable fun PlaybackDetail(diagnosticState: String, hasMediaId: Boolean, positionMs: Long?, accessGranted: Boolean, onOpenNotificationSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsTitle("재생과 청취 학습", "음악 앱에서 재생하고, 확인된 것만 배웁니다")
        GlassSurface {
            Text("음악 앱에서 재생할 수 있어요", fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            Text("추천한 곡은 Spotify에서 재생됩니다. 첫 곡을 누르면 나머지 추천도 큐에 들어갑니다.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
        }
        GlassSurface {
            Text("재생 상태를 직접 받고 있어요", fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            Text("끝까지 들은 곡과 건너뛴 곡을 Spotify가 알려주고, 그것만 취향 학습에 씁니다. 원인을 확인할 수 없는 중단은 기록만 하고 배우지 않아요.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            // §24: a primary action that changes nothing is worse than no action. Notification
            // access did the observing for YouTube; Spotify reports its own state, so it moves
            // into diagnostics instead of sitting on the screen as a thing to fix.
            Expander("개발 진단") {
                Text("재생 연동 Spotify App Remote / 관측 상태 $diagnosticState / 재생 위치 ${positionMs?.let { "${it}ms" } ?: "미상"} / 알림 접근 " + (if (accessGranted) "허용" else "꺼짐") + " · Spotify에는 사용하지 않음", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
                TextButton(onClick = onOpenNotificationSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("알림 접근 설정 열기") }
            }
        }
    }
}

/** Detail: AI (§24). Toggle is only shown enabled once config is READY; effectiveEnabled drives the label. */
@Composable fun AiDetail(cvm: CatalogViewModel, gatewayReady: Boolean, requested: Boolean, driving: Boolean, onRequested: (Boolean) -> Unit, onSetupHelp: () -> Unit) {
    val integrations by cvm.integrations.collectAsStateWithLifecycleCompat(); val busy by cvm.busy.collectAsStateWithLifecycleCompat()
    val configReady = gatewayReady || integrations.getValue(ProviderId.GEMINI_DIRECT).ready || integrations.getValue(ProviderId.FIREBASE_AI).ready
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsTitle("AI 추천", "설정이 끝난 뒤에만 사용할 수 있어요")
        IntegrationCard(cvm, ProviderId.GEMINI_DIRECT, integrations.getValue(ProviderId.GEMINI_DIRECT), busy == ProviderId.GEMINI_DIRECT, driving,
            "가장 간단한 방법이에요. Google AI Studio에서 받은 개인 API 키와 모델 ID만 넣으면 됩니다. 키는 기기에 암호화 저장되는 개인용 모드이며, 배포용 앱에는 아래 Firebase 방식을 권합니다.")
        SectionTitle("또는 Firebase로 연결")
        IntegrationCard(cvm, ProviderId.FIREBASE_AI, integrations.getValue(ProviderId.FIREBASE_AI), busy == ProviderId.FIREBASE_AI, driving,
            "Firebase 콘솔의 프로젝트 ID, 앱 ID, 웹 API 키와 모델 ID를 입력하세요. 서비스 계정 키가 아니라 앱 구성 값입니다. 앱 등록과 App Check는 콘솔에서 따로 설정합니다.")
        GlassSurface {
            Text(when { !configReady -> "설정 필요"; IntegrationPolicy.effectiveEnabled(requested, configReady) -> "사용 중"; else -> "꺼짐" }, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            if (!configReady) { Text("구성이 없으면 기본 선곡을 사용합니다. 빌드에 google-services.json이 포함된 경우에도 동작합니다.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted); DriveButton("AI 연결 설정 안내") { onSetupHelp() } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Column(Modifier.weight(1f)) { Text("AI 추천 사용", fontWeight = FontWeight.SemiBold, fontSize = 16.sp); Text("답변, 후보 곡, 요약 반응을 Google에 전송합니다. 좌표·토큰은 보내지 않아요.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted) }
                Switch(checked = IntegrationPolicy.effectiveEnabled(requested, configReady), onCheckedChange = onRequested, enabled = configReady, modifier = Modifier.semantics { contentDescription = if (configReady) "AI 추천 사용" else "AI 추천 사용 · 설정 필요" })
            }
            if (configReady && !requested) Text("기본 선곡 사용 중", fontSize = 14.sp, color = DriveColors.Muted)
        }
    }
}

/** Detail: collection (§20 status, §24 rows). Counters from different sources are shown separately. */
@Composable fun CollectionDetail(vm: DriveViewModel, cvm: CatalogViewModel, driving: Boolean) {
    val c by cvm.collection.collectAsStateWithLifecycleCompat(); val s by cvm.summary.collectAsStateWithLifecycleCompat()
    val busy by cvm.busy.collectAsStateWithLifecycleCompat()
    LaunchedEffect(c, busy) { cvm.refreshSummary() }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsTitle("음악 정보 수집", "재생 없이 후보를 모아 둡니다")
        val pool by vm.poolStatus.collectAsStateWithLifecycleCompat()
        GlassSurface {
            // The phase came from the retired Catalog pipeline and read "연결 확인 필요" forever.
            // What matters is whether the pool selection reads has enough in it.
            Text(if (s.playable > 0) "후보 ${s.playable}곡 보유" else "후보를 아직 못 모았어요", fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            Text(c.lastSuccessAt?.let { "마지막 갱신 ${time(it)}" } ?: "아직 갱신한 적이 없어요", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            if (!driving) {
                ToggleRow("자동 수집", "약 6시간마다, 배터리·저장 공간이 충분할 때", c.autoEnabled, cvm::setAutoCollect)
                ToggleRow("Wi-Fi에서만", "모바일 데이터에서는 수집하지 않아요", c.unmeteredOnly, cvm::setUnmeteredOnly)
                DriveButton("지금 후보 보충", true, busy = busy != null) { cvm.topUpNow() }
            } else Text("정차 후 설정에서 바꿀 수 있어요.", fontSize = 14.sp, color = DriveColors.Muted)
        }
        GlassSurface {
            SectionTitle("보유 후보")
            Metric("재생 가능한 후보", "${s.playable}곡")
            Metric("장르 확인된 곡", "${s.genreTagged}곡")
            Metric("청취 기록 없는 후보", "${s.noHistory}곡")
            Text(pool, fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            Text("수집 건수는 학습이 아니에요. 청취 학습은 아래 별도로 표시합니다.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            if (s.stalled > 0) Expander("예전 수집 기록") {
                Text("예전 YouTube 수집 경로가 남긴 대기 항목 ${s.stalled}건이에요. 지금은 처리되지 않으며 선곡에도 쓰이지 않습니다.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
                if (!driving) TextButton(onClick = { cvm.clearStalledQueue() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("정리하기") }
            }
        }
        val listening by vm.listening.collectAsStateWithLifecycleCompat()
        GlassSurface {
            SectionTitle("청취 학습")
            Metric("유효 청취", "${listening.validTracks}곡 · ${listening.sessions}개 세션")
            Metric("관측된 재생", "${listening.observedAttempts}회")
            Text("끝까지 듣거나 앱에서 건너뛴 곡만 점수가 됩니다. 원인을 확인할 수 없는 중단은 기록만 하고 학습하지 않아요.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
        }
    }
}

/** Detail: location and weather (§24). The key has a field now, and the state says what is stored. */
@Composable fun WeatherDetail(vm: DriveViewModel, cvm: CatalogViewModel, weatherLabel: String, weatherDetail: String, driving: Boolean, onRefresh: () -> Unit) {
    val integrations by cvm.integrations.collectAsStateWithLifecycleCompat(); val busy by cvm.busy.collectAsStateWithLifecycleCompat()
    val locationStatus by vm.locationStatus.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsTitle("위치와 날씨", "대략 위치로 지역 날씨만 확인합니다")
        GlassSurface {
            Text(weatherLabel, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            // §7: source, region, observation time, lookup time and freshness, stated separately.
            Text(weatherDetail, fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            // §7: name the failure. "위치를 가져오지 못했어요" was the same sentence whether the
            // permission was refused, the radios were off, or the fix simply took too long.
            locationStatus?.takeIf { it != LocationStatus.AVAILABLE }?.let {
                Text(it.advice, fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Cyan)
            }
            Text("위치 권한을 허용하면 현재 지역의 실황을 가져옵니다. 권한이 없어도 추천은 동작해요.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            DriveButton("현재 위치로 날씨 갱신", !driving, onClick = onRefresh)
        }
        IntegrationCard(cvm, ProviderId.WEATHER, integrations.getValue(ProviderId.WEATHER), busy == ProviderId.WEATHER, driving,
            "Open-Meteo를 사용합니다. 키가 필요 없고, 위치는 약 11km 단위로 반올림해서 보냅니다.")
    }
}

@Composable fun PlacesDetail(vm: DriveViewModel, onRegisterHome: () -> Unit, onRegisterWork: () -> Unit, onDelete: () -> Unit, driving: Boolean) {
    val zones by vm.registeredZones.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshZones() }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsTitle("집과 회사", "현재 위치를 중심점으로 등록합니다")
        GlassSurface {
            Text("집 " + (if (Zone.HOME in zones) "등록됨" else "미등록") + " · 회사 " + (if (Zone.WORK in zones) "등록됨" else "미등록"),
                fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            Text("등록한 중심점과 반경만 기기에 암호화 저장합니다. 이동 경로는 저장하지 않아요.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            DriveButton(if (Zone.HOME in zones) "집 위치 다시 등록" else "현재 위치를 집으로 등록", !driving, onClick = onRegisterHome)
            DriveButton(if (Zone.WORK in zones) "회사 위치 다시 등록" else "현재 위치를 회사로 등록", !driving, onClick = onRegisterWork)
            Text("실내에서는 위치가 잡히지 않을 수 있어요. 창가나 실외에서 시도해 주세요.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            if (!driving) TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("등록 장소 삭제") }
        }
    }
}

// ---- building blocks (§24 type/spacing) ----
@Composable fun SettingsTitle(title: String, sub: String) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold); Text(sub, fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted) } }
@Composable fun SectionTitle(text: String) = Text(text, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
@Composable fun SummaryRow(icon: ImageVector, title: String, status: String, badge: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).semantics { contentDescription = "$title · $status" + (badge?.let { " · $it" } ?: "") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = DriveColors.Muted)
        Column(Modifier.weight(1f)) { Text(title, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium); Text(status, fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted) }
        if (badge != null) Surface(shape = MaterialTheme.shapes.small, color = DriveColors.High) { Text(badge, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
        Icon(Icons.Outlined.ChevronRight, null, tint = DriveColors.Muted)
    }
}
@Composable fun ToggleRow(title: String, description: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp); Text(description, fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted) }; Switch(checked = value, onCheckedChange = onChange) }
}
@Composable fun Metric(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, fontSize = 16.sp, lineHeight = 24.sp); Text(value, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold) } }
@Composable fun Expander(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (open) "$title 닫기" else "$title 보기") }
    if (open) content()
}
/** Offers the newer build when CI has published one; installing stays a user action. */
@Composable fun UpdateRow() {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { UpdateManager.prepare(context) }
    when (val s = state) {
        is UpdateState.Downloading -> GlassSurface {
            Text("새 버전을 내려받는 중 ${s.percent}%", fontSize = 16.sp, lineHeight = 24.sp)
            LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.Ready -> GlassSurface {
            Text("새 버전 ${s.info.versionName} 준비됨", fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            Text("설치된 버전은 ${s.info.installedVersionName}입니다. 설정과 기록은 그대로 유지됩니다.", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            DriveButton("지금 설치") { UpdateManager.install(context, s.file) }
        }
        is UpdateState.Failed -> Text("업데이트 확인 실패 · ${s.reason}", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
        else -> Text("최신 버전을 사용 중이에요", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
    }
}

/**
 * Which build is installed (§24 — plain text, no dev jargon). The install time comes from the
 * package manager rather than a build constant, so it tells the user whether this APK is the one
 * they just sideloaded without the build script having to stamp anything.
 */
@Composable fun BuildStamp() {
    val context = LocalContext.current
    val updatedAt = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrNull()
    }
    val label = "DriveMuse ${BuildConfig.VERSION_NAME} (빌드 ${BuildConfig.VERSION_CODE})" + (updatedAt?.let { " · 설치 ${time(it)}" } ?: "")
    Text(label, fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted,
        modifier = Modifier.semantics { contentDescription = "설치된 앱 " + label })
}
private fun time(ms: Long) = DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
@Composable fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat(): State<T> = collectAsStateWithLifecycle()
