package ai.drivemuse.app

import ai.drivemuse.domain.Operation
import ai.drivemuse.domain.OperationKind
import ai.drivemuse.domain.OperationPhase
import ai.drivemuse.domain.OperationStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets a running operation report where it has got to, and say when it has real evidence of
 * success. Handed to the block; never constructed by callers.
 */
class OperationHandle internal constructor(val id: String, private val onStage: (OperationStage) -> Unit) {
    @Volatile internal var confirmed = false
    @Volatile internal var confirmedDetail: String? = null

    fun stage(stage: OperationStage) = onStage(stage)

    /**
     * §3: "성공 표시는 실제 완료 근거가 있을 때만". For a kind that needs confirmation, returning
     * without calling this settles as UNKNOWN rather than as success — an accepted command is not
     * a played track.
     */
    fun confirm(detail: String? = null) { confirmed = true; confirmedDetail = detail }
}

/**
 * §3's feedback contract, one entry per control.
 *
 * Keyed by target so two buttons cannot overwrite each other's state, which is what a single
 * `working: String?` on the UI state did. A second press on a target already running is refused
 * rather than queued, so double-tapping cannot send a command twice.
 *
 * A settled result stays in the map until the same target starts again or the user dismisses it:
 * §3 requires that a missed notification can still be read.
 */
class OperationRegistry(
    private val scope: CoroutineScope,
    private val explain: (Throwable) -> String,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutable = MutableStateFlow<Map<String, Operation>>(emptyMap())
    val flow = mutable.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun of(target: String): Operation? = mutable.value[target]
    fun busy(target: String) = mutable.value[target]?.running == true
    /** The verb for whichever operation is running, for the auxiliary bottom notice. */
    fun anyRunning(): Operation? = mutable.value.values.firstOrNull { it.running }

    /** Launches [block] as an operation. Returns null, and does nothing, if the target is busy. */
    fun start(kind: OperationKind, target: String, block: suspend (OperationHandle) -> Unit): Job? {
        if (busy(target)) return null
        val job = scope.launch { run(kind, target, block) }
        jobs[target] = job
        return job
    }

    /** The same contract for a caller that is already inside a coroutine it wants to keep. */
    suspend fun run(kind: OperationKind, target: String, block: suspend (OperationHandle) -> Unit) {
        if (busy(target)) return
        val id = UUID.randomUUID().toString()
        mutable.update { it + (target to Operation.running(id, kind, target, clock())) }
        val handle = OperationHandle(id) { stage -> patch(target, id) { op -> op.copy(stage = stage) } }
        try {
            withTimeout(kind.timeoutMs) { block(handle) }
            val ok = !kind.needsConfirmation || handle.confirmed
            settle(target, id, if (ok) OperationPhase.SUCCEEDED else OperationPhase.UNKNOWN,
                handle.confirmedDetail, retryable = !ok)
        } catch (t: TimeoutCancellationException) {
            // §3: a timeout is not a confirmed failure. The work may well have landed.
            settle(target, id, OperationPhase.UNKNOWN,
                "${kind.timeoutMs / 1000}초 안에 결과를 확인하지 못했어요", retryable = true)
        } catch (c: CancellationException) {
            withContext(NonCancellable) { settle(target, id, OperationPhase.CANCELLED, null, retryable = true) }
            throw c
        } catch (e: Exception) {
            settle(target, id, OperationPhase.FAILED, explain(e), retryable = true)
        } finally {
            jobs.remove(target)
        }
    }

    /** §3: cancelling stops waiting. It does not undo a command already sent. */
    fun cancel(target: String) { jobs[target]?.cancel() }

    fun dismiss(target: String) {
        mutable.update { current -> current[target]?.takeIf { it.settled }?.let { current - target } ?: current }
    }

    fun clear() { jobs.values.forEach { it.cancel() }; jobs.clear(); mutable.value = emptyMap() }

    private fun settle(target: String, id: String, phase: OperationPhase, detail: String?, retryable: Boolean) =
        patch(target, id) { it.copy(phase = phase, detail = detail, retryable = retryable, settledAt = clock()) }

    /** Only the operation that is still the current one for this target may write to it. */
    private fun patch(target: String, id: String, change: (Operation) -> Operation) =
        mutable.update { current ->
            val existing = current[target]
            if (existing == null || existing.id != id) current else current + (target to change(existing))
        }

    companion object {
        const val SELECTION = "selection"
        const val CONTEXT = "context"
        const val POOL = "pool"
        const val RESET = "reset"
        fun zone(name: String) = "zone.$name"
    }
}
