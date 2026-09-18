package ai.drivemuse.app.playback

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaDiagnostic(val connected: Boolean=false,val state: String="관측 권한 없음",val hasMediaId: Boolean=false,val positionMs: Long?=null)
/** Diagnostic only. No notification bodies, playback events, or preference updates are stored. */
class MediaObservationService: NotificationListenerService() {
    companion object { private val mutable=MutableStateFlow(MediaDiagnostic());val diagnostic=mutable.asStateFlow() }
    private var controller: MediaController?=null
    private val callback=object: MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { publish() }
        override fun onSessionDestroyed() { controller=null;publish() }
    }
    private val sessions=MediaSessionManager.OnActiveSessionsChangedListener { attach(it.orEmpty()) }
    private fun attach(all: List<MediaController>) {
        controller?.unregisterCallback(callback)
        val candidates=all.filter { it.packageName=="com.google.android.apps.youtube.music" }
        controller=candidates.singleOrNull();controller?.registerCallback(callback)
        publish()
    }
    private fun publish() {
        val state=controller?.playbackState
        mutable.value=MediaDiagnostic(true,when(state?.state) { PlaybackState.STATE_PLAYING->"PLAYING";PlaybackState.STATE_PAUSED->"PAUSED";PlaybackState.STATE_BUFFERING->"BUFFERING";else->"UNKNOWN / 식별 불가" },!controller?.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).isNullOrBlank(),state?.position)
    }
    override fun onListenerConnected() {
        val manager=getSystemService(MediaSessionManager::class.java)
        try { manager.addOnActiveSessionsChangedListener(sessions,ComponentName(this,javaClass));attach(manager.getActiveSessions(ComponentName(this,javaClass))) } catch(_: SecurityException) { mutable.value=MediaDiagnostic() }
    }
    private fun clear() { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessions);controller?.unregisterCallback(callback);controller=null;mutable.value=MediaDiagnostic() }
    override fun onListenerDisconnected() { clear() }
    override fun onDestroy() { clear();super.onDestroy() }
}
