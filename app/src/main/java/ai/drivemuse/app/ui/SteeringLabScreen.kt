package ai.drivemuse.app.ui

import ai.drivemuse.app.DriveViewModel
import ai.drivemuse.app.OperationRegistry
import ai.drivemuse.app.Settings
import ai.drivemuse.app.steering.RawKey
import ai.drivemuse.app.steering.SteeringKeyTap
import ai.drivemuse.designsystem.*
import ai.drivemuse.domain.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.KeyEvent as AndroidKeyEvent

/**
 * 설정 → 실험실 → 핸들 단축 조작.
 *
 * Everything reads UNTESTED until a car says otherwise, and the master switch stays off and
 * inoperable while that is true. This is not a placeholder for a feature that exists and is
 * disabled; the feature does not exist yet, and the screen says which step is missing.
 */
@Composable fun SteeringLab(vm: DriveViewModel, settings: Settings, driving: Boolean, onOpen: (String) -> Unit) {
    val records by vm.capabilities.collectAsStateWithLifecycle()
    val vehicle = settings.vehicleId.ifBlank { "unknown" }
    val anyUsable = Shortcut.entries.any { s -> records.firstOrNull { it.shortcut == s && it.vehicleId == vehicle }?.capability?.usable == true }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsTitle("핸들 단축 조작",
            "기존 버튼 동작에 선곡 요청과 평가 기능을 더합니다. 길게 누른 뒤 떼면 실행됩니다. 차량에 따라 지원되지 않을 수 있습니다.")

        GlassSurface {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("핸들 단축 조작 사용", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(if (anyUsable) "지원이 확인된 조작만 동작합니다"
                         else "지원이 확인된 조작이 없어 켤 수 없어요", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
                }
                Switch(checked = settings.steeringEnabled && anyUsable, enabled = !driving && anyUsable,
                    onCheckedChange = { vm.steeringEnabled(it) })
            }
            Text("차량 ${settings.vehicleName.ifBlank { "미등록" }}", fontSize = 14.sp, color = DriveColors.Muted)
        }

        GlassSurface {
            Text("조작별 지원 상태", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Shortcut.entries.forEach { shortcut ->
                val record = records.firstOrNull { it.shortcut == shortcut && it.vehicleId == vehicle }
                val capability = record?.capability ?: InputCapability.UNTESTED
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(shortcut.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(capability.label + record?.conditions.orEmpty().joinToString("") { " · ${it.label}" },
                        fontSize = 14.sp, lineHeight = 20.sp,
                        color = if (capability.usable) DriveColors.Cyan else DriveColors.Muted)
                    if (record == null) Text("아직 확인하지 않았어요", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
                    else if (record.note.isNotBlank()) Text(record.note, fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
                }
            }
            DriveButton("정차 중 버튼 테스트", !driving) { onOpen("설정/핸들진단") }
        }

        GlassSurface {
            Text("아직 못 하는 것", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            // Being precise about which step is missing is the point of §10's warning: a diagnostic
            // that cannot reach the buttons is a real result, not a reason to keep asking the user.
            Text("버튼을 눌렀을 때 추가 기능이 실행되는 부분은 아직 만들지 않았습니다. 지금 할 수 있는 것은 차량이 버튼 입력을 앱까지 보내주는지 확인하는 진단뿐이고, 진단은 앱 화면이 켜져 있는 동안만 동작합니다. 음악을 들으며 화면을 끈 상태에서도 받을 수 있는지는 이 화면으로 확인할 수 없습니다.",
                fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
        }
    }
}

/**
 * The stationary diagnostic. It records what arrived, replays it through the reducer in dry run,
 * and shows what would have happened — it never executes a shortcut.
 */
@Composable fun SteeringDiagnostic(vm: DriveViewModel, settings: Settings, driving: Boolean) {
    val raw by SteeringKeyTap.events.collectAsStateWithLifecycle()
    val records by vm.capabilities.collectAsStateWithLifecycle()
    var confirmed by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { SteeringKeyTap.stop() } }
    LaunchedEffect(driving) { if (driving) { listening = false; SteeringKeyTap.stop() } }

    val vehicle = settings.vehicleId.ifBlank { "unknown" }
    val runs = remember(raw) { replay(raw) }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsTitle("정차 중 버튼 테스트", "핸들 버튼을 눌러 이 화면이 무엇을 받는지 확인합니다")

        GlassSurface {
            Text("추가 기능은 실행하지 않습니다(DRY_RUN). 다만 재생·다음 같은 기본 버튼 동작은 평소대로 일어날 수 있습니다. 이 진단은 앱 화면이 켜져 있는 동안만 입력을 받습니다.",
                fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            if (!confirmed) {
                // §8: an answer, not a sensor reading, and never displayed as one.
                Text("차량이 정차 중인지 직접 확인해 주세요. 앱은 정차 여부를 센서로 확인하지 못합니다.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Cyan)
                DriveButton("정차 중입니다 · 테스트 시작", !driving) { confirmed = true; listening = true; SteeringKeyTap.start() }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DriveButton(if (listening) "입력 받는 중 · 중지" else "다시 시작", !driving) {
                        listening = !listening
                        if (listening) SteeringKeyTap.start() else SteeringKeyTap.stop()
                    }
                    TextButton(onClick = { SteeringKeyTap.clear() }) { Text("기록 지우기") }
                }
            }
        }

        GlassSurface {
            Text("받은 입력 ${raw.size}건", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (raw.isEmpty()) Text(
                if (listening) "아직 아무것도 오지 않았어요. 핸들의 다음 곡·이전 곡·재생 버튼을 눌러 보세요. 아무것도 오지 않는다면 이 차량·연결 방식에서는 앱이 버튼 입력을 받지 못한다는 뜻입니다."
                else "테스트를 시작하면 여기에 표시됩니다.",
                fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            raw.takeLast(12).reversed().forEach { key ->
                Text("${key.keyName} ${if (key.isDown) "누름" else "뗌"}" +
                     (if (key.repeatCount > 0) " · 반복 ${key.repeatCount}" else "") +
                     (if (!key.isDown) " · ${key.heldMs}ms" else "") +
                     (if (key.canceled) " · 취소됨" else "") +
                     " · ${key.deviceName}",
                    fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            }
        }

        GlassSurface {
            Text("판정 결과", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("같은 입력을 실제 판정 로직에 그대로 넣어 본 결과입니다. 명령은 실행하지 않았습니다.",
                fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            Shortcut.entries.forEach { shortcut ->
                val run = runs[shortcut] ?: DiagnosticRun(shortcut)
                val existing = records.firstOrNull { it.shortcut == shortcut && it.vehicleId == vehicle }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(shortcut.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("확정 ${run.gesturesConfirmed}회" +
                         (if (run.discards.isEmpty()) "" else " · 폐기 " + run.discards.distinct().joinToString(", ") { it.label }),
                        fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
                    Text("이 결과로 저장하면: ${run.proposed().label}", fontSize = 14.sp, lineHeight = 20.sp,
                        color = if (run.proposed().usable) DriveColors.Cyan else DriveColors.Muted)
                    if (run.attempts > 0) TextButton(
                        onClick = {
                            vm.saveCapability(CapabilityRecord(
                                vehicleId = vehicle,
                                transport = Transport.UNKNOWN,
                                shortcut = shortcut,
                                capability = run.proposed(),
                                // A foreground run proves a foreground path and nothing more.
                                conditions = if (run.proposed().usable) setOf(CapabilityCondition.FOREGROUND_ONLY) else emptySet(),
                                checkedAt = System.currentTimeMillis(),
                                appVersionCode = ai.drivemuse.app.BuildConfig.VERSION_CODE,
                                osBuild = android.os.Build.DISPLAY,
                                playerVersion = "unknown",
                                note = "정차 진단 ${run.gesturesConfirmed}/${run.attempts}"))
                        },
                        enabled = !driving
                    ) { Text(if (existing == null) "이 결과 저장" else "결과 갱신") }
                    OperationStatus(rememberOperation(vm, OperationRegistry.capability(shortcut.name)),
                        onDismiss = { vm.dismissOperation(OperationRegistry.capability(shortcut.name)) },
                        onRetry = { vm.retryOperation(OperationRegistry.capability(shortcut.name)) })
                }
            }
        }

        GlassSurface {
            Text("이 진단이 증명하지 못하는 것", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("화면이 꺼져 있거나 다른 앱이 앞에 있을 때, Spotify가 미디어 세션을 쥐고 있을 때도 같은 입력이 오는지는 확인하지 못합니다. 그래서 여기서 성공해도 ‘조건부 지원’까지만 기록합니다.",
                fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
        }
    }
}

private val DIAGNOSTIC_KEYS = mapOf(
    AndroidKeyEvent.KEYCODE_MEDIA_NEXT to SteeringKey.NEXT,
    AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS to SteeringKey.PREVIOUS,
    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to SteeringKey.PLAY_PAUSE,
    AndroidKeyEvent.KEYCODE_HEADSETHOOK to SteeringKey.PLAY_PAUSE
)

/**
 * Replays the captured events through the real reducer with every mapping switched on, so the
 * screen reports what the shipped logic would decide rather than a second implementation of it.
 */
private fun replay(raw: List<RawKey>): Map<Shortcut, DiagnosticRun> {
    val reducer = SteeringGestureReducer()
    var state = SteeringState.initial(Shortcut.entries.toSet())
    val runs = Shortcut.entries.associateWith { DiagnosticRun(it) }.toMutableMap()
    var pressesFor: SteeringKey? = null

    raw.forEach { key ->
        val mapped = DIAGNOSTIC_KEYS[key.keyCode] ?: return@forEach
        if (key.isDown && key.repeatCount == 0) {
            pressesFor = mapped
            // One attempt per press, counted against whichever mappings use that key.
            Shortcut.entries.filter { it.key == mapped }.forEach { s ->
                runs[s] = runs.getValue(s).let { it.copy(attempts = it.attempts + 1) }
            }
        }
        val input = SteeringInput(
            eventId = "${key.deviceId}:${key.downTime}:${key.eventTime}:${key.isDown}",
            inputSourceId = key.deviceName,
            transport = Transport.UNKNOWN,
            tripId = null,
            connectionEpoch = 1,
            mediaSessionEpoch = 1,
            key = mapped,
            action = if (key.isDown) KeyAction.DOWN else KeyAction.UP,
            downTimeElapsed = key.downTime,
            eventTimeElapsed = key.eventTime,
            repeatCount = key.repeatCount,
            canceled = key.canceled,
            baseHandlingOwner = BaseHandlingOwner.UNKNOWN
        )
        val result = reducer.reduce(state, SteeringEvent.Key(input, null))
        state = result.state
        result.effects.forEach { effect ->
            when (effect) {
                is SteeringEffect.Emit -> Shortcut.entries
                    .firstOrNull { it.action == effect.command.action }
                    ?.let { s -> runs[s] = runs.getValue(s).let { it.copy(gesturesConfirmed = it.gesturesConfirmed + 1) } }
                is SteeringEffect.Discard -> {
                    val target = effect.shortcut ?: pressesFor?.let { k -> Shortcut.entries.firstOrNull { it.key == k } }
                    target?.let { s -> runs[s] = runs.getValue(s).let { it.copy(discards = it.discards + effect.reason) } }
                }
                is SteeringEffect.DispatchBase -> Shortcut.entries.filter { it.key == effect.key }.forEach { s ->
                    runs[s] = runs.getValue(s).let { it.copy(baseDispatches = it.baseDispatches + 1) }
                }
                is SteeringEffect.ScheduleTimeout -> Unit
            }
        }
    }
    return runs
}
