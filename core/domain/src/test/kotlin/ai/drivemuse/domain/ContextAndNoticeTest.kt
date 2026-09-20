package ai.drivemuse.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class ContextAndNoticeTest {
    private val morning = LocalDateTime.of(2026, 9, 21, 8, 10) // Monday
    private val evening = LocalDateTime.of(2026, 9, 21, 18, 20)

    // --- §4: the two clocks are separate ---

    @Test fun fixAgesOutForZoneLongBeforeItDoesForWeather() {
        val measured = 1_000_000L
        val eightMinutesLater = measured + 8 * 60_000
        assertFalse(ContextFreshness.zoneUsable(measured, eightMinutesLater))
        assertTrue(ContextFreshness.regionUsableForWeather(measured, eightMinutesLater))
    }

    @Test fun neitherClockAcceptsAFixFromTheFuture() {
        assertFalse(ContextFreshness.zoneUsable(2_000, 1_000))
        assertFalse(ContextFreshness.regionUsableForWeather(2_000, 1_000))
    }

    @Test fun weatherRegionStillExpires() {
        val measured = 0L
        assertFalse(ContextFreshness.regionUsableForWeather(measured, ContextFreshness.FIX_FOR_REGION_MS + 1))
    }

    // --- CTX04: a registered origin is evidence; the destination is not invented from it ---

    @Test fun originAloneRaisesTheHypothesisWithoutConfirmingIt() {
        val r = ContextEngine.classify(Signals(true, morning, origin = Zone.HOME))
        assertEquals(DriveContext.COMMUTE_TO_WORK, r.context)
        // Below the bar automation may act on: we know where they left, not where they are going.
        assertTrue(r.confidence < .8)
        assertFalse(Policy.canAutoSelect(true, true, r.confidence, 0, 1))
        assertTrue(r.reasons.any { "도착지" in it })
    }

    @Test fun bothEndsKnownIsStillTheOnlyWayToReachCertainty() {
        val r = ContextEngine.classify(Signals(true, evening, Zone.WORK, Zone.HOME))
        assertEquals(DriveContext.COMMUTE_HOME, r.context)
        assertEquals(1.0, r.confidence, 1e-9)
        assertTrue(Policy.canAutoSelect(true, true, r.confidence, 0, 1))
    }

    @Test fun leavingWorkInTheMorningIsNotACommuteHome() {
        assertEquals(DriveContext.GENERAL_DRIVE, ContextEngine.classify(Signals(true, morning, origin = Zone.WORK)).context)
    }

    @Test fun anUnregisteredOriginChangesNothing() {
        assertEquals(DriveContext.GENERAL_DRIVE, ContextEngine.classify(Signals(true, morning)).context)
    }

    // --- §30/UX04: relaxing the artist cap is reported, not silent ---

    private fun track(id: String, artist: String) = Track(id = id, title = id, artist = artist)

    @Test fun capRelaxationIsReportedWhenThePoolIsTooNarrow() {
        val pool = (1..8).map { track("track00000000000000%02d".format(it), "One Artist") }
        val out = DirectInputSelector.select(pool, Constraints(), "session", count = 8, maxPerArtist = 2)
        assertEquals(8, out.tracks.size)
        assertTrue(out.capRelaxed)
    }

    @Test fun aSpreadPoolDoesNotReportRelaxation() {
        val pool = (1..8).map { track("track00000000000000%02d".format(it), "Artist $it") }
        val out = DirectInputSelector.select(pool, Constraints(), "session", count = 8, maxPerArtist = 2)
        assertEquals(8, out.tracks.size)
        assertFalse(out.capRelaxed)
    }

    @Test fun aShortPoolIsShortNotRelaxed() {
        val pool = listOf(track("track0000000000000001", "A"), track("track0000000000000002", "B"))
        val out = DirectInputSelector.select(pool, Constraints(), "session", count = 8, maxPerArtist = 2)
        assertTrue(out.short)
        assertFalse(out.capRelaxed)
    }

    // --- §7: a failure says which failure ---

    @Test fun everyFailureCarriesSomethingToDo() {
        LocationStatus.entries.filter { it != LocationStatus.AVAILABLE }.forEach {
            assertTrue(it.name, it.advice.isNotBlank())
        }
        assertTrue(LocationStatus.AVAILABLE.advice.isBlank())
        assertFalse(LocationStatus.PERMISSION_DENIED.retryable)
        assertFalse(LocationStatus.DISABLED.retryable)
        assertTrue(LocationStatus.TIMEOUT.retryable)
    }
}
