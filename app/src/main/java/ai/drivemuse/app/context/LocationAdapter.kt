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
import ai.drivemuse.domain.ContextFreshness
import ai.drivemuse.domain.LocationStatus
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
/** A location attempt that says why it failed, not just that it did (§7). */
data class LocationOutcome(val status: LocationStatus, val region: Region?) {
    val available get() = status==LocationStatus.AVAILABLE && region!=null
}

/** Raw coordinates are confined to this package and discarded after this callback. */
class LocationAdapter(private val context: Context) {
    private val manager=context.getSystemService(LocationManager::class.java)
    private val regions=RegisteredZones(context)
    /**
     * When the last *successful* fix was taken. Only success throttles: the old code stamped every
     * attempt, so a refusal — including one caused by a permission the user was in the middle of
     * granting — locked the retry out for two minutes and the button looked dead.
     */
    private var lastSuccess=0L;private var cached: Region?=null
    suspend fun refresh(): LocationOutcome {
        val now=System.currentTimeMillis()
        val fresh=cached?.takeIf { ContextFreshness.zoneUsable(it.measuredAt,now) }
        if(fresh!=null && now-lastSuccess<ContextFreshness.FIX_FOR_ZONE_MS) return LocationOutcome(LocationStatus.AVAILABLE,fresh)
        val (status,location)=current()
        if(location==null) return LocationOutcome(status,fresh)
        val pair=KmaGrid.from(location.latitude,location.longitude)
            ?: return LocationOutcome(LocationStatus.UNAVAILABLE,fresh)
        val coarseLat = Math.round(location.latitude * 10) / 10.0
        val coarseLon = Math.round(location.longitude * 10) / 10.0
        val region=Region(pair.first,pair.second,regions.classify(location),location.time,coarseLat,coarseLon)
        cached=region;lastSuccess=now
        return LocationOutcome(LocationStatus.AVAILABLE,region)
    }
    /** The last fix this adapter took, however old. The caller decides what it is still good for. */
    fun lastKnown(): Region? = cached
    fun registeredZones(): Set<Zone> = regions.registered()
    suspend fun register(zone: Zone): LocationStatus {
        require(zone in setOf(Zone.HOME,Zone.WORK))
        val (status,location)=current()
        if(location==null) return status
        if(location.accuracy>2500) return LocationStatus.INACCURATE
        regions.save(zone,location);cached=null;return LocationStatus.AVAILABLE
    }
    fun deleteZones() { regions.clear();cached=null }
    private companion object { const val BUDGET_MS=15_000L; const val PER_PROVIDER_MS=6_000L }
    fun clear() { cached=null;lastSuccess=0 }
    /**
     * Whether a fix can be attempted at all. Public so the automatic paths can skip silently
     * instead of putting a permission dialog in front of someone who only opened the app.
     */
    fun permitted() =
        ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
    /**
     * Providers in order of preference. The adapter used to ask NETWORK_PROVIDER and nothing else,
     * so a device with no cell/Wi-Fi positioning — or one out on a road with neither — reported no
     * location at all while GPS sat there working.
     */
    private fun providers(): List<String> = listOf(
        LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER
    ).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    private suspend fun current(): Pair<LocationStatus,Location?> {
        if(!permitted()) return LocationStatus.PERMISSION_DENIED to null
        if(runCatching { manager.isLocationEnabled }.getOrDefault(false).not()) return LocationStatus.DISABLED to null
        val enabled=providers()
        if(enabled.isEmpty()) return LocationStatus.DISABLED to null
        var worst=LocationStatus.UNAVAILABLE
        // §3: fifteen seconds for the whole request, shared across providers. Fifteen *each* would
        // be a forty-five second wait behind a spinner.
        val deadline=System.currentTimeMillis()+BUDGET_MS
        for(provider in enabled) {
            val remaining=deadline-System.currentTimeMillis()
            if(remaining<=0L) { worst=LocationStatus.TIMEOUT; break }
            val answer=withTimeoutOrNull(minOf(remaining,PER_PROVIDER_MS)) { ask(provider) }
            if(answer==null) { worst=LocationStatus.TIMEOUT; continue }
            val (status,location)=answer
            if(location!=null) return LocationStatus.AVAILABLE to location
            // A fix that arrived but was too coarse is a better description of the problem than
            // "unavailable", so it survives a later provider returning nothing at all.
            if(status==LocationStatus.INACCURATE) worst=LocationStatus.INACCURATE
        }
        return worst to null
    }
    private suspend fun ask(provider: String): Pair<LocationStatus,Location?> =
        suspendCancellableCoroutine { continuation ->
            val signal=CancellationSignal();continuation.invokeOnCancellation { signal.cancel() }
            try { manager.getCurrentLocation(provider,signal,context.mainExecutor) { l ->
                val valid=l?.takeIf { it.hasAccuracy() && it.accuracy<=5000 && System.currentTimeMillis()-it.time in 0..120000 }
                val status=when { valid!=null->LocationStatus.AVAILABLE; l!=null->LocationStatus.INACCURATE; else->LocationStatus.UNAVAILABLE }
                if(continuation.isActive) continuation.resume(status to valid)
            } } catch(_: Exception) { if(continuation.isActive) continuation.resume(LocationStatus.UNAVAILABLE to null) }
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
