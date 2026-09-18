package ai.drivemuse.app.playback

import ai.drivemuse.app.*
import ai.drivemuse.domain.*
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

interface PlaybackAdapter {
    fun capabilities(): PlaybackLevel
    fun open(track: Track?): String
    suspend fun requestPlay(track: Track): String
    fun observe(): Flow<Observation>
}
class YouTubeMusicAdapter(private val context: Context): PlaybackAdapter {
    override fun capabilities()=PlaybackLevel.OPEN_ONLY
    override fun open(track: Track?)=MusicHandoff.open(context,track)
    override suspend fun requestPlay(track: Track)="Unsupported"
    override fun observe(): Flow<Observation> = emptyFlow()
}
class QueueCoordinator(private val db: DriveDatabase) {
    private val mutex=Mutex();private var active: QueueVersion?=null
    suspend fun begin(v: QueueVersion)=mutex.withLock { active=v }
    suspend fun invalidate()=mutex.withLock { active=null;db.intelligence().invalidate() }
    suspend fun commit(v: QueueVersion, tracks: List<Track>, candidates: List<Track>, constraints: Constraints): Boolean = mutex.withLock {
        if(active!=v || !ProposalGate.valid(active!!,v,tracks.map { it.id },candidates,constraints)) return@withLock false
        db.withTransaction {
            db.intelligence().invalidate()
            val json=JSONArray(tracks.map { t -> JSONObject().put("id",t.id).put("title",t.title).put("artist",t.artist).put("familiar",t.familiar).put("durationMs",t.durationMs).put("features",JSONArray(t.features.map { f -> JSONObject().put("axis",f.axis).put("value",f.value).put("source",f.source).put("confidence",f.confidence) })) }).toString()
            db.intelligence().putBatch(BatchEntity(UUID.randomUUID().toString(),v.sessionId,v.generation,v.profileVersion,v.contextVersion,v.evidenceVersion,v.candidateSetId,"READY",json,System.currentTimeMillis()))
        }
        active=null;true
    }
    suspend fun restore(profileVersion: Long): List<Track> = mutex.withLock {
        val row=db.intelligence().ready()?:return@withLock emptyList()
        if(row.profileVersion!=profileVersion || System.currentTimeMillis()-row.createdAt !in 0..2592000000L) return@withLock emptyList()
        val a=JSONArray(row.json)
        (0 until a.length()).map { i -> val j=a.getJSONObject(i);val f=j.getJSONArray("features");Track(j.getString("id"),j.getString("title"),j.getString("artist"),familiar=j.getBoolean("familiar"),durationMs=if(j.has("durationMs")) j.getLong("durationMs") else null,features=(0 until f.length()).map { n -> val v=f.getJSONObject(n);VerifiedFeature(v.getString("axis"),v.getString("value"),v.getString("source"),v.getDouble("confidence")) }) }
        // Restoration is display only; never dispatch a playback command after restart.
    }
}
