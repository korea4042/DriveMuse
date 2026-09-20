package ai.drivemuse.app

import ai.drivemuse.domain.Operation
import ai.drivemuse.domain.OperationKind
import ai.drivemuse.domain.OperationPhase
import ai.drivemuse.domain.OperationStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The state machine's own tests pass whatever the call sites do with it. These run the registry, so
 * a block that finishes without saying what happened is caught here rather than on a device.
 *
 * Every defect below was real: selection reported a stale generation as success, the pool reported
 * a failed refresh as success because candidates were still there, a reused forecast reported a
 * refresh, and a cancelled external command offered a retry.
 */
class OperationRegistryTest {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private fun registry() = OperationRegistry(scope, { e -> e.message ?: "실패" })

    @AfterTest fun tearDown() { scope.cancel() }

    private suspend fun settled(registry: OperationRegistry, target: String): Operation? =
        withTimeoutOrNull(5_000) {
            while (registry.of(target)?.settled != true) delay(5)
            registry.of(target)
        }

    // --- the selection defect: quiet completion is not success ---

    @Test fun aKindThatNeedsConfirmationDoesNotSucceedByFinishing() = runBlocking {
        val registry = registry()
        registry.run(OperationKind.SELECTION, "sel") { /* returns without confirming */ }
        assertEquals(OperationPhase.UNKNOWN, registry.of("sel")?.phase)
        assertTrue(registry.of("sel")!!.retryable)
    }

    @Test fun confirmingIsWhatMakesItSucceed() = runBlocking {
        val registry = registry()
        registry.run(OperationKind.SELECTION, "sel") { it.confirm("8곡을 준비했어요") }
        val op = registry.of("sel")!!
        assertEquals(OperationPhase.SUCCEEDED, op.phase)
        assertEquals("8곡을 준비했어요", op.label(0))
        assertFalse(op.retryable)
    }

    @Test fun aDiscardedResultIsNeitherSuccessNorUnknown() = runBlocking {
        val registry = registry()
        registry.run(OperationKind.SELECTION, "sel") { it.discard("조건이 바뀌었어요") }
        val op = registry.of("sel")!!
        assertEquals(OperationPhase.CANCELLED, op.phase)
        assertEquals("조건이 바뀌었어요", op.label(0))
        // Local work, so trying again is fine.
        assertTrue(op.safeToRetry)
    }

    // --- the pool defect: a failure must reach the registry as a failure ---

    @Test fun aThrowingBlockFails() = runBlocking {
        val registry = registry()
        registry.run(OperationKind.POOL, "pool") { error("후보를 갱신하지 못했어요 · 기존 12곡은 그대로예요") }
        val op = registry.of("pool")!!
        assertEquals(OperationPhase.FAILED, op.phase)
        assertTrue("기존 12곡" in op.label(0))
    }

    @Test fun confirmingBeforeThrowingStillFails() = runBlocking {
        // The order the call site happens to use must not decide the outcome.
        val registry = registry()
        registry.run(OperationKind.POOL, "pool") { it.confirm("후보 12곡"); error("조회 실패") }
        assertEquals(OperationPhase.FAILED, registry.of("pool")?.phase)
    }

    // --- timeouts and cancels ---

    @Test fun aTimeoutIsUnknownNotFailed() = runBlocking {
        val registry = registry()
        registry.run(OperationKind.SELECTION, "sel", timeoutMs = 50) { delay(5_000) }
        val op = registry.of("sel")!!
        assertEquals(OperationPhase.UNKNOWN, op.phase)
        assertTrue("확인하지 못했어요" in op.label(0))
    }

    @Test fun cancellingAnExternalCommandForbidsAQuietResend() = runBlocking {
        val registry = registry()
        val entered = CompletableDeferred<Unit>()
        registry.start(OperationKind.PLAYBACK, "play") { entered.complete(Unit); awaitCancellation() }
        entered.await()
        registry.cancel("play")
        val op = assertNotNull(settled(registry, "play"))
        assertEquals(OperationPhase.CANCELLED, op.phase)
        // The command may already be with Spotify. Read the state back before sending anything.
        assertFalse(op.safeToRetry)
    }

    @Test fun cancellingLocalWorkMayBeRetried() = runBlocking {
        val registry = registry()
        val entered = CompletableDeferred<Unit>()
        registry.start(OperationKind.POOL, "pool") { entered.complete(Unit); awaitCancellation() }
        entered.await()
        registry.cancel("pool")
        assertTrue(assertNotNull(settled(registry, "pool")).safeToRetry)
    }

    // --- one entry per control ---

    @Test fun aSecondPressOnABusyTargetIsRefused() = runBlocking {
        val registry = registry()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        registry.start(OperationKind.POOL, "pool") { entered.complete(Unit); release.await(); it.confirm("한 번") }
        entered.await()
        assertNull(registry.start(OperationKind.POOL, "pool") { it.confirm("두 번") })
        release.complete(Unit)
        assertEquals("한 번", assertNotNull(settled(registry, "pool")).label(0))
    }

    @Test fun twoTargetsDoNotOverwriteEachOther() = runBlocking {
        val registry = registry()
        val release = CompletableDeferred<Unit>()
        registry.start(OperationKind.POOL, "pool") { release.await(); it.confirm("후보") }
        registry.run(OperationKind.SAVE, "save") { it.confirm("저장") }
        assertEquals(OperationPhase.SUCCEEDED, registry.of("save")?.phase)
        assertTrue(registry.of("pool")!!.running)
        release.complete(Unit)
        assertEquals("후보", assertNotNull(settled(registry, "pool")).label(0))
    }

    @Test fun aSupersededHandleCannotWriteToItsOldTarget() = runBlocking {
        val registry = registry()
        var escaped: OperationHandle? = null
        registry.run(OperationKind.SAVE, "save") { escaped = it; it.confirm("첫 번째") }
        registry.run(OperationKind.SAVE, "save") { it.confirm("두 번째") }
        escaped!!.stage(OperationStage.CONFIRMING)
        val op = registry.of("save")!!
        assertEquals("두 번째", op.label(0))
        assertEquals(OperationStage.STARTING, op.stage)
    }

    @Test fun dismissClearsOnlySettledEntries() = runBlocking {
        val registry = registry()
        val release = CompletableDeferred<Unit>()
        registry.start(OperationKind.POOL, "pool") { release.await(); it.confirm("끝") }
        registry.dismiss("pool")
        assertNotNull(registry.of("pool"))
        release.complete(Unit)
        settled(registry, "pool")
        registry.dismiss("pool")
        assertNull(registry.of("pool"))
    }
}
