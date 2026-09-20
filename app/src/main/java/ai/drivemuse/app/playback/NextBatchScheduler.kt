package ai.drivemuse.app.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val maxAttempts: Int = 3,
    /** Delay before attempt n (1-based). Injectable so tests do not wait. */
    private val backoffMs: (Int) -> Long = { attempt -> 5_000L * attempt }
) {
    private val mutex = Mutex()
    private var revision = 0L
    private var running = false
    private var preparedFor: String? = null
    private var job: Job? = null

    /**
     * Called whenever the queue is replaced or dropped, so requests in flight stop applying.
     *
     * Clearing `running` here matters: a cancelled job never reaches the block that would have
     * cleared it, and a stuck `running` makes every later start return early — the batch would
     * then never be prepared again for the life of the session.
     */
    suspend fun reset() {
        val cancelled = mutex.withLock {
            preparedFor = null; revision++; running = false
            job.also { job = null }
        }
        cancelled?.cancel()
    }

    /** True while the caller's proposal is still based on the queue the app currently owns. */
    suspend fun accepts(base: Long) = mutex.withLock { base == revision }

    /**
     * FIX-C. This used to await prepare() inside the player-state collector, so for as long as
     * selection and the network took, no player callback was processed — exactly the window in
     * which an external track change would be missed. It hands the work to the session scope and
     * returns immediately.
     *
     * Retries are scheduled here rather than left to the next start. The observer announces a
     * given attempt once, so while the last track of a batch keeps playing there is no second
     * notification to retry on: clearing a marker and hoping was not a retry at all. Attempts stop
     * as soon as the queue moves, since a proposal for a queue that no longer exists is worthless.
     */
    suspend fun onStarted(trackId: String, ordinal: Int, plannedSize: Int) {
        val base = mutex.withLock {
            // Anything but the final slot of the batch still has music behind it.
            if (plannedSize <= 0 || ordinal < plannedSize - 1) return
            if (running || preparedFor == trackId) return
            running = true; preparedFor = trackId; revision
        }
        val started = scope.launch {
            try {
                var attempt = 1
                while (true) {
                    // CancellationException must propagate: a cancelled preparation is not a
                    // failure to retry around, and swallowing it would retry against a dead queue.
                    val outcome = try { prepare(base) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { PrepareOutcome.RetryableFailure(e.message ?: e::class.simpleName ?: "알 수 없는 오류") }
                    if (outcome !is PrepareOutcome.RetryableFailure || attempt >= maxAttempts) break
                    delay(backoffMs(attempt))
                    // The queue moved while waiting; whatever we would prepare is already stale.
                    if (!accepts(base)) break
                    attempt++
                }
            } finally {
                // NonCancellable: on cancellation this still has to run, but only if reset() has
                // not already taken over the state for a newer queue.
                withContext(NonCancellable) {
                    mutex.withLock { if (base == revision) running = false }
                }
            }
        }
        mutex.withLock { if (base == revision) job = started else started.cancel() }
    }
}
