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
            "UserNotAuthorized" in kind || "AuthenticationFailed" in kind -> "Spotify 대시보드에 이 앱의 패키지명·SHA-1·Redirect URI가 등록돼 있는지 확인해 주세요"
            "Offline" in kind -> "Spotify가 오프라인 상태예요"
            "UnsupportedFeatureVersion" in kind -> "Spotify 앱을 최신 버전으로 업데이트해 주세요"
            "SpotifyDisconnected" in kind || "SpotifyConnectionTerminated" in kind -> "Spotify 연결이 끊겼어요. 다시 시도해 주세요"
            else -> error.message?.takeIf { it.isNotBlank() } ?: "Spotify 연결 실패"
        }
        return "$hint ($kind)"
    }

    fun disconnect() { remote?.let { SpotifyAppRemote.disconnect(it) }; remote = null; stateMutable.value = null }

    /** Waits for the SDK's own result instead of trusting that the call returned (PLAY01). */
    private suspend fun await(call: CallResult<Empty>, timeoutMs: Long = 8_000): String? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<String?> { cont ->
            call.setResultCallback { if (cont.isActive) cont.resume(null) }
            call.setErrorCallback { e -> if (cont.isActive) cont.resume(explain(e)) }
        }
    } ?: "Spotify가 ${timeoutMs / 1000}초 안에 응답하지 않았어요"

    /**
     * Starts one recording and returns null only once the player reports that track as the current
     * one. The command being accepted is not enough: another track may still be current, or the
     * player may have refused silently.
     */
    suspend fun playAndConfirm(trackId: String, confirmMs: Long = 10_000): String? {
        val api = remote?.takeIf { it.isConnected }?.playerApi ?: return "Spotify에 연결되지 않았어요"
        await(api.play("spotify:track:$trackId"))?.let { return it }
        val started = withTimeoutOrNull(confirmMs) {
            state.filterNotNull().first { it.trackId == trackId && !it.paused }
        }
        return if (started != null) null else "재생 시작을 확인하지 못했어요 (다른 곡이 재생 중이거나 응답 없음)"
    }

    suspend fun queueAwait(trackId: String): String? {
        val api = remote?.takeIf { it.isConnected }?.playerApi ?: return "Spotify에 연결되지 않았어요"
        return await(api.queue("spotify:track:$trackId"))
    }

    fun next(): Boolean { remote?.takeIf { it.isConnected }?.playerApi?.skipNext() ?: return false; return true }
    fun resume(): Boolean { remote?.takeIf { it.isConnected }?.playerApi?.resume() ?: return false; return true }
    fun pause(): Boolean { remote?.takeIf { it.isConnected }?.playerApi?.pause() ?: return false; return true }
}
