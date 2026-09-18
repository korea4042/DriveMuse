package ai.drivemuse.app
import android.app.Application
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.launch
class DriveApplication: Application() {
    override fun onCreate() {
        super.onCreate(); if(FirebaseApp.initializeApp(this)!=null) AppCheckSetup.install()
        // v2.3 §19/§23: wire runtime integrations once; the periodic worker reads the same encrypted config.
        val runtime=IntegrationRuntime.get(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runtime.integrations.revalidateOnStart()
            // Spotify supplies the pool directly, so the YouTube collection worker stays off and any
            // periodic work left from an older install is cancelled.
            ai.drivemuse.app.discovery.MetadataSyncWorker.schedule(this@DriveApplication, unmeteredOnly = true, enabled = false)
        }
    }
}
