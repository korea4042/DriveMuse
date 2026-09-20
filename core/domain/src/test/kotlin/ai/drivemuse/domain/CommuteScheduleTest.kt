package ai.drivemuse.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CommuteScheduleTest {
    private val seoul = "Asia/Seoul"
    private fun at(date: String, time: String, zone: String = seoul): ZonedDateTime =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZoneId.of(zone))

    private fun schedule(
        id: String = "s1",
        direction: CommuteDirection = CommuteDirection.TO_WORK,
        days: Set<DayOfWeek> = CommuteSchedules.WEEKDAYS,
        departure: String = "07:30",
        before: Int = 30,
        after: Int = 30,
        zone: String = seoul
    ) = CommuteSchedule(id, direction, days, LocalTime.parse(departure), before, after, zone)

    // --- SCH02: windows, midnight and time zones ---

    @Test fun theWindowIsTheRangeTheUserIsShown() {
        val s = schedule(departure = "07:30")
        assertEquals("07:00~08:00", s.windowLabel)
        assertFalse(s.crossesMidnight)
        // 2026-09-21 is a Monday.
        assertTrue(s.matches(at("2026-09-21", "07:00")))
        assertTrue(s.matches(at("2026-09-21", "08:00")))
        assertFalse(s.matches(at("2026-09-21", "06:59")))
        assertFalse(s.matches(at("2026-09-21", "08:01")))
    }

    @Test fun aMondayDepartureJustAfterMidnightReachesBackIntoSunday() {
        // §3: the weekday is the weekday of the scheduled departure, not of the moment judged.
        val s = schedule(days = setOf(DayOfWeek.MONDAY), departure = "00:15")
        assertTrue(s.crossesMidnight)
        assertTrue("Sunday 23:45", s.matches(at("2026-09-20", "23:45")))
        assertTrue(s.matches(at("2026-09-21", "00:30")))
        // The same clock time a day earlier belongs to no Monday departure.
        assertFalse(s.matches(at("2026-09-19", "23:45")))
    }

    @Test fun aWindowRunningPastMidnightIsStillOneSchedule() {
        val s = schedule(direction = CommuteDirection.TO_HOME, days = setOf(DayOfWeek.FRIDAY), departure = "23:50")
        assertTrue(s.matches(at("2026-09-25", "23:50")))
        assertTrue("Saturday 00:20 belongs to Friday's departure", s.matches(at("2026-09-26", "00:20")))
        assertFalse(s.matches(at("2026-09-26", "00:21")))
    }

    @Test fun theScheduleKeepsItsOwnZoneWhenTheDeviceMoves() {
        val s = schedule(departure = "07:30", zone = seoul)
        // 07:30 in Seoul is 22:30 the previous day in London; the schedule must not drift.
        val sameInstantInLondon = at("2026-09-21", "07:30").withZoneSameInstant(ZoneId.of("Europe/London"))
        assertTrue(s.matches(sameInstantInLondon))
        // 07:30 London local time is not the Seoul departure.
        assertFalse(s.matches(at("2026-09-21", "07:30", "Europe/London")))
    }

    @Test fun aWeekendMorningIsNotAWeekdaySchedule() {
        assertFalse(schedule().matches(at("2026-09-26", "07:30")))
        assertTrue(schedule(days = CommuteSchedules.EVERY_DAY).matches(at("2026-09-26", "07:30")))
    }

    @Test fun aDisabledScheduleMatchesNothing() {
        assertFalse(schedule().copy(enabled = false).matches(at("2026-09-21", "07:30")))
    }

    // --- SCH01: copying and overlap ---

    @Test fun copyingWeekdaysDoesNotLinkTheTwoSchedules() {
        val morning = schedule(id = "m", days = CommuteSchedules.WEEKDAYS)
        val evening = schedule(id = "e", direction = CommuteDirection.TO_HOME, days = emptySet(), departure = "18:30")
        val copied = CommuteSchedules.copyWeekdays(morning, evening)
        assertEquals(CommuteSchedules.WEEKDAYS, copied.weekdays)
        // Editing the copy afterwards leaves the source alone.
        val edited = copied.copy(weekdays = copied.weekdays - DayOfWeek.FRIDAY)
        assertEquals(CommuteSchedules.WEEKDAYS, morning.weekdays)
        assertEquals(4, edited.weekdays.size)
        assertEquals(CommuteDirection.TO_HOME, copied.direction)
        assertEquals(LocalTime.parse("18:30"), copied.departureLocalTime)
    }

    @Test fun overlappingSchedulesResolveToTheNearestDeparture() {
        val early = schedule(id = "a", departure = "07:00", before = 60, after = 60)
        val late = schedule(id = "b", departure = "08:30", before = 60, after = 60)
        assertEquals("a", CommuteSchedules.match(listOf(early, late), at("2026-09-21", "07:20"))?.id)
        assertEquals("b", CommuteSchedules.match(listOf(late, early), at("2026-09-21", "08:20"))?.id)
    }

    @Test fun anExactTieResolvesTheSameWayEveryTime() {
        val a = schedule(id = "b-second", departure = "07:00", before = 60, after = 60)
        val b = schedule(id = "a-first", departure = "07:00", before = 60, after = 60)
        val one = CommuteSchedules.match(listOf(a, b), at("2026-09-21", "07:30"))
        val other = CommuteSchedules.match(listOf(b, a), at("2026-09-21", "07:30"))
        assertEquals(one?.id, other?.id)
        assertEquals("a-first", one?.id)
    }

    @Test fun outsideEveryWindowThereIsNoMatch() {
        assertNull(CommuteSchedules.match(listOf(schedule()), at("2026-09-21", "13:00")))
        assertNull(CommuteSchedules.match(emptyList(), at("2026-09-21", "07:30")))
    }

    // --- CTX10 / CTX11 ---

    private fun input(
        connected: Boolean = true,
        time: String = "07:30",
        date: String = "2026-09-21",
        origin: Zone = Zone.HOME,
        schedules: List<CommuteSchedule> = listOf(schedule()),
        manual: ContextPurpose? = null
    ) = ContextInput(connected, at(date, time), manual, origin, schedules)

    @Test fun homePlusAMorningScheduleEstimatesACommuteAndKeepsTheArrivalUnknown() {
        val a = ContextEstimator.assess(input())
        assertEquals(ContextPurpose.COMMUTE_TO_WORK, a.purpose)
        assertEquals(ContextBasis.ESTIMATED, a.basis)
        assertTrue(ContextEvidence.ORIGIN_HOME in a.evidence)
        assertTrue(ContextEvidence.SCHEDULE_MATCH in a.evidence)
        assertTrue(MissingSignal.DESTINATION in a.missing)
        assertEquals(DriveContext.COMMUTE_TO_WORK, a.driveContext)
        // §4: reasons, never an invented percentage.
        assertFalse("%" in a.describe())
        assertTrue("도착지" in a.describe())
    }

    @Test fun theScheduleAloneIsNotACommuteWithoutTheDepartureZone() {
        val a = ContextEstimator.assess(input(origin = Zone.UNKNOWN))
        assertEquals(ContextPurpose.GENERAL, a.purpose)
        assertTrue(ContextEvidence.SCHEDULE_MATCH in a.evidence)
        assertTrue(MissingSignal.LOCATION in a.missing)
    }

    @Test fun theDepartureZoneAloneIsNotACommuteWithoutASchedule() {
        val a = ContextEstimator.assess(input(schedules = emptyList()))
        assertEquals(ContextPurpose.GENERAL, a.purpose)
        assertTrue(MissingSignal.SCHEDULE in a.missing)
    }

    @Test fun leavingWorkInsideTheMorningWindowIsNotACommuteToWork() {
        assertEquals(ContextPurpose.GENERAL, ContextEstimator.assess(input(origin = Zone.WORK)).purpose)
    }

    @Test fun theEveningScheduleNeedsWorkAsTheOrigin() {
        val evening = schedule(id = "e", direction = CommuteDirection.TO_HOME, departure = "18:30")
        assertEquals(ContextPurpose.COMMUTE_HOME,
            ContextEstimator.assess(input(time = "18:30", origin = Zone.WORK, schedules = listOf(evening))).purpose)
        assertEquals(ContextPurpose.GENERAL,
            ContextEstimator.assess(input(time = "18:30", origin = Zone.HOME, schedules = listOf(evening))).purpose)
    }

    @Test fun aManualChoiceOutranksEverything() {
        val a = ContextEstimator.assess(input(origin = Zone.WORK, manual = ContextPurpose.TRAVEL))
        assertEquals(ContextPurpose.TRAVEL, a.purpose)
        assertEquals(ContextBasis.MANUAL, a.basis)
        assertTrue(ContextEvidence.MANUAL_CHOICE in a.evidence)
    }

    @Test fun aManualChoiceSurvivesTheCarBeingDisconnected() {
        // CTX11: the estimate is not a playback permission, so disconnection does not erase it.
        val a = ContextEstimator.assess(input(connected = false, manual = ContextPurpose.COMMUTE_HOME))
        assertEquals(ContextPurpose.COMMUTE_HOME, a.purpose)
        assertEquals(ContextBasis.MANUAL, a.basis)
    }

    @Test fun noVehicleMeansUnknownAndSaysWhy() {
        val a = ContextEstimator.assess(input(connected = false))
        assertEquals(ContextPurpose.UNKNOWN, a.purpose)
        assertEquals(MissingSignal.VEHICLE, a.missing.first())
        assertEquals(DriveContext.UNKNOWN, a.driveContext)
    }

    // --- §4: the axes are independent ---

    @Test fun anEveningCommuteCanAlsoBeNightAndLongDistance() {
        val evening = schedule(id = "e", direction = CommuteDirection.TO_HOME, departure = "22:30")
        val a = ContextEstimator.assess(ContextInput(
            connected = true, at = at("2026-09-21", "22:30"), originZone = Zone.WORK,
            schedules = listOf(evening), distanceMode = DistanceMode.LONG_DISTANCE,
            routeStatus = RouteStatus.MATCHING))
        assertEquals(ContextPurpose.COMMUTE_HOME, a.purpose)
        assertEquals(TimeBand.NIGHT, a.timeBand)
        assertEquals(DistanceMode.LONG_DISTANCE, a.distanceMode)
        assertEquals(RouteStatus.MATCHING, a.routeStatus)
        // The commute keeps its own rules; night does not overwrite the purpose.
        assertEquals(DriveContext.COMMUTE_HOME, a.driveContext)
    }

    @Test fun nightIsAWindowThatWrapsMidnight() {
        val night = { t: String -> ContextEstimator.timeBand(at("2026-09-21", t), LocalTime.of(22, 0), LocalTime.of(6, 0)) }
        assertEquals(TimeBand.NIGHT, night("23:30"))
        assertEquals(TimeBand.NIGHT, night("02:00"))
        assertEquals(TimeBand.NIGHT, night("22:00"))
        assertEquals(TimeBand.DAY, night("06:00"))
        assertEquals(TimeBand.DAY, night("21:59"))
    }

    @Test fun aPlainNightDriveStillReadsAsNightForRules() {
        val a = ContextEstimator.assess(input(time = "23:00", origin = Zone.OUTSIDE, schedules = emptyList()))
        assertEquals(ContextPurpose.GENERAL, a.purpose)
        assertEquals(DriveContext.NIGHT_DRIVE, a.driveContext)
        assertTrue(MissingSignal.ORIGIN in a.missing)
    }

    @Test fun theRouteLayerIsHonestAboutNotBeingThereYet() {
        assertTrue(MissingSignal.ROUTE in ContextEstimator.assess(input()).missing)
    }

    // --- the schedule is judged at the departure, not at every reassessment ---

    @Test fun aCommuteSurvivesBeingReassessedAfterTheWindowCloses() {
        // Left home at 07:50, inside 07:00~08:00. Reassessed at 08:31, well outside it.
        val a = ContextEstimator.assess(ContextInput(
            connected = true,
            at = at("2026-09-21", "08:31"),
            originZone = Zone.HOME,
            departedAt = at("2026-09-21", "07:50"),
            schedules = listOf(schedule())))
        assertEquals(ContextPurpose.COMMUTE_TO_WORK, a.purpose)
    }

    @Test fun theTimeBandStillFollowsTheClockNotTheDeparture() {
        // Set off at 21:00, still driving at 23:30: the commute holds and the band turns to night.
        val evening = schedule(id = "e", direction = CommuteDirection.TO_HOME, departure = "21:00")
        val a = ContextEstimator.assess(ContextInput(
            connected = true,
            at = at("2026-09-21", "23:30"),
            originZone = Zone.WORK,
            departedAt = at("2026-09-21", "21:00"),
            schedules = listOf(evening)))
        assertEquals(ContextPurpose.COMMUTE_HOME, a.purpose)
        assertEquals(TimeBand.NIGHT, a.timeBand)
    }

    @Test fun withoutADepartureTheClockStandsIn() {
        val a = ContextEstimator.assess(ContextInput(
            connected = true, at = at("2026-09-21", "07:30"), originZone = Zone.HOME, schedules = listOf(schedule())))
        assertEquals(ContextPurpose.COMMUTE_TO_WORK, a.purpose)
    }

    // --- the direction that agrees with the departure zone is chosen first ---

    @Test fun aNearerScheduleInTheWrongDirectionDoesNotHideTheRightOne() {
        // Both windows are open at 16:50. 퇴근 is nearer, but the car left home, so it is 출근.
        val morning = schedule(id = "m", departure = "08:00", before = 0, after = 9 * 60)
        val evening = schedule(id = "e", direction = CommuteDirection.TO_HOME, departure = "17:00", before = 60, after = 60)
        val a = ContextEstimator.assess(ContextInput(
            connected = true, at = at("2026-09-21", "16:50"), originZone = Zone.HOME,
            departedAt = at("2026-09-21", "16:50"), schedules = listOf(morning, evening)))
        assertEquals(ContextPurpose.COMMUTE_TO_WORK, a.purpose)
        assertTrue(ContextEvidence.ORIGIN_HOME in a.evidence)
    }

    @Test fun matchFromKeepsTheNearestAmongTheCompatibleOnes() {
        val early = schedule(id = "a", departure = "07:00", before = 60, after = 120)
        val late = schedule(id = "b", departure = "09:00", before = 120, after = 60)
        val wrongWay = schedule(id = "z", direction = CommuteDirection.TO_HOME, departure = "08:10", before = 60, after = 60)
        val all = listOf(early, late, wrongWay)
        assertEquals("z", CommuteSchedules.match(all, at("2026-09-21", "08:10"))?.id)
        assertEquals("a", CommuteSchedules.matchFrom(all, at("2026-09-21", "07:40"), Zone.HOME)?.id)
        assertEquals("b", CommuteSchedules.matchFrom(all, at("2026-09-21", "08:40"), Zone.HOME)?.id)
        assertEquals("z", CommuteSchedules.matchFrom(all, at("2026-09-21", "08:10"), Zone.WORK)?.id)
        assertNull(CommuteSchedules.matchFrom(all, at("2026-09-21", "08:10"), Zone.UNKNOWN))
    }

    @Test fun theScheduleEvidenceStillShowsWhenOnlyTheWrongDirectionMatches() {
        val evening = schedule(id = "e", direction = CommuteDirection.TO_HOME, departure = "18:30")
        val a = ContextEstimator.assess(ContextInput(
            connected = true, at = at("2026-09-21", "18:30"), originZone = Zone.HOME, schedules = listOf(evening)))
        assertEquals(ContextPurpose.GENERAL, a.purpose)
        assertTrue(ContextEvidence.SCHEDULE_MATCH in a.evidence)
    }

    // --- the codec: saving verifies by comparing the record that comes back ---

    @Test fun everyFieldSurvivesTheRoundTrip() {
        val all = listOf(
            schedule(id = "commute.TO_WORK", days = CommuteSchedules.WEEKDAYS, departure = "07:05", before = 45, after = 15).copy(revision = 7),
            schedule(id = "commute.TO_HOME", direction = CommuteDirection.TO_HOME, days = setOf(DayOfWeek.SATURDAY),
                departure = "23:50", before = 0, after = 120, zone = "Europe/London").copy(enabled = false, revision = 2))
        assertEquals(all, CommuteCodec.decode(CommuteCodec.encode(all)))
    }

    @Test fun anEmptyStoreIsAnEmptyList() {
        assertEquals(emptyList<CommuteSchedule>(), CommuteCodec.decode(""))
        assertEquals(emptyList<CommuteSchedule>(), CommuteCodec.decode(CommuteCodec.encode(emptyList())))
    }

    @Test fun oneUnreadableRecordDoesNotTakeTheOthersWithIt() {
        val good = schedule(id = "good")
        val raw = CommuteCodec.encode(listOf(good)) + "" + "v1brokenNOT_A_DIRECTION"
        assertEquals(listOf(good), CommuteCodec.decode(raw))
        assertEquals(emptyList<CommuteSchedule>(), CommuteCodec.decode("garbage"))
    }

    @Test fun aScheduleWithNoDaysRoundTripsAsItself() {
        val none = schedule(id = "none", days = emptySet())
        assertEquals(listOf(none), CommuteCodec.decode(CommuteCodec.encode(listOf(none))))
    }
}
