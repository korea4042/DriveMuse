package ai.drivemuse.app

import ai.drivemuse.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import javax.net.ssl.HttpsURLConnection

class QuotaExceededException : IllegalStateException("오늘의 음악 조회 한도를 모두 사용했습니다")
class AuthExpiredException : IllegalStateException("Google 계정 연결을 다시 확인해 주세요")
class ApiNotConfiguredException(reason: String) : IllegalStateException(reason)

/** One video as the API returns it, before it becomes a ranking candidate. */
data class RawVideo(
    val id: String, val title: String, val channel: String,
    val categoryId: String?, val live: String?, val durationSec: Int,
    val topics: List<String>, val publishedYear: Int, val audioLanguage: String? = null
)

private enum class Auth { USER, PUBLIC }

/**
 * v2.3 §22. An API key restricted to "Android apps" is only accepted when the request carries the
 * caller's package and signing certificate. Plain HttpsURLConnection does not add them — the Google
 * client libraries do — so a restricted key fails with 403 until these headers are sent.
 */
data class AndroidClientIdentity(val packageName: String, val sha1: String) {
    companion object {
        fun of(context: android.content.Context): AndroidClientIdentity? = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest(signer.toByteArray())
            AndroidClientIdentity(context.packageName, digest.joinToString("") { "%02X".format(it) })
        }.getOrNull()
    }
}

/**
 * Technical design v1.2 §6.2 and §6.4.
 *
 * Routing has a single rule: data tied to the signed-in account goes out with the bearer
 * token, everything else with the API key. The two are mutually exclusive — sending both on
 * one request makes the API ambiguous about which principal it is serving.
 */
class YouTubeApi(private val apiKeyProvider: () -> String, private val tokens: TokenStore, private val androidIdentity: () -> AndroidClientIdentity? = { null }) {
    /** Legacy build-constant constructor; the runtime config path (v2.3 §22) passes a provider instead. */
    constructor(apiKey: String, tokens: TokenStore) : this({ apiKey }, tokens)
    private val apiKey get() = apiKeyProvider()

    private val base = "https://www.googleapis.com/youtube/v3/"

    private suspend fun get(path: String, params: Map<String, String>, auth: Auth): JSONObject = withContext(Dispatchers.IO) {
        val token = if (auth == Auth.USER) tokens.current() ?: throw UserAuthRequiredException() else null
        if (auth == Auth.PUBLIC && apiKey.isBlank()) throw ApiNotConfiguredException("API 키가 설정되지 않았습니다")

        // The key and the bearer token are mutually exclusive: never both on one request.
        val pairs = params.toList() + if (auth == Auth.PUBLIC) listOf("key" to apiKey) else emptyList()
        val query = pairs.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val connection = (URL(base + path + "?" + query).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 6000; readTimeout = 10000
            requestMethod = "GET"
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (auth == Auth.PUBLIC) androidIdentity()?.let { id ->
                setRequestProperty("X-Android-Package", id.packageName); setRequestProperty("X-Android-Cert", id.sha1)
            }
            setRequestProperty("Accept", "application/json")
        }
        try {
            when (val code = connection.responseCode) {
                in 200..299 -> {
                    val bytes = connection.inputStream.use { it.readNBytes(2_000_001) }
                    check(bytes.size <= 2_000_000) { "응답 크기 초과" }
                    JSONObject(String(bytes, Charsets.UTF_8))
                }
                401 -> { tokens.clear(); throw AuthExpiredException() }
                403 -> throw classify403(connection)
                else -> throw IllegalStateException("음악 정보를 불러오지 못했습니다 ($code)")
            }
        } finally { connection.disconnect() }
    }

    /** §6.8 maps each failure to a distinct recovery path, so the reason has to survive. */
    private fun classify403(connection: HttpsURLConnection): Exception {
        val reason = runCatching {
            val body = connection.errorStream?.use { String(it.readNBytes(200_000), Charsets.UTF_8) } ?: return@runCatching ""
            JSONObject(body).optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        }.getOrDefault("")
        return when (reason) {
            "quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded" -> QuotaExceededException()
            "accessNotConfigured" -> ApiNotConfiguredException("Cloud 프로젝트에서 YouTube Data API v3를 사용 설정해 주세요")
            "ipRefererBlocked", "androidPackageNameNotMatching", "forbidden" -> ApiNotConfiguredException("API 키 제한에 이 앱의 패키지명과 SHA-1이 등록되어 있는지 확인해 주세요")
            else -> AuthExpiredException()
        }
    }

    private fun parseVideos(root: JSONObject): List<RawVideo> {
        val items = root.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { Policy.validTrackId(it) } ?: return@mapNotNull null
            val snippet = item.optJSONObject("snippet") ?: return@mapNotNull null
            val seconds = runCatching { Duration.parse(item.optJSONObject("contentDetails")?.optString("duration").orEmpty()).seconds.toInt() }.getOrDefault(0)
            val topicArray = item.optJSONObject("topicDetails")?.optJSONArray("topicCategories")
            val topics = (0 until (topicArray?.length() ?: 0)).mapNotNull { t -> topicArray?.optString(t)?.substringAfterLast('/')?.replace('_', ' ') }
            RawVideo(
                id = id,
                title = snippet.optString("title").take(200),
                channel = snippet.optString("channelTitle").take(200),
                categoryId = snippet.optString("categoryId").ifBlank { null },
                live = snippet.optString("liveBroadcastContent").ifBlank { null },
                durationSec = seconds,
                topics = topics,
                publishedYear = snippet.optString("publishedAt").take(4).toIntOrNull() ?: 0,
                audioLanguage=snippet.optString("defaultAudioLanguage").takeIf { it.isNotBlank() }
            )
        }
    }

    /** Hydrates ids into full metadata. 1 unit per 50 ids — the cheap workhorse of the design. */
    suspend fun videos(ids: List<String>): List<RawVideo> = ids.filter { Policy.validTrackId(it) }.distinct().chunked(50).flatMap { chunk ->
        parseVideos(get("videos", mapOf("part" to "snippet,contentDetails,topicDetails", "id" to chunk.joinToString(",")), Auth.PUBLIC))
    }

    /** Liked videos live in the reserved LL playlist. OAuth only. */
    suspend fun likedVideoIds(limit: Int = 300): List<String> {
        val ids = mutableListOf<String>()
        var page: String? = null
        do {
            val params = buildMap {
                put("part", "contentDetails"); put("playlistId", "LL"); put("maxResults", "50")
                page?.let { put("pageToken", it) }
            }
            val root = get("playlistItems", params, Auth.USER)
            val items = root.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                items?.optJSONObject(i)?.optJSONObject("contentDetails")?.optString("videoId")
                    ?.takeIf { Policy.validTrackId(it) }?.let { ids += it }
            }
            page = root.optString("nextPageToken").ifBlank { null }
        } while (page != null && ids.size < limit)
        return ids.take(limit)
    }

    suspend fun subscribedChannels(): Set<String> {
        val names = mutableSetOf<String>()
        var page: String? = null
        do {
            val params = buildMap {
                put("part", "snippet"); put("mine", "true"); put("maxResults", "50")
                page?.let { put("pageToken", it) }
            }
            val root = get("subscriptions", params, Auth.USER)
            val items = root.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                items?.optJSONObject(i)?.optJSONObject("snippet")?.optString("title")?.takeIf { it.isNotBlank() }?.let { names += it }
            }
            page = root.optString("nextPageToken").ifBlank { null }
        } while (page != null && names.size < 200)
        return names
    }

    /** Region music chart. 1 unit, and the only discovery source that survives without OAuth. */
    suspend fun popularMusic(regionCode: String): List<RawVideo> = parseVideos(
        get("videos", mapOf("part" to "snippet,contentDetails,topicDetails", "chart" to "mostPopular", "videoCategoryId" to "10", "regionCode" to regionCode, "maxResults" to "50"), Auth.PUBLIC)
    )

    /** 100 units. The caller must have cleared it with the quota guard first. */
    suspend fun searchMusic(query: String, max: Int = 25): List<RawVideo> {
        val root = get("search", mapOf("part" to "snippet", "type" to "video", "videoCategoryId" to "10", "q" to query.take(120), "maxResults" to max.coerceIn(1, 50).toString()), Auth.PUBLIC)
        val items = root.optJSONArray("items")
        val ids = (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it)?.optJSONObject("id")?.optString("videoId") }
            .filter { Policy.validTrackId(it) }
        // search.list omits duration and category, so the filter in §6.7 needs a hydrate pass.
        return if (ids.isEmpty()) emptyList() else videos(ids)
    }
}

/**
 * The YouTube Data API exposes no audio features, so "잔잔하게" has nothing to filter on.
 * These are coarse genre priors from topicCategories — deliberately sparse, because an
 * unmatched track keeps a null energy and stays excluded rather than being guessed loud or quiet.
 */
object EnergyHints {
    private val table = listOf(
        setOf("ambient music", "new-age music", "lullaby") to .15,
        setOf("classical music", "folk music", "jazz") to .3,
        setOf("soul music", "rhythm and blues", "blues") to .45,
        setOf("country music", "pop music", "christian music") to .6,
        setOf("hip hop music", "electronic music", "reggae", "disco") to .75,
        setOf("rock music", "heavy metal", "punk rock", "electronic dance music") to .9
    )
    fun estimate(topics: List<String>): Double? {
        val lowered = topics.map { it.lowercase() }
        val hits = table.filter { (keys, _) -> lowered.any { it in keys } }.map { it.second }
        return if (hits.isEmpty()) null else hits.average()
    }
}
