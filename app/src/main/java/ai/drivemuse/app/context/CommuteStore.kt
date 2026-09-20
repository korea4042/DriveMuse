package ai.drivemuse.app.context

import ai.drivemuse.domain.CommuteDirection
import ai.drivemuse.domain.CommuteSchedule
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/**
 * Schedules live in the existing preference store as one JSON string. They are a handful of small
 * records read on every context assessment and written only from a settings screen, so a Room
 * table and a migration would buy nothing here.
 *
 * Reading is deliberately lenient about individual records and strict about the result: a row that
 * cannot be understood is dropped rather than throwing away every other schedule with it. Losing
 * the user's whole commute setup because one field went bad in an upgrade is the worse failure.
 */
object CommuteStore {
    fun encode(schedules: List<CommuteSchedule>): String = JSONArray().apply {
        schedules.forEach { s ->
            put(JSONObject().apply {
                put("id", s.id)
                put("direction", s.direction.name)
                put("weekdays", JSONArray(s.weekdays.sortedBy { it.value }.map { it.name }))
                put("departure", s.departureLocalTime.toString())
                put("before", s.beforeMinutes)
                put("after", s.afterMinutes)
                put("zone", s.timezoneId)
                put("enabled", s.enabled)
                put("revision", s.revision)
            })
        }
    }.toString()

    fun decode(raw: String): List<CommuteSchedule> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val days = o.optJSONArray("weekdays") ?: JSONArray()
                CommuteSchedule(
                    id = o.getString("id"),
                    direction = CommuteDirection.valueOf(o.getString("direction")),
                    weekdays = (0 until days.length()).mapNotNull { d ->
                        runCatching { DayOfWeek.valueOf(days.getString(d)) }.getOrNull()
                    }.toSet(),
                    departureLocalTime = LocalTime.parse(o.getString("departure")),
                    beforeMinutes = o.optInt("before", 30).coerceIn(0, 180),
                    afterMinutes = o.optInt("after", 30).coerceIn(0, 180),
                    timezoneId = o.optString("zone").ifBlank { ZoneId.systemDefault().id },
                    enabled = o.optBoolean("enabled", true),
                    revision = o.optInt("revision", 0)
                )
            }.getOrNull()
        }
    }
}
