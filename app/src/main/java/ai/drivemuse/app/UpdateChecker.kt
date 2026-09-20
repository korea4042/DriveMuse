package ai.drivemuse.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** What the rolling release says about the build that is available now. */
data class UpdateInfo(val versionName: String, val installedVersionName: String, val downloadUrl: String, val publishedLabel: String)

/**
 * The debug build is sideloaded, so nothing tells the phone that a newer APK exists. CI keeps one
 * rolling release with a stable asset URL, and this reads its metadata to say whether the installed
 * build is behind. It never downloads or installs on its own — the user taps, the browser downloads,
 * and Android's own installer asks for confirmation.
 */
object UpdateChecker {
    private const val RELEASE_API = "https://api.github.com/repos/korea4042/DriveMuse/releases/tags/latest"

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val root = runCatching {
            val c = (URL(RELEASE_API).openConnection() as HttpsURLConnection).apply {
                connectTimeout = 5000; readTimeout = 8000; setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (c.responseCode !in 200..299) return@runCatching null
                JSONObject(c.inputStream.use { String(it.readNBytes(500_000), Charsets.UTF_8) })
            } finally { c.disconnect() }
        }.getOrNull() ?: return@withContext null

        // The title carries the version the release was built from: "DriveMuse 0.5.1".
        val published = root.optString("name").substringAfterLast(' ').trim()
        // versionName alone is not an identity. Two different builds shipped as 0.13.0 because the
        // name was not bumped with the code, and this reported "up to date" on the older one. CI
        // writes the versionCode into the release body; when it is there, it decides.
        val publishedCode = Regex("versionCode (\\d+)").find(root.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
        val asset = root.optJSONArray("assets")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
        } ?: return@withContext null
        val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: return@withContext null
        val installed = BuildConfig.VERSION_NAME
        val behind = when {
            publishedCode != null -> publishedCode > BuildConfig.VERSION_CODE
            else -> published.isNotBlank() && newer(published, installed)
        }
        if (!behind) return@withContext null
        UpdateInfo(published, installed, url, root.optString("body").take(200))
    }

    /** Compares dotted versions numerically so 0.5.10 counts as newer than 0.5.9. */
    fun newer(published: String, installed: String): Boolean {
        val a = published.split('.').mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        val b = installed.split('.').mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
