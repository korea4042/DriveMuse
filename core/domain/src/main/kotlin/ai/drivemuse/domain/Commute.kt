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
    /** True when the stored value is still in the 0.14.0 JSON shape and needs converting. */
    fun isLegacy(raw: String) = raw.trimStart().startsWith("[")

    /**
     * What a conversion of the 0.14.0 value would produce, and whether it understood all of it.
     *
     * [complete] separates an array that was genuinely empty from one whose records could not be
     * read: both decode to an empty list, and only the first is safe to write back. A conversion
     * that is not complete must leave the original bytes alone, because overwriting drops the
     * records it failed to understand permanently.
     */
    data class Migration(val schedules: List<CommuteSchedule>, val recordsSeen: Int) {
        val complete get() = schedules.size == recordsSeen
        /** The new-format text, verified by decoding it again. Null when it would not round-trip. */
        val verified: String? get() = encode(schedules).takeIf { decode(it) == schedules }
    }

    fun migrate(raw: String): Migration {
        val schedules = decode(raw)
        // Only the legacy shape can lose records; anything already in the new format is by
        // definition fully understood, so it reports complete rather than a mismatch of zero.
        val seen = if (isLegacy(raw)) splitTopLevel(raw.trim().removePrefix("[").removeSuffix("]"), ',').size
                   else schedules.size
        return Migration(schedules, seen)
    }

    fun decode(raw: String): List<CommuteSchedule> {
        if (raw.isBlank()) return emptyList()
        // 0.14.0 wrote JSON under this same key. Replacing the codec without reading the old shape
        // would have silently emptied every existing user's commute setup on upgrade.
        if (isLegacy(raw)) return decodeLegacyJson(raw)
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

/**
 * Reader for the 0.14.0 JSON encoding, kept only so upgrades do not lose the user's schedules.
 *
 * Hand-written rather than `org.json`, which is a stub on the unit-test classpath: a migration
 * that cannot be tested is a migration nobody finds out about until the upgrade ships. It reads
 * only the shape this app emitted and gives up on anything else, one record at a time.
 */
private fun decodeLegacyJson(raw: String): List<CommuteSchedule> =
    splitTopLevel(raw.trim().removePrefix("[").removeSuffix("]"), ',').mapNotNull { record ->
        runCatching {
            val fields = readObject(record)
            CommuteSchedule(
                id = fields.getValue("id").also { require(it.isNotBlank()) },
                direction = CommuteDirection.valueOf(fields.getValue("direction")),
                weekdays = (fields["weekdays"] ?: "").removePrefix("[").removeSuffix("]")
                    .split(',').map { it.trim().trim('"') }.filter { it.isNotBlank() }
                    .mapNotNull { d -> runCatching { java.time.DayOfWeek.valueOf(d) }.getOrNull() }.toSet(),
                departureLocalTime = java.time.LocalTime.parse(fields.getValue("departure")),
                beforeMinutes = (fields["before"]?.toIntOrNull() ?: 30).coerceIn(0, 180),
                afterMinutes = (fields["after"]?.toIntOrNull() ?: 30).coerceIn(0, 180),
                timezoneId = fields["zone"]?.ifBlank { null } ?: java.time.ZoneId.systemDefault().id,
                enabled = fields["enabled"] != "false",
                revision = fields["revision"]?.toIntOrNull() ?: 0
            )
        }.getOrNull()
    }

/** Splits on [separator] only where brace, bracket and quote nesting is back at the top level. */
private fun splitTopLevel(text: String, separator: Char): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0; var inString = false; var escaped = false
    for (c in text) {
        when {
            escaped -> escaped = false
            c == '\\' && inString -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '{' || c == '[' -> depth++
            c == '}' || c == ']' -> depth--
            c == separator && depth == 0 -> { parts += current.toString(); current.clear(); continue }
        }
        current.append(c)
    }
    if (current.isNotBlank()) parts += current.toString()
    return parts.filter { it.isNotBlank() }
}

/** `{"a":1,"b":["x"]}` to `{a=1, b=["x"]}`. Strings lose their quotes; everything else is verbatim. */
private fun readObject(record: String): Map<String, String> =
    splitTopLevel(record.trim().removePrefix("{").removeSuffix("}"), ',').mapNotNull { pair ->
        val parts = splitTopLevel(pair, ':')
        if (parts.size < 2) null
        else parts[0].trim().trim('"') to parts.drop(1).joinToString(":").trim().let {
            if (it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1) else it
        }
    }.toMap()

/**
 * §5: a location answer may only be written back by the connection episode that asked for it.
 *
 * captureDeparture waits up to fifteen seconds for a fix. If the car disconnects in that window
 * the disconnect handler records the end — and the callback then arriving would write
 * `endedAt = 0` and resurrect a finished episode, so the next connection would resume a drive that
 * had already ended from a departure point that was never adopted.
 */
enum class DepartureRejection(val detail: String) {
    LATE_FIX("출발 영역 미확인 · 위치가 출발 시점보다 늦게 확인돼 사용하지 않았어요"),
    NO_FIX("출발 영역 미확인")
}

sealed interface DepartureDecision {
    data class Adopt(val zone: Zone) : DepartureDecision
    /** The episode ended or was replaced while the fix was in flight. Write nothing. */
    data object Superseded : DepartureDecision
    data class Rejected(val reason: DepartureRejection) : DepartureDecision
}

object DepartureGate {
    fun decide(
        startedAt: Long,
        now: Long,
        connected: Boolean,
        storedDepartureAt: Long,
        storedEndedAt: Long,
        offered: Zone?
    ): DepartureDecision {
        // Checked before the fix is even looked at: a perfectly good fix still must not be written
        // to an episode that is over.
        if (!connected || storedDepartureAt != startedAt || storedEndedAt != 0L) return DepartureDecision.Superseded
        if (offered == null || offered == Zone.UNKNOWN) return DepartureDecision.Rejected(DepartureRejection.NO_FIX)
        if (now - startedAt > ContextFreshness.FIX_FOR_ZONE_MS) return DepartureDecision.Rejected(DepartureRejection.LATE_FIX)
        return DepartureDecision.Adopt(offered)
    }
}
