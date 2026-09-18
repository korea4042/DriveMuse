package ai.drivemuse.app.context

import ai.drivemuse.domain.WeatherFact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.net.ssl.HttpsURLConnection

/**
 * Technical design v2.3 §4. The provider is Open-Meteo: no key to obtain, no account, and it takes
 * coordinates rather than a national grid. What is sent is the rounded position the location module
 * already produced (~11 km), never a raw fix, and only the observation itself comes back.
 *
 * The observation time is kept as reported so §4's freshness rules still decide whether the fact is
 * usable; a forecast that cannot be dated is discarded rather than treated as current.
 */
class WeatherRepository {
    private var cached: WeatherFact? = null
    private var lastAttempt = 0L

    /** No credential is required, so the feature is available whenever a position is. */
    val configured get() = true

    fun clear() { cached = null; lastAttempt = 0 }

    suspend fun get(region: Region): WeatherFact? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val valid = cached?.takeIf { it.usable(region.id, now) }
        // §4: at most one lookup every 5 minutes, and a fresh fact is reused for 15.
        if (now - lastAttempt < 300_000 || valid != null && now - valid.observedAt < 900_000) return@withContext valid
        if (region.lat == 0.0 && region.lon == 0.0) return@withContext valid
        lastAttempt = now

        val url = "https://api.open-meteo.com/v1/forecast?latitude=${region.lat}&longitude=${region.lon}" +
            "&current=temperature_2m,precipitation,weather_code&timezone=Asia%2FSeoul"
        val connection = try {
            (URL(url).openConnection() as HttpsURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000; instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
            }
        } catch (_: Exception) { return@withContext valid }

        try {
            check(connection.responseCode == 200)
            val bytes = connection.inputStream.use { it.readNBytes(100_001) }
            require(bytes.size <= 100_000)
            val current = JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("current")
            val observed = LocalDateTime.parse(current.getString("time"))
                .atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
            val temperature = current.getDouble("temperature_2m")
            WeatherFact(region.id, temperature, precipitationKind(current), observed, source = "OPEN_METEO")
                .takeIf { it.usable(region.id, now) }?.also { cached = it } ?: valid
        } catch (_: Exception) { valid } finally { connection.disconnect() }
    }

    /**
     * Mapped to the same small vocabulary the rest of the app already uses: 0 none, 1 rain,
     * 2 sleet, 3 snow. WMO codes 71–77 and 85–86 are snow, 56–57 and 66–67 freezing.
     */
    private fun precipitationKind(current: JSONObject): Int {
        val code = current.optInt("weather_code", -1)
        val amount = current.optDouble("precipitation", 0.0)
        return when {
            code in 71..77 || code in 85..86 -> 3
            code in 56..57 || code in 66..67 -> 2
            code in 51..67 || code in 80..82 || code in 95..99 || amount > 0.0 -> 1
            else -> 0
        }
    }

    companion object {
        /** Kept so a live check still exists; Open-Meteo needs no credential, so it just pings. */
        suspend fun probe(): ai.drivemuse.app.integration.ProbeResult = withContext(Dispatchers.IO) {
            runCatching {
                val c = (URL("https://api.open-meteo.com/v1/forecast?latitude=37.5&longitude=127.0&current=temperature_2m")
                    .openConnection() as HttpsURLConnection).apply { connectTimeout = 5000; readTimeout = 5000 }
                try { c.responseCode == 200 } finally { c.disconnect() }
            }.fold(
                onSuccess = { ok -> if (ok) ai.drivemuse.app.integration.ProbeResult(true) else ai.drivemuse.app.integration.ProbeResult(false, ai.drivemuse.domain.IntegrationError.NETWORK, "응답 오류") },
                onFailure = { ai.drivemuse.app.integration.ProbeResult(false, ai.drivemuse.domain.IntegrationError.NETWORK, it.message ?: "") }
            )
        }
    }
}
