package ai.drivemuse.app

import ai.drivemuse.app.playback.NextBatchScheduler
import ai.drivemuse.app.playback.PrepareOutcome
import ai.drivemuse.domain.StaleReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The retry existed only as a cleared marker: the observer announces one start per attempt, so
 * while the last track of a batch played on there was no second notification to retry on. These
 * tests are about the scheduler actually trying again, and about stopping when it should.
 */
class NextBatchSchedulerTest {

    private fun scope() = CoroutineScope(Dispatchers.Default + Job())

    private suspend fun settle(attempts: AtomicInteger, expected: Int) {
        withTimeoutOrNull(5_000) {
            while (attempts.get() < expected) delay(10)
        }
    }

    @Test fun retriesARetryableFailureUpToTheBound() = runBlocking {
        val attempts = AtomicInteger()
        val scope = scope()
        val scheduler = NextBatchScheduler(
            prepare = { attempts.incrementAndGet(); PrepareOutcome.RetryableFailure("network") },
            scope = scope, maxAttempts = 3, backoffMs = { 0 }
        )
        scheduler.onStarted("t8", ordinal = 7, plannedSize = 8)
        settle(attempts, 3)
        delay(150)
        assertEquals(3, attempts.get(), "the scheduler stopped short of the bound or ran past it")
        scope.cancel()
    }

    @Test fun successStopsAfterOneAttempt() = runBlocking {
        val attempts = AtomicInteger()
        val scope = scope()
        val scheduler = NextBatchScheduler(
            prepare = { attempts.incrementAndGet(); PrepareOutcome.Prepared },
            scope = scope, maxAttempts = 3, backoffMs = { 0 }
        )
        scheduler.onStarted("t8", 7, 8)
        settle(attempts, 1)
        delay(150)
        assertEquals(1, attempts.get())
        scope.cancel()
    }

    @Test fun anExhaustedPoolIsNotRetried() = runBlocking {
        val attempts = AtomicInteger()
        val scope = scope()
        val scheduler = NextBatchScheduler(
            prepare = { attempts.incrementAndGet(); PrepareOutcome.Exhausted("후보 없음") },
            scope = scope, maxAttempts = 3, backoffMs = { 0 }
        )
        scheduler.onStarted("t8", 7, 8)
        settle(attempts, 1)
        delay(150)
        assertEquals(1, attempts.get(), "retrying cannot invent candidates")
        scope.cancel()
    }

    @Test fun resetStopsRetryingAndLeavesTheSchedulerUsable() = runBlocking {
        val attempts = AtomicInteger()
        val scope = scope()
        val scheduler = NextBatchScheduler(
            prepare = { attempts.incrementAndGet(); delay(200); PrepareOutcome.RetryableFailure("network") },
            scope = scope, maxAttempts = 10, backoffMs = { 20 }
        )
        scheduler.onStarted("t8", 7, 8)
        settle(attempts, 1)
        scheduler.reset()
        val afterReset = attempts.get()
        delay(300)
        assertTrue(attempts.get() <= afterReset + 1, "retries continued past reset")

        // The cancelled job must not have left `running` set: a new start has to be accepted.
        val before = attempts.get()
        scheduler.onStarted("t9", 7, 8)
        settle(attempts, before + 1)
        assertTrue(attempts.get() > before, "the scheduler was left stuck after reset")
        scope.cancel()
    }

    @Test fun proposalsForAMovedQueueAreRejected() = runBlocking {
        val scope = scope()
        val scheduler = NextBatchScheduler({ PrepareOutcome.Prepared }, scope, backoffMs = { 0 })
        val base = 0L
        assertTrue(scheduler.accepts(base))
        scheduler.reset()
        assertFalse(scheduler.accepts(base), "a base from before the reset was still accepted")
        scope.cancel()
    }

    /**
     * The regression the review caught: DELIVERY_UNCERTAIN was raised and then cleared in the same
     * breath, because confirming the chosen track counted as confirming the queue behind it.
     */
    @Test fun deliveryUncertaintySurvivesConfirmedPlayback() {
        assertFalse(StaleReason.DELIVERY_UNCERTAIN.clearedByConfirmedPlayback)
        assertFalse(StaleReason.DELIVERY_UNCERTAIN.fixedByReselection, "choosing again cannot tell you what the queue did")
        assertTrue(StaleReason.CONTROL_LOST.clearedByConfirmedPlayback)
        assertTrue(StaleReason.RESTORE_UNVERIFIED.clearedByConfirmedPlayback)
        assertTrue(StaleReason.PROFILE_CHANGED.fixedByReselection)
        assertTrue(StaleReason.RULE_CHANGED.fixedByReselection)
        assertFalse(StaleReason.PROFILE_CHANGED.clearedByConfirmedPlayback)
    }
}
