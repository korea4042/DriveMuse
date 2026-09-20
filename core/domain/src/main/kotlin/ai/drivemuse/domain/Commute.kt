package ai.drivemuse.domain

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

enum class CommuteDirection(val label: String) { TO_WORK("출근"), TO_HOME("퇴근") }

/**
 * One departure the user actually told us about, replacing the hardcoded 06:30–10:30 and
 * 17:00–22:30 windows that no shift worker, no school run and no flexible office ever matched.
 *
 * The weekday set is the weekday of the *scheduled departure*, not of the moment being judged. A
 * Monday 00:15 departure with a 30-minute lead-in means Sunday 23:45 is inside a Monday schedule;
 * asking "is today Monday" at 23:45 on Sunday would answer no and miss it.
 */
data class CommuteSchedule(
    val id: String,
    val direction: CommuteDirection,
    val weekdays: Set<DayOfWeek>,
    val departureLocalTime: LocalTime,
    val beforeMinutes: Int = 30,
    val afterMinutes: Int = 30,
    /** The zone the user set this in. Kept so a trip abroad does not silently reinterpret it. */
    val timezoneId: String,
    val enabled: Boolean = true,
    val revision: Int = 0
) {
    val zone: ZoneId get() = runCatching { ZoneId.of(timezoneId) }.getOrDefault(ZoneId.systemDefault())
    val windowStart: LocalTime get() = departureLocalTime.minusMinutes(beforeMinutes.toLong())
    val windowEnd: LocalTime get() = departureLocalTime.plusMinutes(afterMinutes.toLong())
    /** "07:00~08:00", the range §3 wants shown rather than a hidden ±30. */
    val windowLabel: String get() = "%02d:%02d~%02d:%02d".format(
        windowStart.hour, windowStart.minute, windowEnd.hour, windowEnd.minute)
    /** LocalTime wraps, so a window that ends before it starts is one that crosses midnight. */
    val crossesMidnight: Boolean get() = windowStart > windowEnd

    /**
     * Distance from [at] to the nearest scheduled departure this schedule covers, or null when
     * [at] is outside every window. Days either side are considered so a window spanning midnight
     * is found from whichever side of it the clock is on.
     */
    fun minutesFromDeparture(at: ZonedDateTime): Long? {
        if (!enabled || weekdays.isEmpty()) return null
        val local = at.withZoneSameInstant(zone)
        var best: Long? = null
        for (offset in -1L..1L) {
            val date = local.toLocalDate().plusDays(offset)
            if (date.dayOfWeek !in weekdays) continue
            // ZonedDateTime.of resolves a DST gap forward rather than throwing, so a departure at
            // an hour that does not exist that day still yields a real instant.
            val departure = ZonedDateTime.of(date, departureLocalTime, zone)
            val delta = java.time.Duration.between(departure, local).toMinutes()
            if (delta < -beforeMinutes || delta > afterMinutes) continue
            if (best == null || abs(delta) < abs(best!!)) best = delta
        }
        return best
    }

    fun matches(at: ZonedDateTime) = minutesFromDeparture(at) != null
}

object CommuteSchedules {
    /** Weekday shorthand for the "평일" button. */
    val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    val EVERY_DAY = DayOfWeek.entries.toSet()

    /** 출근 leaves home, 퇴근 leaves work. There is no third reading of the departure zone. */
    fun expectedOrigin(direction: CommuteDirection) =
        if (direction == CommuteDirection.TO_WORK) Zone.HOME else Zone.WORK

    /**
     * The schedule covering [at], chosen deterministically when several overlap: nearest scheduled
     * departure first, then 출근 before 퇴근, then id. Overlapping schedules are a thing users will
     * create by accident, and two runs of the same app on the same minute must not disagree.
     */
    fun match(schedules: List<CommuteSchedule>, at: ZonedDateTime): CommuteSchedule? =
        schedules.mapNotNull { s -> s.minutesFromDeparture(at)?.let { s to abs(it) } }
            .minWithOrNull(compareBy({ it.second }, { it.first.direction.ordinal }, { it.first.id }))
            ?.first

    /**
     * The nearest schedule that the car could actually be on, given where it set off.
     *
     * Picking the nearest schedule first and checking the origin afterwards threw away real
     * commutes: leaving home at 16:50 with a 17:00 퇴근 window and an 08:00–17:30 출근 window both
     * open would choose 퇴근, find the origin was HOME rather than WORK, and fall through to a
     * general drive — even though the 출근 schedule matched and agreed with the departure.
     */
    fun matchFrom(schedules: List<CommuteSchedule>, at: ZonedDateTime, origin: Zone): CommuteSchedule? =
        if (origin == Zone.UNKNOWN) null
        else match(schedules.filter { expectedOrigin(it.direction) == origin }, at)

    /** §3: 퇴근 copies 출근's days once, then diverges. No hidden two-way sync. */
    fun copyWeekdays(from: CommuteSchedule, to: CommuteSchedule) = to.copy(weekdays = from.weekdays, revision = to.revision + 1)
}

// --- §4: the situation is several attributes, not one enum ---

enum class ContextPurpose(val label: String) {
    COMMUTE_TO_WORK("출근길"), COMMUTE_HOME("퇴근길"), TRAVEL("여행"), GENERAL("드라이브"), UNKNOWN("연결 대기")
}

enum class ContextBasis(val label: String) {
    MANUAL("직접 선택"), ESTIMATED("추정"), ARRIVAL_CONFIRMED("도착 확인")
}

enum class DistanceMode { NORMAL, LONG_DISTANCE }

enum class RouteStatus { NOT_READY, MATCHING, DEVIATING, UNKNOWN }

enum class TimeBand(val label: String) { DAY("주간"), NIGHT("야간") }

enum class ContextEvidence(val label: String) {
    MANUAL_CHOICE("직접 선택한 상황"),
    VEHICLE_CONNECTED("차량 연결"),
    ORIGIN_HOME("집에서 출발"),
    ORIGIN_WORK("회사에서 출발"),
    SCHEDULE_MATCH("설정한 출퇴근 시간대"),
    NIGHT("야간 시간대"),
    LONG_DISTANCE("장거리 이동"),
    ROUTE_MATCHING("평소 경로와 일치"),
    ROUTE_DEVIATING("평소 경로와 다른 이동")
}

enum class MissingSignal(val label: String) {
    VEHICLE("차량 미연결"),
    LOCATION("위치 미확인"),
    ORIGIN("출발 영역 미확인"),
    DESTINATION("도착지는 확인되지 않음"),
    SCHEDULE("출퇴근 일정 미설정"),
    ROUTE("평소 경로를 아직 학습하지 않음")
}

/**
 * §4: purpose, basis, distance, route and time band are separate axes, so "퇴근 추정 + 야간 +
 * 장거리" can all be true at once. The old single `DriveContext` could hold exactly one of them.
 */
data class ContextAssessment(
    val purpose: ContextPurpose,
    val basis: ContextBasis,
    val timeBand: TimeBand,
    val distanceMode: DistanceMode = DistanceMode.NORMAL,
    val routeStatus: RouteStatus = RouteStatus.NOT_READY,
    val evidence: List<ContextEvidence> = emptyList(),
    val missing: List<MissingSignal> = emptyList(),
    val assessedAt: Long = 0,
    val contextRevision: Long = 0
) {
    /**
     * The lossy projection onto the rule-scoping enum, which predates this split and still holds
     * one value. Night survives only when there is no commute purpose to lose; a night commute
     * keeps its commute rules, and `timeBand` remains available to anything that needs both.
     */
    val driveContext: DriveContext get() = when (purpose) {
        ContextPurpose.COMMUTE_TO_WORK -> DriveContext.COMMUTE_TO_WORK
        ContextPurpose.COMMUTE_HOME -> DriveContext.COMMUTE_HOME
        ContextPurpose.TRAVEL -> DriveContext.TRAVEL
        ContextPurpose.GENERAL -> if (timeBand == TimeBand.NIGHT) DriveContext.NIGHT_DRIVE else DriveContext.GENERAL_DRIVE
        ContextPurpose.UNKNOWN -> DriveContext.UNKNOWN
    }

    /**
     * §4: show the reasons, never a number. "확신도 75%" was a weighted sum of hand-picked
     * constants presented as a measurement.
     */
    fun describe(): String {
        val parts = buildList {
            add(purpose.label + if (basis == ContextBasis.ESTIMATED && purpose != ContextPurpose.UNKNOWN) " 추정" else "")
            addAll(evidence.map { it.label })
            addAll(missing.map { it.label })
        }
        return parts.joinToString(" · ")
    }
}

data class ContextInput(
    val connected: Boolean,
    val at: ZonedDateTime,
    /** §4 priority 1: a direct choice outranks every inference for the rest of this drive. */
    val manual: ContextPurpose? = null,
    /** The departure snapshot from §5, not the latest position sample. */
    val originZone: Zone = Zone.UNKNOWN,
    /**
     * When the drive began. The schedule is judged against this, not against [at]: a commute that
     * started at 07:50 inside a 07:00–08:00 window is still a commute when reassessed at 08:01,
     * and re-reading the clock every few minutes used to turn it into a general drive mid-drive.
     * Null before a departure has been captured, in which case [at] stands in.
     */
    val departedAt: ZonedDateTime? = null,
    val schedules: List<CommuteSchedule> = emptyList(),
    val routeStatus: RouteStatus = RouteStatus.NOT_READY,
    val distanceMode: DistanceMode = DistanceMode.NORMAL,
    val nightStart: LocalTime = LocalTime.of(22, 0),
    val nightEnd: LocalTime = LocalTime.of(6, 0),
    val assessedAt: Long = 0,
    val contextRevision: Long = 0
)

object ContextEstimator {

    fun timeBand(at: ZonedDateTime, nightStart: LocalTime, nightEnd: LocalTime): TimeBand {
        val t = at.toLocalTime()
        val night = if (nightStart <= nightEnd) t >= nightStart && t < nightEnd
                    else t >= nightStart || t < nightEnd
        return if (night) TimeBand.NIGHT else TimeBand.DAY
    }

    /**
     * §4's priority order. This is an estimate of what the drive is, and nothing more: it is not
     * permission to start playing. The playback controller checks connection, stop state and the
     * connection epoch separately, and none of those appear here.
     */
    fun assess(input: ContextInput): ContextAssessment {
        val band = timeBand(input.at, input.nightStart, input.nightEnd)
        val evidence = mutableListOf<ContextEvidence>()
        val missing = mutableListOf<MissingSignal>()

        if (band == TimeBand.NIGHT) evidence += ContextEvidence.NIGHT
        if (input.distanceMode == DistanceMode.LONG_DISTANCE) evidence += ContextEvidence.LONG_DISTANCE
        when (input.routeStatus) {
            RouteStatus.MATCHING -> evidence += ContextEvidence.ROUTE_MATCHING
            RouteStatus.DEVIATING -> evidence += ContextEvidence.ROUTE_DEVIATING
            RouteStatus.NOT_READY -> missing += MissingSignal.ROUTE
            RouteStatus.UNKNOWN -> Unit
        }

        fun result(purpose: ContextPurpose, basis: ContextBasis) = ContextAssessment(
            purpose, basis, band, input.distanceMode, input.routeStatus,
            evidence.toList(), missing.toList(), input.assessedAt, input.contextRevision)

        // 1. A choice the driver made this trip.
        input.manual?.let {
            evidence.add(0, ContextEvidence.MANUAL_CHOICE)
            return result(it, ContextBasis.MANUAL)
        }

        if (!input.connected) {
            missing.add(0, MissingSignal.VEHICLE)
            return result(ContextPurpose.UNKNOWN, ContextBasis.ESTIMATED)
        }
        evidence.add(0, ContextEvidence.VEHICLE_CONNECTED)

        when (input.originZone) {
            Zone.HOME -> evidence += ContextEvidence.ORIGIN_HOME
            Zone.WORK -> evidence += ContextEvidence.ORIGIN_WORK
            Zone.UNKNOWN -> missing += MissingSignal.LOCATION
            else -> missing += MissingSignal.ORIGIN
        }

        val usable = input.schedules.filter { it.enabled }
        if (usable.isEmpty()) missing += MissingSignal.SCHEDULE

        // 2. Departure zone plus the user's own schedule, both read at the departure.
        val judgedAt = input.departedAt ?: input.at
        if (CommuteSchedules.match(usable, judgedAt) != null) evidence += ContextEvidence.SCHEDULE_MATCH
        val match = CommuteSchedules.matchFrom(usable, judgedAt, input.originZone)
        if (match != null) {
            // CTX10: the destination is never claimed. Leaving home inside the morning window is a
            // commute hypothesis and the missing arrival stays on the screen.
            missing += MissingSignal.DESTINATION
            return result(
                if (match.direction == CommuteDirection.TO_WORK) ContextPurpose.COMMUTE_TO_WORK else ContextPurpose.COMMUTE_HOME,
                ContextBasis.ESTIMATED)
        }

        // 3–4. Route-supported commutes outside the schedule, and travel, need learned routes.
        // Until those exist the honest answer is a general drive, not a guess dressed as one.
        return result(ContextPurpose.GENERAL, ContextBasis.ESTIMATED)
    }
}

/**
 * Serialisation for the preference store.
 *
 * Deliberately not JSON: the codec has to be covered by tests, and `org.json` is a stub on the
 * unit-test classpath, so a JSON version could only be exercised on a device or behind an extra
 * dependency. The round trip matters more than the format, because saving now confirms by reading
 * the record back and comparing it — a codec that loses a field would make every save fail.
 *
 * Unit and record separators are used as delimiters; neither can occur in a zone id, a weekday
 * name or an id this app generates.
 */
object CommuteCodec {
    private const val FIELD = '\u001f'
    private const val RECORD = '\u001e'
    private const val VERSION = "v1"

    fun encode(schedules: List<CommuteSchedule>): String = schedules.joinToString(RECORD.toString()) { s ->
        listOf(
            VERSION, s.id, s.direction.name,
            s.weekdays.sortedBy { it.value }.joinToString(",") { it.name },
            s.departureLocalTime.toString(), s.beforeMinutes.toString(), s.afterMinutes.toString(),
            s.timezoneId, s.enabled.toString(), s.revision.toString()
        ).joinToString(FIELD.toString())
    }

    /**
     * Lenient per record, strict about the result: one unreadable row is dropped rather than
     * taking every other schedule down with it. Losing a whole commute setup because one field
     * went bad in an upgrade is the worse failure.
     */
    fun decode(raw: String): List<CommuteSchedule> {
        if (raw.isBlank()) return emptyList()
        return raw.split(RECORD).mapNotNull { record ->
            runCatching {
                val f = record.split(FIELD)
                require(f.size >= 10 && f[0] == VERSION)
                CommuteSchedule(
                    id = f[1].also { require(it.isNotBlank()) },
                    direction = CommuteDirection.valueOf(f[2]),
                    weekdays = f[3].split(",").filter { it.isNotBlank() }
                        .mapNotNull { d -> runCatching { java.time.DayOfWeek.valueOf(d) }.getOrNull() }.toSet(),
                    departureLocalTime = java.time.LocalTime.parse(f[4]),
                    beforeMinutes = f[5].toInt().coerceIn(0, 180),
                    afterMinutes = f[6].toInt().coerceIn(0, 180),
                    timezoneId = f[7].ifBlank { java.time.ZoneId.systemDefault().id },
                    enabled = f[8].toBooleanStrict(),
                    revision = f[9].toInt()
                )
            }.getOrNull()
        }
    }
}
