package ai.drivemuse.domain

/*
 * Technical design v2.3 §30 "반응에 따른 세 곡 큐 조정".
 * Three tracks are a plan, not a promise. A slot moves PLANNED → LOCKED (command reserved)
 * → START_CONFIRMED (observed playing) → TERMINAL. Only PLANNED slots are replaceable, and a
 * replacement is applied by comparing the revision the proposal was based on.
 */

enum class SlotState { PLANNED, LOCKED, START_CONFIRMED, TERMINAL, CANCELLED }

data class Slot(val batchItemId: String, val ordinal: Int, val trackId: String, val state: SlotState = SlotState.PLANNED, val attemptId: String? = null, val commandId: String? = null)

data class SlotBatch(
    val batchId: String, val sessionId: String, val generation: Long, val queueRevision: Long,
    val evidenceVersion: Long, val candidateSetId: String, val slots: List<Slot>, val status: BatchStatus = BatchStatus.READY
) {
    val replaceableOrdinals get() = slots.filter { it.state == SlotState.PLANNED }.map { it.ordinal }
    val lockedItemIds get() = slots.filter { it.state == SlotState.LOCKED || it.state == SlotState.START_CONFIRMED }.map { it.batchItemId }
    val activeSlot get() = slots.singleOrNull { it.state == SlotState.START_CONFIRMED }
    val complete get() = slots.all { it.state == SlotState.TERMINAL || it.state == SlotState.CANCELLED }
}

data class QueueUpdateRequest(val sessionId: String, val generation: Long, val baseQueueRevision: Long, val evidenceVersion: Long, val candidateSetId: String, val lockedItemIds: List<String>, val replaceableOrdinals: List<Int>)
data class SlotProposal(val ordinal: Int, val trackId: String, val reasonEvidenceIds: List<String>)

sealed class QueueResult {
    data class Applied(val batch: SlotBatch, val cancelled: List<Slot>) : QueueResult()
    data class Rejected(val reason: String) : QueueResult()
}

object SlotQueuePolicy {
    fun request(b: SlotBatch) = QueueUpdateRequest(b.sessionId, b.generation, b.queueRevision, b.evidenceVersion, b.candidateSetId, b.lockedItemIds, b.replaceableOrdinals)

    /**
     * Compare-and-set replacement of PLANNED slots. Rejects stale base revisions, unknown or
     * non-replaceable ordinals, duplicates against locked/active tracks, and out-of-candidate IDs.
     * Replaced slots become CANCELLED and carry no listening or dislike evidence.
     */
    fun apply(current: SlotBatch, req: QueueUpdateRequest, proposals: List<SlotProposal>, candidateIds: Set<String>, now: Long, nextItemId: (Int) -> String): QueueResult {
        if (req.sessionId != current.sessionId || req.generation != current.generation) return QueueResult.Rejected("SESSION_MISMATCH")
        if (req.baseQueueRevision != current.queueRevision) return QueueResult.Rejected("STALE_REVISION")
        if (req.candidateSetId != current.candidateSetId) return QueueResult.Rejected("CANDIDATE_SET_MISMATCH")
        if (current.status == BatchStatus.SUSPENDED || current.status == BatchStatus.INVALIDATED) return QueueResult.Rejected("BATCH_${current.status}")
        val replaceable = current.replaceableOrdinals.toSet()
        if (proposals.isEmpty()) return QueueResult.Rejected("EMPTY_PROPOSAL")
        if (proposals.any { it.ordinal !in replaceable }) return QueueResult.Rejected("ORDINAL_NOT_REPLACEABLE")
        if (proposals.map { it.ordinal }.distinct().size != proposals.size) return QueueResult.Rejected("DUPLICATE_ORDINAL")
        val ids = proposals.map { it.trackId }
        if (ids.distinct().size != ids.size) return QueueResult.Rejected("DUPLICATE_TRACK")
        if (ids.any { it !in candidateIds }) return QueueResult.Rejected("TRACK_NOT_IN_CANDIDATES")
        val kept = current.slots.filter { it.ordinal !in proposals.map { p -> p.ordinal } && it.state != SlotState.CANCELLED }.map { it.trackId }
        if (ids.any { it in kept }) return QueueResult.Rejected("DUPLICATES_LOCKED_OR_ACTIVE")
        val cancelled = mutableListOf<Slot>()
        val slots = current.slots.flatMap { s ->
            val p = proposals.firstOrNull { it.ordinal == s.ordinal }
            if (p == null) listOf(s) else if (p.trackId == s.trackId) listOf(s) else { cancelled += s.copy(state = SlotState.CANCELLED); listOf(s.copy(state = SlotState.CANCELLED), Slot(nextItemId(s.ordinal), s.ordinal, p.trackId)) }
        }
        return QueueResult.Applied(current.copy(slots = slots, queueRevision = current.queueRevision + 1, evidenceVersion = maxOf(current.evidenceVersion, req.evidenceVersion)), cancelled)
    }

    /** Reserve a playback command for the next PLANNED slot. Only one active slot is allowed (§30). */
    fun lock(b: SlotBatch, ordinal: Int, commandId: String): SlotBatch? {
        if (b.activeSlot != null && b.slots.any { it.state == SlotState.LOCKED }) return null
        val s = b.slots.firstOrNull { it.ordinal == ordinal && it.state == SlotState.PLANNED } ?: return null
        if (b.slots.any { it.commandId == commandId }) return null
        return b.copy(slots = b.slots.map { if (it === s) it.copy(state = SlotState.LOCKED, commandId = commandId) else it }, queueRevision = b.queueRevision + 1)
    }

    /** Observed track ID must match the LOCKED slot; the slot becomes the single active one. */
    fun confirmStart(b: SlotBatch, observedTrackId: String, attemptId: String): SlotBatch? {
        if (b.activeSlot != null) return null
        val s = b.slots.firstOrNull { it.state == SlotState.LOCKED && it.trackId == observedTrackId } ?: return null
        return b.copy(slots = b.slots.map { if (it === s) it.copy(state = SlotState.START_CONFIRMED, attemptId = attemptId) else it }, status = BatchStatus.ACTIVE)
    }

    /** Terminates the active slot exactly once; retries of the same Track on another video reuse the slot. */
    fun terminate(b: SlotBatch, attemptId: String): SlotBatch {
        val s = b.slots.firstOrNull { it.state == SlotState.START_CONFIRMED && it.attemptId == attemptId } ?: return b
        val slots = b.slots.map { if (it === s) it.copy(state = SlotState.TERMINAL) else it }
        return b.copy(slots = slots, status = if (slots.all { it.state == SlotState.TERMINAL || it.state == SlotState.CANCELLED }) BatchStatus.COMPLETED else BatchStatus.ACTIVE)
    }

    /** Manual selection elsewhere: cancel PLANNED slots and drop pending commands; the current track is not touched. */
    fun suspend(b: SlotBatch): SlotBatch = b.copy(status = BatchStatus.SUSPENDED, slots = b.slots.map { if (it.state == SlotState.PLANNED || it.state == SlotState.LOCKED) it.copy(state = SlotState.CANCELLED) else it })

    /** After a restart, LOCKED but unconfirmed commands are not resent; they return to PLANNED for re-check. */
    fun recoverAfterRestart(b: SlotBatch): SlotBatch = b.copy(slots = b.slots.map { if (it.state == SlotState.LOCKED) it.copy(state = SlotState.PLANNED, commandId = null) else it })
}
