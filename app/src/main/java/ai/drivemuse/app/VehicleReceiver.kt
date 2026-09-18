package ai.drivemuse.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

object VehicleIdentity {
    fun hash(address: String): String {
        val alias = "drivemuse.vehicle.hmac"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias,null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN).build()) }.generateKey()
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal(address.uppercase().toByteArray()).joinToString("") { "%02x".format(it) } }
    }
}
class VehicleReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    WorkManager.getInstance(context).cancelUniqueWork("vehicle-stable")
                    Preferences(context).flag("connected",false)
                    Preferences(context).suspendUntil(0)
                } finally { pending.finish() }
            }
            return
        }
        if (intent.action !in setOf(BluetoothDevice.ACTION_ACL_CONNECTED,BluetoothDevice.ACTION_ACL_DISCONNECTED)) return
        if (ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = Preferences(context).flow.first()
                if (settings.vehicleId.isBlank() || settings.vehicleId != VehicleIdentity.hash(device.address)) return@launch
                val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
                // REPLACE cancels the opposing event: brief connection bounce never creates a session.
                val work = OneTimeWorkRequestBuilder<VehicleWorker>().setInputData(workDataOf("connected" to connected,"vehicleId" to settings.vehicleId)).setInitialDelay(if (connected) 20 else 30, TimeUnit.SECONDS).build()
                WorkManager.getInstance(context).enqueueUniqueWork("vehicle-stable",ExistingWorkPolicy.REPLACE,work)
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and Bluetooth access.
            } finally { pending.finish() }
        }
    }
}
class VehicleWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val prefs = Preferences(applicationContext)
        val settings = prefs.flow.first()
        if (settings.vehicleId != inputData.getString("vehicleId")) return Result.success()
        val connected = inputData.getBoolean("connected",false)
        prefs.flag("connected",connected)
        if (!connected) { prefs.suspendUntil(0); return Result.success() }
        if (settings.connected || !settings.auto || settings.suspendedUntil > System.currentTimeMillis()) return Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("automation_status","드라이브 준비",NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1,NotificationCompat.Builder(applicationContext,"automation_status").setSmallIcon(R.drawable.ic_notification).setContentTitle("드라이브를 준비할까요?").setContentText("정차 중 눌러서 오늘의 음악을 선택하세요").setContentIntent(launch).setAutoCancel(true).setSilent(true).build())
        return Result.success()
    }
}
