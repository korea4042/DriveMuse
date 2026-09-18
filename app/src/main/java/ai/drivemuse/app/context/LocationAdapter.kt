package ai.drivemuse.app.context

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import ai.drivemuse.domain.Zone
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.math.*

/**
 * §4: the raw fix never leaves this module. lat/lon are rounded to one decimal (~11 km) before
 * anything else sees them, which is enough for a regional forecast and not a trail.
 */
data class Region(val x: Int,val y: Int,val zone: Zone,val measuredAt: Long,val lat: Double=0.0,val lon: Double=0.0) { val id get()="$x:$y" }
object KmaGrid {
    fun from(latitude: Double,longitude: Double): Pair<Int,Int>? {
        if(latitude !in 32.0..40.0 || longitude !in 123.0..133.0) return null
        val rad=PI/180;val re=6371.00877/5;val slat1=30*rad;val slat2=60*rad
        val sn=ln(cos(slat1)/cos(slat2))/ln(tan(PI*.25+slat2*.5)/tan(PI*.25+slat1*.5))
        val sf=tan(PI*.25+slat1*.5).pow(sn)*cos(slat1)/sn
        val ro=re*sf/tan(PI*.25+38*rad*.5).pow(sn)
        val ra=re*sf/tan(PI*.25+latitude*rad*.5).pow(sn);val theta=(longitude-126)*rad*sn
        return floor(ra*sin(theta)+43+.5).toInt() to floor(ro-ra*cos(theta)+136+.5).toInt()
    }
}
/** Raw coordinates are confined to this package and discarded after this callback. */
class LocationAdapter(private val context: Context) {
    private val manager=context.getSystemService(LocationManager::class.java)
    private val regions=RegisteredZones(context)
    private var lastAttempt=0L;private var cached: Region?=null
    suspend fun refresh(): Region? {
        val now=System.currentTimeMillis()
        if(now-lastAttempt<120000) return cached?.takeIf { now-it.measuredAt in 0..120000 }
        lastAttempt=now
        val location=current()?:return null
        val pair=KmaGrid.from(location.latitude,location.longitude)?:return null
        val coarseLat = Math.round(location.latitude * 10) / 10.0
        val coarseLon = Math.round(location.longitude * 10) / 10.0
        return Region(pair.first,pair.second,regions.classify(location),location.time,coarseLat,coarseLon).also { cached=it }
    }
    fun registeredZones(): Set<Zone> = regions.registered()
    suspend fun register(zone: Zone): Boolean {
        require(zone in setOf(Zone.HOME,Zone.WORK))
        val location=current()?:return false
        if(location.accuracy>2500) return false
        regions.save(zone,location);cached=null;return true
    }
    fun deleteZones() { regions.clear();cached=null }
    fun clear() { cached=null;lastAttempt=0 }
    private suspend fun current(): Location? {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return null
        return withTimeoutOrNull(15000) {
            suspendCancellableCoroutine { continuation ->
                val signal=CancellationSignal();continuation.invokeOnCancellation { signal.cancel() }
                try { manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER,signal,context.mainExecutor) { l ->
                    val valid=l?.takeIf { it.hasAccuracy() && it.accuracy<=5000 && System.currentTimeMillis()-it.time in 0..120000 }
                    if(continuation.isActive) continuation.resume(valid)
                } } catch(_: Exception) { if(continuation.isActive) continuation.resume(null) }
            }
        }
    }
}
private class RegisteredZones(context: Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"registered_zones.enc"));private val alias="drivemuse.registered-zones"
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
    }
    private fun load(): JSONArray=runCatching {
        val bytes=file.openRead().use { it.readBytes() };val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)));JSONArray(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size))))
    }.getOrElse { JSONArray() }
    fun save(zone: Zone,l: Location) {
        val old=load();val rows=(0 until old.length()).map { old.getJSONObject(it) }.filter { it.getString("zone")!=zone.name }
        val data=JSONArray(rows+JSONObject().put("zone",zone.name).put("lat",l.latitude).put("lon",l.longitude).put("radius",maxOf(500.0,l.accuracy*2.0))).toString().toByteArray()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());val output=file.startWrite()
        try { output.write(cipher.iv+cipher.doFinal(data));file.finishWrite(output) } catch(e: Exception) { file.failWrite(output);throw e }
    }
    fun classify(l: Location): Zone {
        val rows=load();val matches=(0 until rows.length()).map { rows.getJSONObject(it) }.filter { row -> val distance=FloatArray(1);Location.distanceBetween(l.latitude,l.longitude,row.getDouble("lat"),row.getDouble("lon"),distance);distance[0]+l.accuracy<=row.getDouble("radius") }
        return if(matches.size==1) Zone.valueOf(matches.single().getString("zone")) else Zone.UNKNOWN
    }
    fun clear() { file.delete();KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) } }
    /** Which zones are registered, so the screen can say whether saving worked (§24). */
    fun registered(): Set<Zone> = load().let { rows -> (0 until rows.length()).mapNotNull { runCatching { Zone.valueOf(rows.getJSONObject(it).getString("zone")) }.getOrNull() }.toSet() }
}
