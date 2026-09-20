package ai.drivemuse.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 1 §6. Three tracks used to run out and Spotify's own autoplay took over. This asks for the
 * next batch the moment the last track of the current one starts playing, which is late enough to
 * include the earlier tracks' outcomes and early enough to fill the queue before the music stops.
 *
 * It decides *when*, never *what*: selection stays in the view model. Two guards keep it honest —
 * one preparation per batch, and a revision so a proposal built on a queue that has since changed
 * is discarded rather than applied (§30, T22).
 */
/** What a preparation attempt did, so a failure is a state rather than a silence. */
sealed interface PrepareOutcome {
    data object Prepared : PrepareOutcome
    /** Worth another go: network, database, a transient selection failure. */
    data class RetryableFailure(val reason: String) : PrepareOutcome
    /** Built on a queue that has since moved. Discard, do not retry. */
    data object Stale : PrepareOutcome
    /** Nothing left to offer. Retrying will not change that. */
    data class Exhausted(val reason: String) : PrepareOutcome
}

class NextBatchScheduler(
    private val prepare: suspend (Long) -> PrepareOutcome,
    /** Where preparation runs. Never the collector: see [onStarted]. */
    private val scope: CoroutineScope,
    private val maxAttempts: Int = 2
) {
    private val mutex = Mutex()
    private var revision = 0L
    private var running = false
    private var preparedFor: String? = null
    private var attempts = 0
    private var job: Job? = null

    /** Called whenever the queue is replaced or dropped, so requests in flight stop applying. */
    suspend fun reset() { mutex.withLock { preparedFor = null; attempts = 0; revision++; job?.cancel(); job = null } }

    /** True while the caller's proposal is still based on the queue the app currently owns. */
    suspend fun accepts(base: Long) = mutex.withLock { base == revision }

    /**
     * FIX-C. This used to await prepare() inside the player-state collector, so for as long as
     * selection and the network took, no player callback was processed — exactly the window in
     * which an external track change would be missed. It now hands the work to the session scope
     * and returns immediately.
     *
     * preparedFor also used to be set before the work and never cleared on failure, so one failed
     * preparation stopped the batch being prepared ever again. It is cleared on a retryable
     * failure, up to a small bound.
     */
    suspend fun onStarted(trackId: String, ordinal: Int, plannedSize: Int) {
        val base = mutex.withLock {
            // Anything but the final slot of the batch still has music behind it.
            if (plannedSize <= 0 || ordinal < plannedSize - 1) return
            if (running || preparedFor == trackId) return
            running = true; preparedFor = trackId; revision
        }
        job = scope.launch {
            val outcome = runCatching { prepare(base) }
                .getOrElse { PrepareOutcome.RetryableFailure(it.message ?: it::class.simpleName ?: "알 수 없는 오류") }
            mutex.withLock {
                running = false
                when (outcome) {
                    is PrepareOutcome.RetryableFailure -> {
                        attempts++
                        // Let the next start try again, unless it has already failed enough times.
                        if (attempts < maxAttempts) preparedFor = null
                    }
                    is PrepareOutcome.Prepared -> attempts = 0
                    // Stale and Exhausted are answers, not failures to retry around.
                    else -> Unit
                }
            }
        }
    }
}
