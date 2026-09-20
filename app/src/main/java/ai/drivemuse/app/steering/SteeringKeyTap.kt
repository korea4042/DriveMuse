package ai.drivemuse.app.steering

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One key event exactly as it arrived, before any interpretation. */
data class RawKey(
    val keyCode: Int,
    val keyName: String,
    val isDown: Boolean,
    val downTime: Long,
    val eventTime: Long,
    val repeatCount: Int,
    val flags: Int,
    val canceled: Boolean,
    val deviceId: Int,
    val deviceName: String
) {
    val heldMs get() = eventTime - downTime
}

/**
 * §10 step 1: find out what, if anything, actually reaches the app.
 *
 * It records and returns false, always — the event carries on to whoever would have handled it.
 * Consuming here would make the diagnostic itself break the ordinary button behaviour, which §4
 * forbids outright, and would also make the app look like the base-action owner when it is only
 * eavesdropping from the foreground.
 *
 * Only on while the diagnostic screen is open. That is also the honest limit of what this can
 * prove: keys that reach a focused activity say nothing about keys while Spotify holds the media
 * session and the screen is off.
 */
object SteeringKeyTap {
    private val MEDIA_KEYS = setOf(
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_HEADSETHOOK
    )

    @Volatile private var listening = false
    private val mutable = MutableStateFlow<List<RawKey>>(emptyList())
    val events = mutable.asStateFlow()

    fun start() { mutable.value = emptyList(); listening = true }
    fun stop() { listening = false }
    fun clear() { mutable.value = emptyList() }

    /** @return always false: the event is observed, never swallowed. */
    fun observe(event: KeyEvent): Boolean {
        if (!listening || event.keyCode !in MEDIA_KEYS) return false
        val raw = RawKey(
            keyCode = event.keyCode,
            keyName = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_"),
            isDown = event.action == KeyEvent.ACTION_DOWN,
            downTime = event.downTime,
            eventTime = event.eventTime,
            repeatCount = event.repeatCount,
            flags = event.flags,
            canceled = event.isCanceled,
            deviceId = event.deviceId,
            deviceName = runCatching { event.device?.name }.getOrNull() ?: "알 수 없는 입력"
        )
        mutable.update { (it + raw).takeLast(60) }
        return false
    }
}
