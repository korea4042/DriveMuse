package ai.drivemuse.app.spotify

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.Empty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** What the Spotify player is doing, as App Remote reports it. */
data class RemotePlayerState(
    val trackUri: String?, val title: String?, val artist: String?,
    val paused: Boolean, val positionMs: Long, val durationMs: Long, val observedAt: Long
) {
    val trackId get() = trackUri?.substringAfterLast(':')
}

/**
 * R01. What the SDK said about one command, never what the player is doing.
 *
 * `Accepted` is not `Confirmed`: the command reached Spotify, nothing more. `Unknown` covers the
 * timeout and any indeterminate transport failure — resending on Unknown is how a queue grows
 * duplicates, so the caller reconciles against observed state instead.
 */
sealed interface DispatchResult {
    data object Accepted : DispatchResult
    data class Rejected(val reason: String) : DispatchResult
    data object Unknown : DispatchResult
}

/** The outcome of asking for one recording to start, after observation has had its say. */
sealed interface StartResult {
    data object Confirmed : StartResult
    /** Definitively did not start; another transport may be tried. */
    data class Failed(val reason: String) : StartResult
    /** May or may not have started. Never re-send the same play on this. */
    data class Indeterminate(val reason: String) : StartResult
}

/**
 * Technical design v2.3 §3 and §30, Spotify App Remote.
 *
 * The Web API can only command a device that is already awake, which is useless in a car: the point
 * is that the driver starts nothing. App Remote binds to the installed Spotify app directly, so it
 * can start playback of a named track from cold and report state back through a subscription.
 *
 * This is the L2 path from §3: a command is only treated as successful after the player reports the
 * expected track, never because the call returned.
 */
class SpotifyRemote(private val clientId: () -> String?) {
    private val mutex = Mutex()
    @Volatile private var remote: SpotifyAppRemote? = null
    private val stateMutable = MutableStateFlow<RemotePlayerState?>(null)
    val state = stateMutable.asStateFlow()

    val connected get() = remote?.isConnected == true

    fun installed(context: Context) = SpotifyAppRemote.isSpotifyInstalled(context)

    /** Connects, reusing an existing session. Returns null on success, or a reason to show. */
    suspend fun connect(context: Context): String? = mutex.withLock {
        remote?.takeIf { it.isConnected }?.let { return null }
        val id = clientId()?.trim().orEmpty()
        if (id.isBlank()) return "Spotify Client ID를 먼저 저장해 주세요"
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) return "기기에 Spotify 앱이 필요해요"
        val params = ConnectionParams.Builder(id)
            .setRedirectUri(SpotifyAuth.REDIRECT)
            .showAuthView(true)
            .build()
        // The SDK may never call back (Spotify app stuck, auth dialog never shown). A bound is the
        // difference between a clear failure and a request that spins forever.
        val result = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Pair<SpotifyAppRemote?, String?>> { cont ->
                SpotifyAppRemote.connect(context.applicationContext, params, object : Connector.ConnectionListener {
                    override fun onConnected(appRemote: SpotifyAppRemote) { if (cont.isActive) cont.resume(appRemote to null) }
                    override fun onFailure(error: Throwable) { if (cont.isActive) cont.resume(null to explain(error)) }
                })
            }
        } ?: return "Spotify 앱이 20초 안에 응답하지 않았어요. Spotify를 한 번 열어 로그인 상태를 확인한 뒤 다시 시도해 주세요"
        val connectedRemote = result.first ?: return result.second
        remote = connectedRemote
        connectedRemote.playerApi.subscribeToPlayerState().setEventCallback { s ->
            val t = s.track
            stateMutable.value = RemotePlayerState(
                trackUri = t?.uri, title = t?.name, artist = t?.artist?.name,
                paused = s.isPaused, positionMs = s.playbackPosition,
                durationMs = t?.duration ?: 0L, observedAt = System.currentTimeMillis()
            )
        }
        null
    }

    /** App Remote failures arrive as exception classes with empty messages; name what each one means. */
    private fun explain(error: Throwable): String {
        val kind = error::class.simpleName ?: "Unknown"
        val hint = when {
            "CouldNotFindSpotifyApp" in kind -> "기기에 Spotify 앱이 없어요"
            "NotLoggedIn" in kind -> "Spotify 앱에 로그인해 주세요"
            // Three different causes share this exception, so name all three rather than guess.
            "UserNotAuthorized" in kind || "AuthenticationFailed" in kind ->
                "재생 권한이 없어요. ①설정에서 연결을 해제하고 다시 연결(app-remote-control 권한) ②Spotify 대시보드에 패키지명·SHA-1·Redirect URI 등록 ③개발자 대시보드 User Management에 이 계정 추가, 순서로 확인해 주세요"
            "Offline" in kind -> "Spotify가 오프라인 상태예요"
            "UnsupportedFeatureVersion" in kind -> "Spotify 앱을 최신 버전으로 업데이트해 주세요"
            "SpotifyDisconnected" in kind || "SpotifyConnectionTerminated" in kind -> "Spotify 연결이 끊겼어요. 다시 시도해 주세요"
            else -> error.message?.takeIf { it.isNotBlank() } ?: "Spotify 연결 실패"
        }
        return "$hint ($kind)"
    }

    fun disconnect() { remote?.let { SpotifyAppRemote.disconnect(it) }; remote = null; stateMutable.value = null }

    /**
     * R01. The previous version resumed with `null` on success and then applied `?:` to the
     * `withTimeoutOrNull` result, so a success and a timeout were the same value and every
     * accepted command was reported as a timeout. Three outcomes are not two: a command can be
     * accepted, definitively refused, or of unknown fate, and only the middle one justifies
     * sending the same command down another pipe.
     */
    private suspend fun dispatch(call: CallResult<Empty>, timeoutMs: Long = 8_000): DispatchResult =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<DispatchResult> { cont ->
                call.setResultCallback { if (cont.isActive) cont.resume(DispatchResult.Accepted) }
                call.setErrorCallback { e -> if (cont.isActive) cont.resume(DispatchResult.Rejected(explain(e))) }
            }
        } ?: DispatchResult.Unknown

    /**
     * Starts one recording and returns null only once the player reports that track as the current
     * one. The command being accepted is not enough: another track may still be current, or the
     * player may have refused silently.
     */
    suspend fun playAndConfirm(trackId: String, confirmMs: Long = 10_000): StartResult {
        val api = remote?.takeIf { it.isConnected }?.playerApi
            ?: return StartResult.Failed("Spotify에 연결되지 않았어요")
        // A rejection is the only outcome that proves the command did not land. On Unknown the
        // player may well be starting the track right now, so the state stream decides.
        when (val sent = dispatch(api.play("spotify:track:$trackId"))) {
            is DispatchResult.Rejected -> return StartResult.Failed(sent.reason)
            else -> Unit
        }
        val started = withTimeoutOrNull(confirmMs) {
            state.filterNotNull().first { it.trackId == trackId && !it.paused }
        }
        return when {
            started != null -> StartResult.Confirmed
            // Accepted but never observed: another track holds the player, or state is stale.
            else -> StartResult.Indeterminate("재생 시작을 확인하지 못했어요 (다른 곡이 재생 중이거나 상태 응답 없음)")
        }
    }

    suspend fun queue(trackId: String): DispatchResult {
        val api = remote?.takeIf { it.isConnected }?.playerApi
            ?: return DispatchResult.Rejected("Spotify에 연결되지 않았어요")
        return dispatch(api.queue("spotify:track:$trackId"))
    }

    /** §7: these three used to drop their CallResult, so a command that never landed read as sent. */
    suspend fun next(): DispatchResult {
        val api = remote?.takeIf { it.isConnected }?.playerApi
            ?: return DispatchResult.Rejected("Spotify에 연결되지 않았어요")
        return dispatch(api.skipNext())
    }
    suspend fun resume(): DispatchResult {
        val api = remote?.takeIf { it.isConnected }?.playerApi
            ?: return DispatchResult.Rejected("Spotify에 연결되지 않았어요")
        return dispatch(api.resume())
    }
    suspend fun pause(): DispatchResult {
        val api = remote?.takeIf { it.isConnected }?.playerApi
            ?: return DispatchResult.Rejected("Spotify에 연결되지 않았어요")
        return dispatch(api.pause())
    }
}
