package ai.drivemuse.domain

import org.junit.Assert.*
import org.junit.Test

class OperationTest {
    private val start = 1_000_000L
    private fun running(kind: OperationKind = OperationKind.SELECTION) =
        Operation.running("op1", kind, "target", start)

    @Test fun theVerbAloneForTheFirstSecond() {
        val op = running().copy(stage = OperationStage.PREPARING)
        assertEquals(OperationKind.SELECTION.verb, op.label(start + 500))
        assertFalse(op.showStage(start + 500))
        assertFalse(op.cancellable(start + 500))
    }

    @Test fun theStageAppearsAtOneSecond() {
        val op = running().copy(stage = OperationStage.PREPARING)
        assertTrue(op.showStage(start + Operation.STAGE_AFTER_MS))
        assertTrue("곡 준비" in op.label(start + 1_500))
    }

    @Test fun aStageThatWasNeverSetIsNotAnnounced() {
        assertFalse(running().showStage(start + 5_000))
    }

    @Test fun eightSecondsSaysSoAndOffersCancel() {
        val op = running().copy(stage = OperationStage.PREPARING)
        assertFalse(op.slow(start + Operation.SLOW_AFTER_MS - 1))
        assertTrue(op.slow(start + Operation.SLOW_AFTER_MS))
        assertTrue(op.cancellable(start + 8_000))
        assertEquals("평소보다 오래 걸리고 있어요", op.label(start + 9_000))
    }

    @Test fun aResetIsNotCancellableHalfway() {
        assertFalse(running(OperationKind.RESET).cancellable(start + 20_000))
    }

    @Test fun cancellingAnExternalCommandDoesNotClaimItWasUndone() {
        val cancelled = running(OperationKind.PLAYBACK).copy(phase = OperationPhase.CANCELLED)
        assertTrue("되돌리지" in cancelled.label(start))
        assertEquals("취소됨", running(OperationKind.SELECTION).copy(phase = OperationPhase.CANCELLED).label(start))
    }

    @Test fun anExternalCommandWhoseFateIsOpenIsNeverResent() {
        // Unknown and cancelled are the same problem: the command may already be with Spotify.
        // Cancelling stops the app waiting; it does not reach out and unsend anything.
        listOf(OperationPhase.UNKNOWN, OperationPhase.CANCELLED).forEach { phase ->
            assertFalse(phase.name, running(OperationKind.PLAYBACK).copy(phase = phase, retryable = true).safeToRetry)
        }
        // Local work in either state is safe to run again: nothing left the device.
        listOf(OperationPhase.UNKNOWN, OperationPhase.CANCELLED).forEach { phase ->
            assertTrue(phase.name, running(OperationKind.WEATHER).copy(phase = phase, retryable = true).safeToRetry)
        }
        // A failure is still retryable even for an external command: it did not land.
        assertTrue(running(OperationKind.PLAYBACK).copy(phase = OperationPhase.FAILED, retryable = true).safeToRetry)
    }

    @Test fun everythingThatCanEndWithoutProducingAnythingDemandsConfirmation() {
        // Selection can end on a stale generation, a pool refresh can fail with the old pool
        // intact, a context refresh can reuse what it already had. None of those are success.
        listOf(OperationKind.PLAYBACK, OperationKind.SELECTION, OperationKind.POOL,
               OperationKind.CONTEXT, OperationKind.LOCATION, OperationKind.WEATHER).forEach {
            assertTrue(it.name, it.needsConfirmation)
        }
        assertFalse(OperationKind.WEATHER.external)
    }

    @Test fun aDeclaredDetailOutranksTheCancelBoilerplate() {
        val discarded = running().copy(phase = OperationPhase.CANCELLED, detail = "조건이 바뀌었어요")
        assertEquals("조건이 바뀌었어요", discarded.label(start))
    }

    @Test fun everyBudgetIsLongerThanTheSlowThreshold() {
        OperationKind.entries.forEach {
            assertTrue(it.name, it.timeoutMs > Operation.SLOW_AFTER_MS)
        }
    }

    @Test fun settledIsSettledAndRunningIsNot() {
        assertFalse(running().settled)
        assertTrue(running().copy(phase = OperationPhase.FAILED).settled)
        assertTrue(running().copy(phase = OperationPhase.UNKNOWN).settled)
        assertFalse(Operation("x", OperationKind.SAVE, "t", OperationPhase.IDLE).settled)
    }

    @Test fun elapsedNeverGoesNegative() {
        assertEquals(0L, running().elapsed(start - 5_000))
    }
}
