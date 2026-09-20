package ai.drivemuse.domain

/**
 * Why the visible list no longer matches the conditions it was built under.
 *
 * A single boolean could only be cleared wholesale, so a successful recovery from an off-plan
 * recording also cleared a rule change that was still outstanding — or, worse, left the queue
 * marked stale forever and the next button permanently refusing. Each reason clears on its own
 * terms, and only some of them are cleared by resuming playback.
 */
enum class StaleReason {
    /** Something the app did not plan was playing. Cleared by a confirmed recovery. */
    CONTROL_LOST,
    /** The survey changed. Cleared by building a plan on the new profile. */
    PROFILE_CHANGED,
    /** A rule changed. Cleared by building a plan under the new rules. */
    RULE_CHANGED,
    /** A queue restored from disk after a restart, never checked against the player. */
    RESTORE_UNVERIFIED,
    /** Tracks were sent but their fate is unknown. Cleared by re-reading the player. */
    DELIVERY_UNCERTAIN;

    /**
     * Whether confirming playback of the intended recording is enough to clear this one.
     *
     * DELIVERY_UNCERTAIN is deliberately not in this set. Hearing the track the user chose says
     * nothing about the tracks queued behind it, and treating it as proof cleared the flag in the
     * same breath as it was raised.
     */
    val clearedByConfirmedPlayback get() = this == CONTROL_LOST || this == RESTORE_UNVERIFIED

    /**
     * Whether choosing again is what fixes it. Only conditions that moved qualify: re-running
     * selection cannot tell you what Spotify did with a queue command, and an unverified restored
     * list is fixed by playing from it, not by replacing it.
     */
    val fixedByReselection get() = this == PROFILE_CHANGED || this == RULE_CHANGED
}

/**
 * Monotonic counter over losses of control: an off-plan recording, an explicit stop. It exists so a
 * command issued before the loss cannot report success afterwards and quietly hand the queue back.
 *
 * In memory on purpose. It guards callbacks inside one process; a restart cannot have callbacks in
 * flight, and the durable half of the state is the controlLost flag.
 */
class ControlEpoch {
    @Volatile private var value: Long = 0
    val current get() = value
    /** Returns the new epoch, so the caller can compare against what it captured. */
    fun advance(): Long = synchronized(this) { ++value }
    /** True when nothing has intervened since [captured]. */
    fun stillCurrent(captured: Long) = captured == value
}
