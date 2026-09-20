package ai.drivemuse.domain

import org.junit.Assert.*
import org.junit.Test

class ContextAndNoticeTest {

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

    // CTX04's origin handling moved to ContextEstimator; see CommuteScheduleTest.

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

    // --- §3/§7: reuse is not a refresh, and a surviving pool is not a successful one ---

    @Test fun reusingAHeldForecastIsNotARefresh() {
        assertEquals(ContextRefresh.REFRESHED, ContextRefresh.of(hasUsableFact = true, fetchedAtChanged = true))
        assertEquals(ContextRefresh.REUSED, ContextRefresh.of(hasUsableFact = true, fetchedAtChanged = false))
        assertEquals(ContextRefresh.NONE, ContextRefresh.of(hasUsableFact = false, fetchedAtChanged = false))
        assertFalse(ContextRefresh.REUSED.refreshed)
        // The cache is also served when the throttle holds, so reuse on its own names no cause.
        assertFalse("확인하지 못해" in ContextRefresh.REUSED.describe())
        assertEquals(ContextRefresh.REUSED.detail, ContextRefresh.REUSED.describe(null))
        assertTrue(LocationStatus.DISABLED.advice in ContextRefresh.REUSED.describe(LocationStatus.DISABLED.advice))
        // A cause is only ever attached to a reuse.
        assertEquals(ContextRefresh.REFRESHED.detail, ContextRefresh.REFRESHED.describe(LocationStatus.DISABLED.advice))
        assertTrue(ContextRefresh.values().distinctBy { it.detail }.size == 3)
    }

    @Test fun aFailedPoolRefreshIsAFailureHoweverManyCandidatesSurvived() {
        val failed = PoolRefresh.of(before = 120, after = 120, failure = "조회 실패")
        assertTrue(failed is PoolRefresh.Failed)
        assertTrue("기존 120곡" in (failed as PoolRefresh.Failed).detail)
    }

    @Test fun aRefreshThatAddedNothingStillSaysSo() {
        val none = PoolRefresh.of(before = 120, after = 120, failure = null) as PoolRefresh.Refreshed
        assertEquals(0, none.added)
        assertTrue("새로 추가된 곡 없음" in none.detail)
        val some = PoolRefresh.of(before = 120, after = 150, failure = null) as PoolRefresh.Refreshed
        assertEquals(30, some.added)
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
