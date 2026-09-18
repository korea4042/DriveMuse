package ai.drivemuse.app
import android.app.Application
import com.google.firebase.FirebaseApp
class DriveApplication: Application() {
    override fun onCreate() { super.onCreate(); if(FirebaseApp.initializeApp(this)!=null) AppCheckSetup.install() }
}
