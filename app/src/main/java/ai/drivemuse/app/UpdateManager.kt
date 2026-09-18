package ai.drivemuse.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Where the update flow currently is, for a screen that only shows something when it matters. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * A sideloaded build has no store behind it, so the app fetches its own update: on launch it asks
 * the rolling release what exists, downloads the APK in the background, and then asks to install.
 *
 * Android will not let an app install itself silently — the package installer always confirms, and
 * "unknown sources" must be granted once. That confirmation is the last step here, never skipped,
 * and it is not raised while driving (§9: no dialogs during a car session).
 */
object UpdateManager {
    private val stateMutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = stateMutable.asStateFlow()

    /** Checks and downloads. Safe to call on every launch: it returns early once a build is ready. */
    suspend fun prepare(context: Context) {
        when (stateMutable.value) {
            is UpdateState.Ready, is UpdateState.Downloading, UpdateState.Checking -> return
            else -> Unit
        }
        stateMutable.value = UpdateState.Checking
        val info = runCatching { UpdateChecker.check() }.getOrNull()
        if (info == null) { stateMutable.value = UpdateState.Idle; return }
        val target = File(context.cacheDir, "updates/DriveMuse-${info.versionName}.apk")
        if (target.exists() && target.length() > 0) { stateMutable.value = UpdateState.Ready(info, target); return }
        stateMutable.value = UpdateState.Downloading(0)
        val result = runCatching { download(info.downloadUrl, target) }
        stateMutable.value = result.fold(
            onSuccess = { UpdateState.Ready(info, target) },
            onFailure = { UpdateState.Failed(it.message ?: "다운로드 실패") }
        )
    }

    /** Hands the downloaded file to the system installer; the user confirms there. */
    fun install(context: Context, file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun dismiss() { stateMutable.value = UpdateState.Idle }

    private suspend fun download(url: String, target: File) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        val c = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 8000; readTimeout = 30000; instanceFollowRedirects = true
        }
        try {
            check(c.responseCode in 200..299) { "응답 ${c.responseCode}" }
            val total = c.contentLengthLong
            var read = 0L
            c.inputStream.use { input ->
                partial.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n); read += n
                        if (total > 0) stateMutable.value = UpdateState.Downloading(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            check(partial.length() > 0) { "빈 파일" }
            target.delete(); check(partial.renameTo(target)) { "파일 저장 실패" }
        } finally { c.disconnect(); partial.delete() }
    }
}
