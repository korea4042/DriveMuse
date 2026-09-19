package ai.drivemuse.domain
import kotlin.test.*

/**
 * Phase 1 §11, T14–T18: the whole path from App Remote callbacks to one score, with no Android and
 * no database. Every number here is the §8 table, so a change to the policy fails here first.
 */
class PlaybackObservationTest {
    private var seq = 0
    private fun tick(timeMs: Long, positionMs: Long, paused: Boolean = false) =
        Observation("e${seq++}", "attempt", timeMs, positionMs, if (paused) MediaState.PAUSED else MediaState.PLAYING)

    private fun score(events: List<Observation>, durationMs: Long?, trigger: EndTrigger, commandAt: Long? = null): Pair<EndJudgement, Double> {
        val totals = ListeningAggregator.aggregate(events, durationMs)
        val last = events.maxByOrNull { it.monotonicMs }
        val judgement = EndReasonResolver.resolve(AttemptClose(last?.monotonicMs ?: 0, last?.positionMs ?: 0, durationMs, trigger, commandAt))
        return judgement to PreferenceLearner.score(Outcome("attempt", "t", "s", 1, totals, judgement.reason, judgement.confidence, null, last?.monotonicMs ?: 0))
    }

    /** T14: position advancing in step with the clock is the only thing that counts as listening. */
    @Test fun matchingAdvanceCountsAsListening() {
        val totals = ListeningAggregator.aggregate(listOf(tick(0, 0), tick(3000, 3000), tick(6000, 6000)), 200_000)
        assertEquals(6000L, totals.activeMs)
        assertEquals(6000L, totals.coveredMs)
        assertFalse(totals.uncertain)
    }

    /** T15: a gap longer than five seconds is recorded as unobserved, never filled in. */
    @Test fun longGapIsNotListening() {
        val totals = ListeningAggregator.aggregate(listOf(tick(0, 0), tick(3000, 3000), tick(12000, 12000)), 200_000)
        assertEquals(3000L, totals.activeMs)
        assertTrue(totals.uncertain)
    }

    /** T16: carried past 97% and then changed — a completion, worth +0.25 once coverage is there. */
    @Test fun completionIsNaturalEnd() {
        val events = (0..19).map { tick(it * 3000L, it * 3000L) }
        val (judgement, value) = score(events, 58_000, EndTrigger.TRACK_CHANGED)
        assertEquals(EndReason.NATURAL_END, judgement.reason)
        assertEquals(1.0, judgement.confidence)
        assertEquals(.25, value)
    }

    /** T17: the app's own skip is the one skip whose cause is known, so it scores. */
    @Test fun appSkipIsNegative() {
        val events = listOf(tick(0, 0), tick(3000, 3000), tick(6000, 6000), tick(9000, 9000), tick(12000, 12000))
        val (judgement, value) = score(events, 200_000, EndTrigger.TRACK_CHANGED, commandAt = 11_500)
        assertEquals(EndReason.USER_NEXT, judgement.reason)
        assertEquals(1.0, judgement.confidence)
        assertEquals(-.4, value)
    }

    /** T18: the same skip without a command behind it is a guess, and a guess must not teach. */
    @Test fun unexplainedSkipScoresZero() {
        val events = listOf(tick(0, 0), tick(3000, 3000), tick(6000, 6000), tick(9000, 9000), tick(12000, 12000))
        val (judgement, value) = score(events, 200_000, EndTrigger.TRACK_CHANGED)
        assertEquals(EndReason.USER_NEXT, judgement.reason)
        assertEquals(.6, judgement.confidence)
        assertEquals(0.0, value)
    }

    /** A command that landed long before the change did not cause it. */
    @Test fun staleCommandIsNotAttributed() {
        val close = AttemptClose(60_000, 20_000, 200_000, EndTrigger.TRACK_CHANGED, appCommandAt = 1_000)
        assertEquals(.6, EndReasonResolver.resolve(close).confidence)
    }

    /** A dropped connection is an interruption, never a rejection of the track. */
    @Test fun disconnectIsInterrupted() {
        val (judgement, value) = score(listOf(tick(0, 0), tick(3000, 3000)), 200_000, EndTrigger.DISCONNECTED)
        assertEquals(EndReason.INTERRUPTED, judgement.reason)
        assertEquals(0.0, value)
    }

    /** No duration means no completion claim, whatever the last position was. */
    @Test fun missingDurationCannotComplete() {
        val close = AttemptClose(200_000, 199_000, null, EndTrigger.TRACK_CHANGED)
        assertEquals(EndReason.USER_NEXT, EndReasonResolver.resolve(close).reason)
        assertEquals(.6, EndReasonResolver.resolve(close).confidence)
    }
}
