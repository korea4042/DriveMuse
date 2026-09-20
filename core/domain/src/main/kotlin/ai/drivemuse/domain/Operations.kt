package ai.drivemuse.domain

/**
 * §3 of the automation UX design: the common feedback contract.
 *
 * Every user-initiated action carries an id, a kind, a target, a stage, a start time, an error and
 * whether retrying is sensible. Before this, "did my tap register" was a question the user answered
 * by waiting: a tap either changed nothing on screen until the work finished, or produced a single
 * indefinite snackbar that whichever coroutine spoke last overwrote.
 *
 * Everything here is pure data. The registry that owns the coroutines lives in the app module.
 */
enum class OperationPhase { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

/**
 * What kind of work this is, and how long it is allowed to take before the result stops being
 * knowable. §3 gives the budgets: location 15 s, weather 12 s, selection 30 s, playback confirm
 * 10 s. They live here so they are adjustable in one place rather than scattered as literals.
 */
enum class OperationKind(
    val verb: String,
    val timeoutMs: Long,
    /**
     * True when the work sends a command to something outside this app. A timeout or a cancel on
     * one of these does not mean the command did not land, so the app must not say it was undone
     * and must not quietly send it again.
     */
    val external: Boolean = false,
    /**
     * True when finishing without an error is not itself evidence of success. Playback is the case
     * that matters: Spotify accepting a command is not Spotify playing the track.
     */
    val needsConfirmation: Boolean = false
) {
    LOCATION("위치를 확인하는 중", 15_000),
    WEATHER("날씨를 확인하는 중", 12_000),
    /** Location then weather behind one button: §3's two budgets back to back, not a new number. */
    CONTEXT("위치와 날씨를 확인하는 중", 27_000),
    SELECTION("곡을 고르는 중", 30_000),
    PLAYBACK("재생을 요청하는 중", 10_000, external = true, needsConfirmation = true),
    POOL("후보를 불러오는 중", 30_000),
    SAVE("저장하는 중", 10_000),
    RESET("초기화하는 중", 60_000)
}

/** The three stages §3 asks for once a second has passed. */
enum class OperationStage(val label: String) {
    STARTING(""), CONNECTING("연결 확인"), PREPARING("곡 준비"), CONFIRMING("재생 확인")
}

data class Operation(
    val id: String,
    val kind: OperationKind,
    /** Which control owns this, so two buttons cannot overwrite each other's state. */
    val target: String,
    val phase: OperationPhase,
    val stage: OperationStage = OperationStage.STARTING,
    val startedAt: Long = 0,
    val settledAt: Long = 0,
    val detail: String? = null,
    val retryable: Boolean = false
) {
    val running get() = phase == OperationPhase.RUNNING
    val settled get() = phase != OperationPhase.IDLE && phase != OperationPhase.RUNNING

    fun elapsed(now: Long) = (now - startedAt).coerceAtLeast(0)
    /** §3: stages appear after a second, not immediately — a flash of "연결 확인" is noise. */
    fun showStage(now: Long) = running && elapsed(now) >= STAGE_AFTER_MS && stage != OperationStage.STARTING
    /** §3: at eight seconds, say so and offer a cancel. Never invent a progress bar. */
    fun slow(now: Long) = running && elapsed(now) >= SLOW_AFTER_MS

    /** Whether cancelling is offered. Only once it is actually slow; not for a reset mid-flight. */
    fun cancellable(now: Long) = slow(now) && kind != OperationKind.RESET

    /** What the control says right now. */
    fun label(now: Long): String = when (phase) {
        OperationPhase.IDLE -> ""
        OperationPhase.RUNNING -> when {
            slow(now) -> "평소보다 오래 걸리고 있어요"
            showStage(now) -> "${kind.verb} · ${stage.label}"
            else -> kind.verb
        }
        OperationPhase.SUCCEEDED -> detail ?: "완료했어요"
        // §3: a timeout is not a confirmed failure, and neither is a cancel of something already
        // sent. Both land here, and neither claims the outside world was left untouched.
        OperationPhase.UNKNOWN -> detail ?: "결과를 확인하지 못했어요"
        OperationPhase.CANCELLED -> if (kind.external) "취소됨 · 이미 보낸 명령은 되돌리지 못해요" else "취소됨"
        OperationPhase.FAILED -> detail ?: "실패했어요"
    }

    /**
     * §3: do not resend a playback or queue command whose result is unknown. Retrying local work
     * whose result is unknown is fine; retrying an external command that may already have landed
     * is how a track gets queued twice.
     */
    val safeToRetry get() = retryable && !(phase == OperationPhase.UNKNOWN && kind.external)

    companion object {
        const val STAGE_AFTER_MS = 1_000L
        const val SLOW_AFTER_MS = 8_000L
        fun running(id: String, kind: OperationKind, target: String, at: Long) =
            Operation(id, kind, target, OperationPhase.RUNNING, OperationStage.STARTING, at)
    }
}
