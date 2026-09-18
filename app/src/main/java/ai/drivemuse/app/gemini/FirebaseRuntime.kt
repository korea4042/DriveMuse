package ai.drivemuse.app.gemini

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.withTimeout

/**
 * Technical design v2.3 §5 and §22. The Firebase project is entered in the app, not fixed at build
 * time: projectId, applicationId and API key become a named FirebaseApp so a changed config takes
 * effect without a rebuild. A build that still ships google-services.json keeps working through the
 * default app. Registering the Android app and App Check attestation still happens in the console —
 * entering values here cannot create them.
 */
object FirebaseRuntime {
    private const val NAME = "drivemuse-runtime"

    /** The app to use for AI calls, or null when neither runtime config nor a config file is present. */
    fun app(context: Context, config: Map<String, String>): FirebaseApp? {
        val projectId = config["projectId"]?.trim().orEmpty()
        val applicationId = config["applicationId"]?.trim().orEmpty()
        val apiKey = config["apiKey"]?.trim().orEmpty()
        if (projectId.isBlank() || applicationId.isBlank() || apiKey.isBlank()) {
            return FirebaseApp.getApps(context).firstOrNull()
        }
        val existing = runCatching { FirebaseApp.getInstance(NAME) }.getOrNull()
        if (existing != null) {
            val o = existing.options
            if (o.projectId == projectId && o.applicationId == applicationId && o.apiKey == apiKey) return existing
            runCatching { existing.delete() }
        }
        val options = FirebaseOptions.Builder()
            .setProjectId(projectId).setApplicationId(applicationId).setApiKey(apiKey).build()
        return runCatching { FirebaseApp.initializeApp(context, options, NAME) }.getOrNull()
    }

    /** One tiny call, so "연결됨" means the project really answered (§22). Throws on failure. */
    suspend fun ping(app: FirebaseApp, modelId: String) {
        withTimeout(20_000) {
            Firebase.ai(app = app, backend = GenerativeBackend.googleAI())
                .generativeModel(modelName = modelId).generateContent("ping")
        }
    }
}
