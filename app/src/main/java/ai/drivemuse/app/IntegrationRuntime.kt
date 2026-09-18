package ai.drivemuse.app

import ai.drivemuse.app.catalog.*
import ai.drivemuse.app.discovery.*
import ai.drivemuse.app.integration.*
import ai.drivemuse.app.knowledge.*
import ai.drivemuse.app.onboarding.SurveyStore
import ai.drivemuse.domain.*
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/*
 * Technical design v2.3 §22–§23, §28. One place that turns stored IntegrationConfig into live
 * clients. Clients read credentials through suspending providers so a promoted config takes
 * effect on the next request without a rebuild (T14). BuildConfig keys are optional defaults only.
 */
class IntegrationRuntime private constructor(context: Context) {
    val db = DriveDatabase.get(context)
    val credentials = CredentialStore(context)
    val integrations = IntegrationConfigRepository(db, credentials, legacyDefaults = mapOf(ProviderId.YOUTUBE to mapOf("apiKey" to BuildConfig.YT_API_KEY), ProviderId.FIREBASE_AI to mapOf("modelId" to BuildConfig.GEMINI_MODEL), ProviderId.WEATHER to mapOf("apiKey" to BuildConfig.WEATHER_API_KEY)))
    val tokens = TokenStore()
    private val ua = "DriveMuse/${BuildConfig.VERSION_NAME} (Android; contact: drivemuse-app@example.invalid)"
    val musicBrainzHttp = ProviderHttp(ProviderId.MUSICBRAINZ, ua)
    val lastFmHttp = ProviderHttp(ProviderId.LASTFM, ua)
    val listenBrainzHttp = ProviderHttp(ProviderId.LISTENBRAINZ, ua)

    /** Latest promoted secret, read per request. */
    fun secret(p: ProviderId, key: String): String? = runBlocking { integrations.secrets(p)[key] }
    private val androidIdentity = AndroidClientIdentity.of(context)
    val youtube = YouTubeApi({ secret(ProviderId.YOUTUBE, "apiKey") ?: "" }, tokens, { androidIdentity })
    val registry = ProviderRegistry(listOf(
        MusicBrainzAdapter(musicBrainzHttp),
        LastFmAdapter(lastFmHttp, { secret(ProviderId.LASTFM, "apiKey") }),
        ListenBrainzAdapter(listenBrainzHttp, { secret(ProviderId.LISTENBRAINZ, "userName") }, { secret(ProviderId.LISTENBRAINZ, "token") })
    ))
    val coordinator = DiscoveryCoordinator(db, youtube, registry)
    val surveyStore = SurveyStore(db)
    val prefs = Preferences(context)

    /** §17 historyCoverageSince: when this install started recording experience. Reset on learning reset. */
    suspend fun historyCoverageSince(): Long {
        val dao = db.intelligence(); dao.state("history_since")?.let { return it.version }
        val now = System.currentTimeMillis(); dao.putState(IntelligenceState("history_since", "", now, now)); return now
    }

    /** Snapshot for a collection run; null when the survey is not done or automation is off. */
    suspend fun snapshot(): RunSnapshot? {
        val draft = surveyStore.load(); if (!draft.completed) return null
        val control = db.catalog().control("default")
        if (control?.autoEnabled == false) return null
        val profile = Survey.map(draft.answers)
        val settings = prefs.flow.first()
        val liked = if (settings.accountLinked && tokens.current() != null) runCatching { youtube.subscribedChannels().map { it.removeSuffix(" - Topic") }.toSet() }.getOrDefault(emptySet()) else emptySet()
        val yt = integrations.active(ProviderId.YOUTUBE)
        return RunSnapshot("default", control?.generation ?: 1, draft.revision, yt.configVersion, profile, liked, Constraints(excludedGenres = profile.exclusions))
    }

    companion object {
        @Volatile private var instance: IntegrationRuntime? = null
        fun get(context: Context): IntegrationRuntime = instance ?: synchronized(this) { instance ?: IntegrationRuntime(context.applicationContext).also { rt -> instance = rt; DiscoveryRuntime.factory = { DiscoveryRuntime(rt.coordinator) { rt.snapshot() } } } }
    }
}
