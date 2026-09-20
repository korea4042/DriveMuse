package ai.drivemuse.domain

/**
 * Steering-wheel media buttons, §1–§6 of the shortcut design.
 *
 * Everything here is pure: no clock, no Android types, no I/O. The reducer is fed events and
 * returns effects, so the timing boundaries and the discard rules can be tested with a fake clock
 * rather than by pressing buttons in a car — which is the only way most of these paths would ever
 * be reached, since they are races and lost events.
 *
 * Nothing here decides whether the buttons can be received at all. That is §3's capability
 * question and it is answered on a device, not here.
 */

/** Only the three media keys the design covers. STOP is never folded into PLAY_PAUSE (§1). */
enum class SteeringKey { NEXT, PREVIOUS, PLAY_PAUSE }

enum class KeyAction { DOWN, UP }

enum class Transport { BLUETOOTH, ANDROID_AUTO, WIRED, UNKNOWN }

/**
 * §4: who performs the ordinary button action. The reducer only ever dispatches a base command
 * when the app is the confirmed consumer; when the player already handled it, sending one too
 * would double it.
 */
enum class BaseHandlingOwner { EXTERNAL_PLAYER, DRIVEMUSE, UNKNOWN }

enum class Gesture { LONG_PRESS, DOUBLE_TAP }

enum class ShortcutAction { RESET_SELECTION, REASSESS_CONTEXT, RATE_UP, RATE_DOWN }

/** The four mappings of §1. Each is enabled separately and only where verified. */
enum class Shortcut(val key: SteeringKey, val gesture: Gesture, val action: ShortcutAction, val label: String) {
    SC01(SteeringKey.NEXT, Gesture.LONG_PRESS, ShortcutAction.RESET_SELECTION, "다음 곡 길게 · 선곡 리셋"),
    SC02(SteeringKey.PREVIOUS, Gesture.LONG_PRESS, ShortcutAction.REASSESS_CONTEXT, "이전 곡 길게 · 상황 재판단"),
    SC03(SteeringKey.PLAY_PAUSE, Gesture.DOUBLE_TAP, ShortcutAction.RATE_UP, "재생·정지 짧게 두 번 · 좋아요"),
    SC04(SteeringKey.PLAY_PAUSE, Gesture.LONG_PRESS, ShortcutAction.RATE_DOWN, "재생·정지 길게 · 싫어요");

    companion object {
        fun of(key: SteeringKey, gesture: Gesture) = entries.firstOrNull { it.key == key && it.gesture == gesture }
    }
}

// --- §3: what a given car, over a given transport, is known to deliver ---

enum class Capability(val label: String, val usable: Boolean) {
    UNTESTED("확인 전", false),
    SUPPORTED("지원 확인", true),
    /** Verified only under stated conditions — never presented as always-on while driving. */
    LIMITED("조건부 지원", true),
    UNSUPPORTED("미지원", false)
}

enum class CapabilityCondition(val label: String) {
    SCREEN_ON("화면이 켜져 있을 때만"),
    FOREGROUND_ONLY("앱이 화면에 있을 때만"),
    PLAYING_ONLY("재생 중일 때만")
}

/**
 * One verified result. Stored per vehicle, per transport, per shortcut: one gesture working says
 * nothing about the others, and a result from another car says nothing at all.
 */
data class CapabilityRecord(
    val vehicleId: String,
    val transport: Transport,
    val shortcut: Shortcut,
    val capability: Capability = Capability.UNTESTED,
    val conditions: Set<CapabilityCondition> = emptySet(),
    val checkedAt: Long = 0,
    /** The app, OS and player build the check was made against; a change invalidates it. */
    val appVersionCode: Int = 0,
    val osBuild: String = "",
    val playerVersion: String = "",
    val note: String = ""
) {
    /** §3: a check made against different software has to be repeated before it counts. */
    fun stale(appVersionCode: Int, osBuild: String, playerVersion: String) =
        capability != Capability.UNTESTED &&
        (this.appVersionCode != appVersionCode || this.osBuild != osBuild || this.playerVersion != playerVersion)

    fun active(userEnabled: Boolean, appVersionCode: Int, osBuild: String, playerVersion: String) =
        userEnabled && capability.usable && !stale(appVersionCode, osBuild, playerVersion)
}

// --- §5: inputs, snapshots and commands ---

data class SteeringInput(
    val eventId: String,
    val inputSourceId: String,
    val transport: Transport,
    val tripId: String?,
    val connectionEpoch: Long,
    val mediaSessionEpoch: Long,
    val key: SteeringKey,
    val action: KeyAction,
    /** Monotonic. §5 forbids wall-clock arithmetic on input intervals. */
    val downTimeElapsed: Long,
    val eventTimeElapsed: Long,
    val repeatCount: Int = 0,
    val canceled: Boolean = false,
    val baseHandlingOwner: BaseHandlingOwner = BaseHandlingOwner.UNKNOWN
) {
    /** §5.1: what makes two events the same physical press. */
    val pressId get() = "$inputSourceId:$connectionEpoch:${key.name}:$downTimeElapsed"
}

data class TrackSnapshot(
    val provider: String,
    val trackId: String,
    val mediaSessionEpoch: Long,
    val observedAtElapsed: Long,
    val playbackRevision: Long,
    val isSupportedContent: Boolean
)

data class ShortcutCommand(
    val commandId: String,
    val action: ShortcutAction,
    val target: TrackSnapshot?,
    val tripId: String?,
    val connectionEpoch: Long,
    val mediaSessionEpoch: Long,
    val requestedAtElapsed: Long,
    val gestureConfigVersion: String
)

enum class DiscardReason(val label: String) {
    CANCELED_EVENT("취소된 입력"),
    STUCK_PRESS("눌린 채로 시간 초과"),
    OTHER_KEY("다른 버튼이 눌림"),
    EPOCH_CHANGED("연결이 바뀜"),
    SESSION_CHANGED("재생 세션이 바뀜"),
    DISCONNECTED("연결이 끊김"),
    FEATURE_OFF("기능이 꺼짐"),
    RESYNC("이전 입력과 동기화 중"),
    GESTURE_DISABLED("이 조작은 켜져 있지 않음"),
    NO_TARGET_TRACK("대상 곡 불명"),
    STALE_SNAPSHOT("곡 정보가 오래됨"),
    UNSUPPORTED_CONTENT("평가할 수 없는 콘텐츠"),
    TRACK_CHANGED("누르는 사이 곡이 바뀜"),
    LONG_SECOND_PRESS("두 번째 누름이 길어 두 번 누르기가 아님")
}

sealed interface SteeringEffect {
    /** §4: only ever emitted on the first DOWN, and only when the app owns the base action. */
    data class DispatchBase(val key: SteeringKey, val pressId: String) : SteeringEffect
    data class Emit(val command: ShortcutCommand) : SteeringEffect
    data class Discard(val reason: DiscardReason, val shortcut: Shortcut?) : SteeringEffect
    /** Ask for a timer; the coordinator returns it as a Timeout event on the same queue (§5.1). */
    data class ScheduleTimeout(val token: String, val atElapsed: Long) : SteeringEffect
}

/** Tuning values. Trial figures, not measured optima (§5). */
data class SteeringThresholds(
    val version: String = "steering-v1",
    val longPressMs: Long = 800,
    val doubleTapGapMs: Long = 350,
    val stuckPressMs: Long = 5000,
    val trackSnapshotMaxAgeMs: Long = 2000
)

sealed interface SteeringEvent {
    data class Key(val input: SteeringInput, val snapshot: TrackSnapshot?) : SteeringEvent
    data class Timeout(val token: String, val atElapsed: Long) : SteeringEvent
    data class Invalidate(val reason: DiscardReason) : SteeringEvent
}

internal data class ActivePress(
    val pressId: String,
    val key: SteeringKey,
    val downAt: Long,
    val snapshot: TrackSnapshot?,
    val connectionEpoch: Long,
    val mediaSessionEpoch: Long,
    val tripId: String?,
    /** A press that only exists to re-establish sync after a stuck or lost one (§5.2.5). */
    val resyncOnly: Boolean = false
)

internal data class PendingTap(
    val gestureId: String,
    val upAt: Long,
    val snapshot: TrackSnapshot?,
    val connectionEpoch: Long,
    val mediaSessionEpoch: Long,
    val tripId: String?
)

data class SteeringState internal constructor(
    val enabled: Set<Shortcut> = emptySet(),
    internal val press: ActivePress? = null,
    internal val pendingTap: PendingTap? = null,
    /** After a stuck press, this key produces no further commands until one clean press passes. */
    internal val blocked: SteeringKey? = null,
    internal val epoch: Long = Long.MIN_VALUE
) {
    val idle get() = press == null && pendingTap == null
    companion object { fun initial(enabled: Set<Shortcut> = emptySet()) = SteeringState(enabled) }
}

data class SteeringResult(val state: SteeringState, val effects: List<SteeringEffect>)

/**
 * §5's rules as a state machine.
 *
 * Two of them shape everything else. A long press is confirmed on release, never by the timer
 * alone, so a lost UP cannot fire a command the driver never completed. And the base action is
 * dispatched on the first DOWN without waiting for any gesture window, so holding the button does
 * not delay the ordinary behaviour of the car.
 */
class SteeringGestureReducer(private val thresholds: SteeringThresholds = SteeringThresholds()) {

    fun reduce(state: SteeringState, event: SteeringEvent): SteeringResult = when (event) {
        is SteeringEvent.Invalidate -> clear(state, event.reason)
        is SteeringEvent.Timeout -> timeout(state, event)
        is SteeringEvent.Key -> key(state, event)
    }

    private fun clear(state: SteeringState, reason: DiscardReason): SteeringResult {
        if (state.idle) return SteeringResult(state.copy(press = null, pendingTap = null), emptyList())
        return SteeringResult(state.copy(press = null, pendingTap = null), listOf(SteeringEffect.Discard(reason, null)))
    }

    private fun timeout(state: SteeringState, event: SteeringEvent.Timeout): SteeringResult {
        state.press?.takeIf { stuckToken(it) == event.token }?.let { press ->
            // §5.2.5: held too long. The additional command is abandoned and this key stops being
            // judged until a clean press comes through, because the UP may simply be lost.
            return SteeringResult(
                state.copy(press = null, pendingTap = null, blocked = press.key),
                listOf(SteeringEffect.Discard(DiscardReason.STUCK_PRESS, null)))
        }
        state.pendingTap?.takeIf { tapToken(it) == event.token }?.let {
            // The second tap never came. The first press already did its base action, so there is
            // nothing to undo and nothing to report.
            return SteeringResult(state.copy(pendingTap = null), emptyList())
        }
        return SteeringResult(state, emptyList())
    }

    private fun key(state: SteeringState, event: SteeringEvent.Key): SteeringResult {
        val input = event.input
        var working = state
        val effects = mutableListOf<SteeringEffect>()

        // A new connection invalidates anything in flight, including the blocked-key marker.
        if (working.epoch != input.connectionEpoch) {
            if (!working.idle) effects += SteeringEffect.Discard(DiscardReason.EPOCH_CHANGED, null)
            working = working.copy(press = null, pendingTap = null, blocked = null, epoch = input.connectionEpoch)
        }

        if (input.canceled) {
            if (!working.idle) effects += SteeringEffect.Discard(DiscardReason.CANCELED_EVENT, null)
            return SteeringResult(working.copy(press = null, pendingTap = null), effects)
        }

        return when (input.action) {
            KeyAction.DOWN -> down(working, input, event.snapshot, effects)
            KeyAction.UP -> up(working, input, effects)
        }
    }

    private fun down(
        state: SteeringState,
        input: SteeringInput,
        snapshot: TrackSnapshot?,
        effects: MutableList<SteeringEffect>
    ): SteeringResult {
        var working = state

        // §5.1: a repeat of the press already being tracked is not a new press, and must not
        // repeat the base command.
        if (working.press?.pressId == input.pressId) return SteeringResult(working, effects)
        if (input.repeatCount > 0) return SteeringResult(working, effects)

        // §5.4: a different key, or a second key while one is held, abandons what was in flight.
        if (working.press != null || (working.pendingTap != null && input.key != SteeringKey.PLAY_PAUSE)) {
            effects += SteeringEffect.Discard(DiscardReason.OTHER_KEY, null)
            working = working.copy(press = null, pendingTap = null)
        }

        // §4: the ordinary action goes out now, before any gesture window, and only if the app is
        // the confirmed consumer of this press.
        if (input.baseHandlingOwner == BaseHandlingOwner.DRIVEMUSE)
            effects += SteeringEffect.DispatchBase(input.key, input.pressId)

        val resync = working.blocked == input.key
        val press = ActivePress(
            input.pressId, input.key, input.downTimeElapsed, snapshot,
            input.connectionEpoch, input.mediaSessionEpoch, input.tripId, resyncOnly = resync)
        working = working.copy(press = press)
        effects += SteeringEffect.ScheduleTimeout(stuckToken(press), input.downTimeElapsed + thresholds.stuckPressMs)
        return SteeringResult(working, effects)
    }

    private fun up(state: SteeringState, input: SteeringInput, effects: MutableList<SteeringEffect>): SteeringResult {
        val press = state.press
        if (press == null || press.pressId != input.pressId) {
            // An UP for a press this reducer never saw the start of. Nothing to confirm.
            return SteeringResult(state, effects)
        }
        var working = state.copy(press = null)
        val held = input.eventTimeElapsed - press.downAt

        if (press.resyncOnly) {
            // The clean press that re-establishes sync produces no command of its own (§5.2.5).
            effects += SteeringEffect.Discard(DiscardReason.RESYNC, null)
            return SteeringResult(working.copy(blocked = null, pendingTap = null), effects)
        }

        if (held >= thresholds.stuckPressMs) {
            effects += SteeringEffect.Discard(DiscardReason.STUCK_PRESS, null)
            return SteeringResult(working.copy(pendingTap = null, blocked = press.key), effects)
        }

        val long = held >= thresholds.longPressMs
        val waiting = working.pendingTap

        // §5.3: a long second press cancels the double-tap candidate and is judged on its own,
        // against its own track. A like and a dislike never come out of one group of presses.
        if (long) {
            if (waiting != null) {
                effects += SteeringEffect.Discard(DiscardReason.LONG_SECOND_PRESS, Shortcut.SC03)
                working = working.copy(pendingTap = null)
            }
            return SteeringResult(working, effects + confirm(Shortcut.of(press.key, Gesture.LONG_PRESS), working.enabled, press, input, press.snapshot, press.pressId))
        }

        // A short press. For PLAY_PAUSE it may open or close a double tap; for the other keys it
        // is simply the ordinary button action and nothing more.
        if (press.key != SteeringKey.PLAY_PAUSE) return SteeringResult(working.copy(pendingTap = null), effects)

        if (waiting != null && press.downAt - waiting.upAt <= thresholds.doubleTapGapMs) {
            working = working.copy(pendingTap = null)
            // §2.3: the target is the track seen at the first press. If the player has moved on
            // between the two, there is no longer one track being rated and the like is dropped.
            val moved = waiting.snapshot == null || press.snapshot == null ||
                waiting.snapshot.trackId != press.snapshot.trackId ||
                waiting.snapshot.mediaSessionEpoch != press.snapshot.mediaSessionEpoch
            if (moved) {
                effects += SteeringEffect.Discard(DiscardReason.TRACK_CHANGED, Shortcut.SC03)
                return SteeringResult(working, effects)
            }
            return SteeringResult(working, effects + confirm(Shortcut.SC03, working.enabled, press, input, waiting.snapshot, waiting.gestureId))
        }

        // First short press of a possible pair. §5.3: a third press starts a new group rather than
        // pairing with the second, which is why the candidate is replaced rather than extended.
        val tap = PendingTap(press.pressId, input.eventTimeElapsed, press.snapshot,
            press.connectionEpoch, press.mediaSessionEpoch, press.tripId)
        effects += SteeringEffect.ScheduleTimeout(tapToken(tap), input.eventTimeElapsed + thresholds.doubleTapGapMs)
        return SteeringResult(working.copy(pendingTap = tap), effects)
    }

    private fun confirm(
        shortcut: Shortcut?,
        enabled: Set<Shortcut>,
        press: ActivePress,
        input: SteeringInput,
        target: TrackSnapshot?,
        gestureId: String
    ): List<SteeringEffect> {
        if (shortcut == null) return emptyList()
        if (shortcut !in enabled) return listOf(SteeringEffect.Discard(DiscardReason.GESTURE_DISABLED, shortcut))
        if (shortcut.action == ShortcutAction.RATE_UP || shortcut.action == ShortcutAction.RATE_DOWN) {
            // §2.3 / RATE05: no target, an observation too old to trust, or content that cannot be
            // rated means no rating at all — the base action still happened.
            val reason = when {
                target == null -> DiscardReason.NO_TARGET_TRACK
                !target.isSupportedContent -> DiscardReason.UNSUPPORTED_CONTENT
                input.eventTimeElapsed - target.observedAtElapsed > thresholds.trackSnapshotMaxAgeMs -> DiscardReason.STALE_SNAPSHOT
                target.mediaSessionEpoch != press.mediaSessionEpoch -> DiscardReason.SESSION_CHANGED
                else -> null
            }
            if (reason != null) return listOf(SteeringEffect.Discard(reason, shortcut))
        }
        return listOf(SteeringEffect.Emit(ShortcutCommand(
            commandId = "$gestureId#${shortcut.action.name}",
            action = shortcut.action,
            target = if (shortcut.action == ShortcutAction.RATE_UP || shortcut.action == ShortcutAction.RATE_DOWN) target else null,
            tripId = press.tripId,
            connectionEpoch = press.connectionEpoch,
            mediaSessionEpoch = press.mediaSessionEpoch,
            requestedAtElapsed = input.eventTimeElapsed,
            gestureConfigVersion = thresholds.version)))
    }

    private fun stuckToken(press: ActivePress) = "stuck:${press.pressId}"
    private fun tapToken(tap: PendingTap) = "tap:${tap.gestureId}"
}
