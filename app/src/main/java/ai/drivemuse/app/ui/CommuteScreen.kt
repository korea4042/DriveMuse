package ai.drivemuse.app.ui

import ai.drivemuse.app.DriveViewModel
import ai.drivemuse.app.OperationRegistry
import ai.drivemuse.designsystem.*
import ai.drivemuse.domain.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

private val DAY_LABELS = listOf(
    DayOfWeek.MONDAY to "월", DayOfWeek.TUESDAY to "화", DayOfWeek.WEDNESDAY to "수",
    DayOfWeek.THURSDAY to "목", DayOfWeek.FRIDAY to "금", DayOfWeek.SATURDAY to "토", DayOfWeek.SUNDAY to "일"
)

private enum class ScheduleAction { SAVE, DELETE }

private fun blank(direction: CommuteDirection) = CommuteSchedule(
    id = "commute.${direction.name}",
    direction = direction,
    weekdays = CommuteSchedules.WEEKDAYS,
    departureLocalTime = if (direction == CommuteDirection.TO_WORK) LocalTime.of(8, 0) else LocalTime.of(18, 30),
    timezoneId = ZoneId.systemDefault().id
)

/**
 * §3. The fixed 06:30–10:30 and 17:00–22:30 windows are gone; this is where the replacement comes
 * from. The screen shows the resulting range rather than a hidden ±30, because "07:00~08:00" is a
 * thing a user can check and "앞뒤 30분" is a thing they have to work out.
 */
@Composable fun CommuteDetail(vm: DriveViewModel, driving: Boolean) {
    val stored by vm.schedules.collectAsStateWithLifecycle()
    val zones by vm.registeredZones.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshZones() }

    var morning by remember(stored) {
        mutableStateOf(stored.firstOrNull { it.direction == CommuteDirection.TO_WORK } ?: blank(CommuteDirection.TO_WORK))
    }
    var evening by remember(stored) {
        mutableStateOf(stored.firstOrNull { it.direction == CommuteDirection.TO_HOME } ?: blank(CommuteDirection.TO_HOME))
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsTitle("출퇴근과 이동 판단", "설정한 시간과 출발 장소로 상황을 추정합니다. 설정하지 않아도 일반 선곡은 그대로 동작해요")

        ScheduleCard(morning, zones, driving, vm, saved = stored.any { it.id == morning.id }, onChange = { morning = it })
        ScheduleCard(evening, zones, driving, vm, saved = stored.any { it.id == evening.id }, onChange = { evening = it },
            onCopyDays = { evening = CommuteSchedules.copyWeekdays(morning, evening) })

        GlassSurface {
            Text("지금 판단", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            // §4: the reasons, with what is missing named. No confidence percentage.
            Text(ui.assessment?.describe() ?: ui.reason, fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
            ui.assessment?.let { a ->
                if (a.missing.isNotEmpty()) Text(
                    "부족한 근거: " + a.missing.joinToString(", ") { it.label },
                    fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Cyan)
            }
            OperationStatus(rememberOperation(vm, OperationRegistry.DEPARTURE),
                onDismiss = { vm.dismissOperation(OperationRegistry.DEPARTURE) })
        }

        GlassSurface {
            Text("경로 학습", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            // Not a disabled toggle: a switch that cannot be switched invites the reading that the
            // feature exists and is off. It does not exist yet.
            Text("아직 제공하지 않습니다. 반복되는 출퇴근 경로 학습은 위치 수집 기능과 함께 추가될 예정이며, 기본은 꺼짐이고 켤 때 저장 범위와 보존기간을 먼저 안내합니다.",
                fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
        }
    }
}

@Composable private fun ScheduleCard(
    schedule: CommuteSchedule,
    zones: Set<Zone>,
    driving: Boolean,
    vm: DriveViewModel,
    saved: Boolean,
    onChange: (CommuteSchedule) -> Unit,
    onCopyDays: (() -> Unit)? = null
) {
    var picking by remember { mutableStateOf(false) }
    // Save and delete share one operation target so they cannot run at once, which means the
    // status line's retry has to know which of the two failed. It used to always call save, so
    // retrying a failed delete wrote the schedule back.
    var pending by remember(schedule.id) { mutableStateOf(ScheduleAction.SAVE) }
    fun save() { pending = ScheduleAction.SAVE; vm.saveSchedule(schedule) }
    fun delete() { pending = ScheduleAction.DELETE; vm.deleteSchedule(schedule.direction, schedule.id) }
    val place = if (schedule.direction == CommuteDirection.TO_WORK) Zone.HOME else Zone.WORK
    val placeName = if (place == Zone.HOME) "집" else "회사"

    GlassSurface {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${schedule.direction.label} 일정", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            Switch(checked = schedule.enabled, enabled = !driving, onCheckedChange = { onChange(schedule.copy(enabled = it)) })
        }

        Text("요일", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DAY_LABELS.forEach { (day, label) ->
                val on = day in schedule.weekdays
                DayToggle(label, on, !driving) {
                    onChange(schedule.copy(weekdays = if (on) schedule.weekdays - day else schedule.weekdays + day))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onChange(schedule.copy(weekdays = CommuteSchedules.WEEKDAYS)) }, enabled = !driving) { Text("평일") }
            TextButton(onClick = { onChange(schedule.copy(weekdays = CommuteSchedules.EVERY_DAY)) }, enabled = !driving) { Text("매일") }
            if (onCopyDays != null) TextButton(onClick = onCopyDays, enabled = !driving) { Text("출근 요일 복사") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("출발 시간", fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Muted)
                Text("%02d:%02d".format(schedule.departureLocalTime.hour, schedule.departureLocalTime.minute),
                    fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { picking = true }, enabled = !driving) { Text("변경") }
        }

        Text("판단 범위 ${schedule.windowLabel}" + if (schedule.crossesMidnight) " · 자정을 넘습니다" else "",
            fontSize = 14.sp, lineHeight = 20.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("앞", fontSize = 14.sp, color = DriveColors.Muted)
            Stepper(schedule.beforeMinutes, !driving) { onChange(schedule.copy(beforeMinutes = it)) }
            Text("뒤", fontSize = 14.sp, color = DriveColors.Muted)
            Stepper(schedule.afterMinutes, !driving) { onChange(schedule.copy(afterMinutes = it)) }
            Text("분", fontSize = 14.sp, color = DriveColors.Muted)
        }

        Text(
            if (place in zones) "출발 장소: $placeName 등록됨"
            else "출발 장소: $placeName 미등록 · 등록해야 이 일정으로 추정할 수 있어요",
            fontSize = 14.sp, lineHeight = 20.sp,
            color = if (place in zones) DriveColors.Muted else DriveColors.Cyan)

        if (schedule.timezoneId != ZoneId.systemDefault().id) Text(
            "일정 기준 시간대 ${schedule.timezoneId} · 기기 시간대 ${ZoneId.systemDefault().id}",
            fontSize = 14.sp, lineHeight = 20.sp, color = DriveColors.Cyan)

        DriveButton("${schedule.direction.label} 일정 저장", !driving) { save() }
        if (saved) TextButton(onClick = { delete() }, enabled = !driving) {
            Text("${schedule.direction.label} 일정 삭제")
        }
        OperationStatus(rememberOperation(vm, OperationRegistry.schedule(schedule.direction.name)),
            onDismiss = { vm.dismissOperation(OperationRegistry.schedule(schedule.direction.name)) },
            onRetry = { if (pending == ScheduleAction.DELETE) delete() else save() })
    }

    if (picking) TimePickerDialog(schedule.departureLocalTime, onDismiss = { picking = false }) {
        onChange(schedule.copy(departureLocalTime = it)); picking = false
    }
}

@Composable private fun DayToggle(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = if (selected) DriveColors.Blue else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) DriveColors.Blue else DriveColors.Muted),
        modifier = Modifier.size(48.dp).semantics { contentDescription = "${label}요일 ${if (selected) "선택됨" else "선택 안 됨"}" }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

/** Fifteen-minute steps, 0 to 120. Typing a number into a range this coarse helps nobody. */
@Composable private fun Stepper(minutes: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange((minutes - 15).coerceAtLeast(0)) }, enabled = enabled && minutes > 0) { Text("−") }
        Text("$minutes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { onChange((minutes + 15).coerceAtMost(120)) }, enabled = enabled && minutes < 120) { Text("+") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        text = { TimePicker(state = state) }
    )
}
