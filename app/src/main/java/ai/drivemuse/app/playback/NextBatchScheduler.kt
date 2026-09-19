package ai.drivemuse.app.playback

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
class NextBatchScheduler(private val prepare: suspend (Long) -> Unit) {
    private val mutex = Mutex()
    private var revision = 0L
    private var running = false
    private var preparedFor: String? = null

    /** Called whenever the queue is replaced or dropped, so requests in flight stop applying. */
    suspend fun reset() { mutex.withLock { preparedFor = null; revision++ } }

    /** True while the caller's proposal is still based on the queue the app currently owns. */
    suspend fun accepts(base: Long) = mutex.withLock { base == revision }

    suspend fun onStarted(trackId: String, ordinal: Int, plannedSize: Int) {
        val base = mutex.withLock {
            // Anything but the final slot of the batch still has music behind it.
            if (plannedSize <= 0 || ordinal < plannedSize - 1) return
            if (running || preparedFor == trackId) return
            running = true; preparedFor = trackId; revision
        }
        try { prepare(base) } finally { mutex.withLock { running = false } }
    }
}
