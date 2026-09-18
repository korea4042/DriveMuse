package ai.drivemuse.app.spotify

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
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
        val result = suspendCancellableCoroutine<Pair<SpotifyAppRemote?, String?>> { cont ->
            SpotifyAppRemote.connect(context.applicationContext, params, object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) { if (cont.isActive) cont.resume(appRemote to null) }
                override fun onFailure(error: Throwable) { if (cont.isActive) cont.resume(null to explain(error)) }
            })
        }
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

    /** Starts one recording. The caller confirms the start from the state subscription (§3 L2). */
    fun play(trackId: String): Boolean {
        val api = remote?.takeIf { it.isConnected }?.playerApi ?: return false
        api.play("spotify:track:$trackId"); return true
    }

    fun queue(trackId: String): Boolean {
        val api = remote?.takeIf { it.isConnected }?.playerApi ?: return false
        api.queue("spotify:track:$trackId"); return true
    }

    fun next(): Boolean { remote?.takeIf { it.isConnected }?.playerApi?.skipNext() ?: return false; return true }
    fun resume(): Boolean { remote?.takeIf { it.isConnected }?.playerApi?.resume() ?: return false; return true }
    fun pause(): Boolean { remote?.takeIf { it.isConnected }?.playerApi?.pause() ?: return false; return true }
}
