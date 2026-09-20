package ai.drivemuse.app.playback

import ai.drivemuse.app.DriveDatabase
import ai.drivemuse.app.PlaybackAttemptEntity
import ai.drivemuse.app.PlaybackEventEntity
import ai.drivemuse.app.learning.LearningStore
import ai.drivemuse.app.spotify.RemotePlayerState
import ai.drivemuse.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Phase 1 §4. The player state stream was previously collected by nobody, so nothing the driver did
 * was ever recorded. This turns each callback into an attempt-scoped event and, when the track
 * changes or the connection drops, closes the attempt with one TrackOutcome.
 *
 * Two rules keep it honest. Only tracks this app put in the queue are observed, because a recording
 * the user picked in Spotify is not an answer to a recommendation. And a track change on its own
 * never becomes a skip: the cause comes from the app's own command log or stays unverified (§7).
 */
class PlaybackObserver(
    private val db: DriveDatabase,
    private val learning: LearningStore,
    private val session: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Fired outside the lock once a planned track is confirmed playing, for the scheduler (§6). */
    private val onStarted: suspend (trackId: String, ordinal: Int, plannedSize: Int) -> Unit = { _, _, _ -> },
    /**
     * R09. Fired once per unplanned recording. Before this the id was stored and never read, so a
     * recording the app did not queue produced no response at all. Carries no listening evidence:
     * an unplanned track says something about control, nothing about taste.
     */
    private val onUnplanned: suspend (trackId: String) -> Unit = { }
) {
    /** Ordinal of each track the app handed to the player, so an auto-advance is still ours. */
    private val planned = LinkedHashMap<String, Int>()
    private val mutex = Mutex()
    private var job: Job? = null
    private var attempt: PlaybackAttemptEntity? = null
    private var durationMs: Long? = null
    private var lastPositionMs = 0L
    private var lastStoredAt = 0L
    private var commandAt: Long? = null
    private val observations = mutableListOf<Observation>()
    /** Set when the player reports a recording the app did not queue (§30 suspend condition). */
    var unplannedTrackId: String? = null; private set
    /**
     * R09. One episode, not one track id. Without this a driver who picks three songs of their own
     * raises three events, and a return to the same off-plan track later in the session raises
     * none. The episode ends when a planned recording is observed again.
     */
    private var unplannedEpisode = false

    /** What the state handler wants said once the lock is released. */
    private sealed interface Signal {
        data class Started(val trackId: String, val ordinal: Int, val plannedSize: Int) : Signal
        data class Unplanned(val trackId: String) : Signal
    }

    fun start(scope: CoroutineScope, states: Flow<RemotePlayerState?>) {
        job?.cancel()
        job = scope.launch {
            states.collect { state ->
                when (val signal = onState(state)) {
                    is Signal.Started -> runCatching { onStarted(signal.trackId, signal.ordinal, signal.plannedSize) }
                    is Signal.Unplanned -> runCatching { onUnplanned(signal.trackId) }
                    null -> Unit
                }
            }
        }
    }
    fun stop() { job?.cancel(); job = null }

    /** Records the batch the app just sent to the player. Ordinals follow the queue order. */
    suspend fun plan(batchId: String?, tracks: List<Track>): Unit = mutex.withLock {
        unplannedEpisode = false
        unplannedTrackId = null
        planned.clear()
        tracks.forEachIndexed { index, track -> planned[track.id] = index }
        batch = batchId
    }
    private var batch: String? = null

    /** Called immediately before the app's own skip, so the resulting change has a known cause. */
    suspend fun commandedSkip(): String = mutex.withLock {
        val id = UUID.randomUUID().toString()
        commandAt = now()
        attempt = attempt?.copy(commandId = id)
        id
    }

    /** Closes whatever is open, e.g. when the user takes manual control or the app resets. */
    suspend fun release(trigger: EndTrigger = EndTrigger.SESSION_END): Unit = mutex.withLock { close(trigger) }

    private suspend fun onState(state: RemotePlayerState?): Signal? = mutex.withLock {
        if (state == null) { close(EndTrigger.DISCONNECTED); return@withLock null }
        val id = state.trackId ?: return@withLock null
        if (id != attempt?.trackId) {
            close(EndTrigger.TRACK_CHANGED)
            val ordinal = planned[id]
            // R09: report it once. Repeating the signal for every callback of the same recording
            // would turn one intervention into a stream of them.
            if (ordinal == null) {
                val first = !unplannedEpisode
                unplannedEpisode = true
                unplannedTrackId = id
                return@withLock if (first) Signal.Unplanned(id) else null
            }
            unplannedTrackId = null
            unplannedEpisode = false
            attempt = PlaybackAttemptEntity(UUID.randomUUID().toString(), id, session(), batch, ordinal, null, state.observedAt, null, null, "COMMANDED")
            durationMs = null; lastPositionMs = 0; lastStoredAt = 0; commandAt = null; observations.clear()
        }
        val open = attempt ?: return@withLock null
        durationMs = state.durationMs.takeIf { it > 0 } ?: durationMs
        lastPositionMs = state.positionMs
        // §7: the command being accepted is not a start. PLAYING with the expected id is.
        var started: Signal.Started? = null
        if (open.confirmedAt == null && !state.paused) {
            attempt = open.copy(confirmedAt = state.observedAt, state = "START_CONFIRMED")
            db.intelligence().putAttempt(attempt!!)
            started = Signal.Started(id, open.ordinal, planned.size)
        }
        val observation = Observation(
            UUID.randomUUID().toString(), open.attemptId, state.observedAt, state.positionMs,
            if (state.paused) MediaState.PAUSED else MediaState.PLAYING
        )
        observations += observation
        // The player repeats itself while paused; store a row only when something actually moved.
        val moved = observations.size == 1 || state.observedAt - lastStoredAt >= 1000
        if (moved) {
            lastStoredAt = state.observedAt
            runCatching { db.intelligence().putEvent(PlaybackEventEntity(observation.id, open.attemptId, state.observedAt, state.positionMs, state.durationMs, state.paused, "APP_REMOTE")) }
        }
        started
    }

    /** An attempt that never played is abandoned, not disliked: there is nothing to learn from it. */
    private suspend fun close(trigger: EndTrigger) {
        val open = attempt ?: return
        attempt = null
        val endedAt = now()
        if (open.confirmedAt == null) {
            runCatching { db.intelligence().putAttempt(open.copy(endedAt = endedAt, state = "ABANDONED")) }
            observations.clear(); return
        }
        val totals = ListeningAggregator.aggregate(observations.toList(), durationMs)
        val judgement = EndReasonResolver.resolve(AttemptClose(endedAt, lastPositionMs, durationMs, trigger, commandAt))
        runCatching { learning.record(open, totals, judgement, endedAt) }
        observations.clear(); commandAt = null; durationMs = null; lastPositionMs = 0
    }
}
