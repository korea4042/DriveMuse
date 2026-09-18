package ai.drivemuse.app.context

import ai.drivemuse.domain.WeatherFact
import ai.drivemuse.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.*
import java.time.format.DateTimeFormatter

class WeatherRepository(private val keyProvider: () -> String = { BuildConfig.WEATHER_API_KEY }) {
    private var cached: WeatherFact?=null;private var lastAttempt=0L
    private val apiKey get() = keyProvider().ifBlank { BuildConfig.WEATHER_API_KEY }
    val configured get() = apiKey.isNotBlank()
    fun clear() { cached=null;lastAttempt=0 }
    suspend fun get(region: Region): WeatherFact? = withContext(Dispatchers.IO) {
        val now=System.currentTimeMillis();val valid=cached?.takeIf { it.usable(region.id,now) }
        if(apiKey.isBlank() || now-lastAttempt<300000 || valid!=null && now-valid.observedAt<900000) return@withContext valid
        lastAttempt=now
        val connection=try {
            val base=ZonedDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(40).withMinute(0).withSecond(0).withNano(0)
            val query="serviceKey=${URLEncoder.encode(apiKey,"UTF-8")}&pageNo=1&numOfRows=100&dataType=JSON&base_date=${base.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}&base_time=${base.format(DateTimeFormatter.ofPattern("HHmm"))}&nx=${region.x}&ny=${region.y}"
            (URL("https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst?$query").openConnection() as HttpURLConnection).apply { connectTimeout=5000;readTimeout=5000;instanceFollowRedirects=false }
        } catch(_: Exception) { return@withContext valid }
        try {
            check(connection.responseCode==200)
            val bytes=connection.inputStream.use { it.readNBytes(100001) };require(bytes.size<=100000)
            val response=JSONObject(String(bytes)).getJSONObject("response");check(response.getJSONObject("header").getString("resultCode")=="00")
            val arr=response.getJSONObject("body").getJSONObject("items").getJSONArray("item");val rows=(0 until arr.length()).map { arr.getJSONObject(it) }
            require(rows.isNotEmpty() && rows.all { it.getInt("nx")==region.x && it.getInt("ny")==region.y })
            val dates=rows.map { it.getString("baseDate")+it.getString("baseTime") }.distinct();require(dates.size==1)
            val observed=LocalDateTime.parse(dates.single(),DateTimeFormatter.ofPattern("yyyyMMddHHmm")).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
            val temp=rows.single { it.getString("category")=="T1H" }.getString("obsrValue").toDouble();val rain=rows.single { it.getString("category")=="PTY" }.getString("obsrValue").toInt()
            WeatherFact(region.id,temp,rain,observed).takeIf { it.usable(region.id,now) }?.also { cached=it }?:valid
        } catch(_: Exception) { valid } finally { connection.disconnect() }
    }
}
