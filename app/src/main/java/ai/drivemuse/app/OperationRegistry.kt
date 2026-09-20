package ai.drivemuse.app

import ai.drivemuse.domain.Operation
import ai.drivemuse.domain.OperationKind
import ai.drivemuse.domain.OperationPhase
import ai.drivemuse.domain.OperationStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
    @Volatile internal var outcome: Pair<OperationPhase, String?>? = null

    fun stage(stage: OperationStage) = onStage(stage)

    /**
     * §3: "성공 표시는 실제 완료 근거가 있을 때만". For a kind that needs confirmation, returning
     * without calling this settles as UNKNOWN rather than as success — an accepted command is not
     * a played track, and a selection that ended without committing a batch is not a selection.
     */
    fun confirm(detail: String? = null) { outcome = OperationPhase.SUCCEEDED to detail }

    /**
     * The work ran to completion and deliberately produced nothing: a stale generation, a revision
     * that moved under it. Not a failure, not a success, and not "결과를 확인하지 못했어요" either —
     * the result is perfectly well known and it is that nothing was applied.
     */
    fun discard(detail: String) { outcome = OperationPhase.CANCELLED to detail }
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

    /**
     * Launches [block] as an operation. Returns null, and does nothing, if the target is busy.
     *
     * RUNNING is published here, on the caller's thread, before the coroutine starts: a double tap
     * arrives faster than a dispatch, and claiming the target inside the launched body left a
     * window in which both presses saw an idle target (UX01).
     */
    fun start(kind: OperationKind, target: String, timeoutMs: Long = kind.timeoutMs, block: suspend (OperationHandle) -> Unit): Job? {
        val id = claim(kind, target) ?: return null
        val job = scope.launch { execute(id, kind, target, timeoutMs, block) }
        jobs[target] = job
        // RUNNING is published before the coroutine is dispatched, so a cancel that lands in
        // between — a scope torn down, a user cancelling immediately — leaves a body that never
        // runs and therefore never settles. This is the only place that can see that happen.
        job.invokeOnCompletion { cause ->
            jobs.remove(target, job)
            val current = mutable.value[target]
            if (current?.id == id && current.running) settle(
                target, id,
                if (cause is CancellationException) OperationPhase.CANCELLED else OperationPhase.UNKNOWN,
                if (cause is CancellationException) null else "시작하지 못했어요",
                retryable = true
            )
        }
        return job
    }

    /**
     * The same contract for a caller that is already inside a coroutine it wants to keep. There is
     * no job to register, so [cancel] cannot reach it; only use it for work that is not cancellable.
     */
    suspend fun run(kind: OperationKind, target: String, timeoutMs: Long = kind.timeoutMs, block: suspend (OperationHandle) -> Unit) {
        val id = claim(kind, target) ?: return
        execute(id, kind, target, timeoutMs, block)
    }

    /** Takes the target for a new operation, or returns null because something else holds it. */
    private fun claim(kind: OperationKind, target: String): String? {
        var claimed: String? = null
        mutable.update { current ->
            if (current[target]?.running == true) { claimed = null; current }
            else {
                val id = UUID.randomUUID().toString()
                claimed = id
                current + (target to Operation.running(id, kind, target, clock()))
            }
        }
        return claimed
    }

    private suspend fun execute(id: String, kind: OperationKind, target: String, timeoutMs: Long, block: suspend (OperationHandle) -> Unit) {
        val handle = OperationHandle(id) { stage -> patch(target, id) { op -> op.copy(stage = stage) } }
        try {
            withTimeout(timeoutMs) { block(handle) }
            val declared = handle.outcome
            when {
                declared != null -> settle(target, id, declared.first, declared.second,
                    retryable = declared.first != OperationPhase.SUCCEEDED)
                // Finishing quietly is not evidence. A kind that needs confirmation and did not
                // get one settles UNKNOWN, which is exactly what the caller left it as.
                kind.needsConfirmation -> settle(target, id, OperationPhase.UNKNOWN, null, retryable = true)
                else -> settle(target, id, OperationPhase.SUCCEEDED, null, retryable = false)
            }
        } catch (t: TimeoutCancellationException) {
            // §3: a timeout is not a confirmed failure. The work may well have landed.
            settle(target, id, OperationPhase.UNKNOWN,
                "${timeoutMs / 1000}초 안에 결과를 확인하지 못했어요", retryable = true)
        } catch (c: CancellationException) {
            withContext(NonCancellable) { settle(target, id, OperationPhase.CANCELLED, null, retryable = true) }
            throw c
        } catch (e: Exception) {
            settle(target, id, OperationPhase.FAILED, explain(e), retryable = true)
        } finally {
            // Only this operation's own job, never a newer one that has already taken the target.
            currentCoroutineContext()[Job]?.let { jobs.remove(target, it) }
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
        const val DEPARTURE = "departure"
        fun zone(name: String) = "zone.$name"
        fun schedule(direction: String) = "schedule.$direction"
    }
}
