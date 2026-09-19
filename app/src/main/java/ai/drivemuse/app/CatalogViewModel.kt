package ai.drivemuse.app

import ai.drivemuse.app.catalog.*
import ai.drivemuse.app.discovery.MetadataSyncWorker
import ai.drivemuse.app.integration.Probes
import ai.drivemuse.domain.*
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/*
 * Technical design v2.3 §20 (status display), §22–§24 (in-app integration settings).
 * UiState never contains a secret; the screen shows status → required action → optional detail.
 */

data class CollectionStatus(val phase: String, val lastSuccessAt: Long?, val inserted: Int, val updated: Int, val autoEnabled: Boolean, val unmeteredOnly: Boolean, val nextEligibleAt: Long)
/**
 * The pool that actually feeds selection. This screen used to report the Catalog pipeline — track,
 * playable_ref, discovery_item — which the Spotify switch left unfed, so it showed zeros and a
 * permanent "연결 확인 필요" while selection worked fine from a different table. Reporting a dead
 * subsystem as broken, and staying silent about the live one, is worse than reporting nothing.
 */
data class CatalogSummary(val pool: Int, val playable: Int, val noHistory: Int, val confirmedListened: Int, val genreTagged: Int, val stalled: Int)

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = IntegrationRuntime.get(application)
    private val catalog = runtime.db.catalog()
    private val busyMutable = MutableStateFlow<ProviderId?>(null)
    val busy = busyMutable.asStateFlow()
    private val messageMutable = MutableStateFlow<String?>(null)
    val message = messageMutable.asStateFlow()
    val integrations = runtime.integrations.observe().stateIn(viewModelScope, SharingStarted.Eagerly, ProviderId.values().associateWith { IntegrationConfig(it) })
    val providerCapabilities get() = runtime.registry.status()

    val collection: StateFlow<CollectionStatus> = combine(catalog.observeControl("default"), catalog.observeLastRun("default")) { c, run ->
        val now = System.currentTimeMillis()
        val phase = when {
            c?.autoEnabled == false && run == null -> "꺼짐"
            c?.leaseOwner != null && c.leaseUntil > now -> "수집 중"
            run?.status == "NEEDS_AUTH" -> "연결 확인 필요"
            run?.status == "QUOTA" -> "오늘 한도 도달"
            run?.status == "RATE_LIMITED" -> "잠시 대기"
            run?.status == "PARTIAL" -> "부분 완료"
            run?.status == "FAILED" -> "연결 확인 필요"
            else -> "대기"
        }
        CollectionStatus(phase, c?.lastSuccessAt, run?.inserted ?: 0, run?.updated ?: 0, c?.autoEnabled != false, c?.unmeteredOnly != false, c?.nextEligibleAt ?: 0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CollectionStatus("대기", null, 0, 0, true, true, 0))

    private val summaryMutable = MutableStateFlow(CatalogSummary(0, 0, 0, 0, 0, 0))
    val summary = summaryMutable.asStateFlow()
    fun refreshSummary() { viewModelScope.launch {
        val dao = runtime.db.dao()
        val rows = runCatching { dao.candidates(0) }.getOrDefault(emptyList())
        // Playable means Spotify can actually start it: legacy YouTube ids cannot.
        val playable = rows.count { ai.drivemuse.app.spotify.SpotifyIds.isTrackId(it.videoId) }
        val heard = runCatching { runtime.db.intelligence().outcomes().filter { !it.explicit }.map { it.trackId }.toSet() }.getOrDefault(emptySet())
        val counts = runCatching { catalog.queueCounts("default").associate { it.queueStatus to it.n } }.getOrDefault(emptyMap())
        summaryMutable.value = CatalogSummary(
            pool = rows.size,
            playable = playable,
            noHistory = rows.count { ai.drivemuse.app.spotify.SpotifyIds.isTrackId(it.videoId) && it.videoId !in heard },
            confirmedListened = heard.size,
            genreTagged = rows.count { it.topics.isNotBlank() },
            // Left over from the retired collection path; shown only so it is not a silent mystery.
            stalled = (counts["PENDING"] ?: 0) + (counts["RETRY_WAIT"] ?: 0)
        )
    } }

    /** Removes the discovery queue the retired YouTube path left behind (§8). */
    fun clearStalledQueue() { viewModelScope.launch {
        runCatching { catalog.clearDiscoveryQueue("default") }
            .onSuccess { messageMutable.value = "예전 수집 대기 항목을 정리했습니다" }
            .onFailure { messageMutable.value = "정리하지 못했습니다 · ${it.message ?: ""}" }
        refreshSummary()
    } }

    fun saveIntegration(p: ProviderId, values: Map<String, String>) {
        if (busyMutable.value != null) return
        busyMutable.value = p
        viewModelScope.launch {
            try {
                val probe: suspend (Map<String, String>) -> ai.drivemuse.app.integration.ProbeResult = when (p) {
                    // §8: retired as a music source. The code stays for the migration path only.
                    ProviderId.YOUTUBE -> { _ -> ai.drivemuse.app.integration.ProbeResult(false, IntegrationError.UNKNOWN, "YouTube 연동은 더 이상 사용하지 않습니다") }
                    ProviderId.LASTFM -> Probes.lastFm(runtime.lastFmHttp)
                    ProviderId.LISTENBRAINZ -> Probes.listenBrainz(runtime.listenBrainzHttp)
                    ProviderId.FIREBASE_AI -> Probes.firebaseAi(getApplication<Application>())
                    ProviderId.GEMINI_DIRECT -> Probes.geminiDirect()
                    ProviderId.MUSICBRAINZ -> { _ -> ai.drivemuse.app.integration.ProbeResult(true) }
                    // §22: a Client ID proves nothing by itself; the consent round trip is the real check.
                    ProviderId.SPOTIFY -> { v -> if (v["clientId"].isNullOrBlank()) ai.drivemuse.app.integration.ProbeResult(false, IntegrationError.UNKNOWN, "Client ID가 필요합니다") else ai.drivemuse.app.integration.ProbeResult(true) }
                    ProviderId.WEATHER -> Probes.weather()
                }
                val result = runtime.integrations.applyDraft(p, values, probe)
                messageMutable.value = when (result.status) {
                    IntegrationStatus.READY -> "${label(p)} 연결을 확인했습니다"
                    IntegrationStatus.ERROR -> "${label(p)} 연결 실패 · ${explain(result.error)}"
                    else -> "${label(p)} 설정을 저장했습니다"
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { messageMutable.value = "설정을 적용하지 못했습니다. 기존 연결은 유지됩니다" }
            finally { busyMutable.value = null }
        }
    }
    fun removeIntegration(p: ProviderId) { viewModelScope.launch { runtime.integrations.remove(p) { if (p == ProviderId.YOUTUBE) runtime.db.dao().clearCandidates() }; messageMutable.value = "${label(p)} 연결을 삭제했습니다" } }

    fun setAutoCollect(enabled: Boolean) { viewModelScope.launch { catalog.putControl((catalog.control("default") ?: CollectionControlEntity("default", 1, null, 0, null, 0, true, true)).copy(autoEnabled = enabled)); MetadataSyncWorker.schedule(getApplication(), collection.value.unmeteredOnly, enabled) } }
    fun setUnmeteredOnly(only: Boolean) { viewModelScope.launch { catalog.putControl((catalog.control("default") ?: CollectionControlEntity("default", 1, null, 0, null, 0, true, true)).copy(unmeteredOnly = only)); MetadataSyncWorker.schedule(getApplication(), only, collection.value.autoEnabled) } }
    /** Refreshes the Spotify pool directly: the old worker fed the retired Catalog path (§8). */
    fun topUpNow() { viewModelScope.launch {
        if (busyMutable.value != null) return@launch
        busyMutable.value = ProviderId.SPOTIFY
        messageMutable.value = try {
            runtime.music.refreshReport(runtime.prefs.flow.first()).describe()
        } catch (e: Exception) { "후보 보충 실패 · ${e.message ?: e::class.simpleName}" }
        busyMutable.value = null
        refreshSummary()
    } }
    fun message(text: String?) { messageMutable.value = text }

    /** Spotify sign-in and sign-out; the redirect comes back through MainActivity. */
    fun spotifyAuthorizeIntent() = runtime.spotifyAuth.authorizeIntent()
    /**
     * Observable, because a plain getter never recomposes: the account could link successfully and
     * the screen would still read "계정 미연결" until something unrelated redrew it.
     */
    private val spotifyLinkedMutable = MutableStateFlow(runtime.spotifyAuth.linked)
    val spotifyLinkedFlow = spotifyLinkedMutable.asStateFlow()
    val spotifyLinked get() = runtime.spotifyAuth.linked
    private fun refreshLinked() { spotifyLinkedMutable.value = runtime.spotifyAuth.linked }
    /** Resolves to true only when the token exchange succeeded (AUTH01). */
    suspend fun onSpotifyRedirect(uri: android.net.Uri): Boolean {
        val error = runtime.spotifyAuth.onRedirect(uri)
        messageMutable.value = when (error) {
            null -> "Spotify 계정을 연결했습니다"
            "cancelled" -> "Spotify 연결을 취소했습니다"
            "no_pending_attempt" -> null   // duplicate delivery of an already-consumed redirect
            "attempt_lost" -> "연결 요청 기록을 찾지 못했어요. ‘Spotify 계정 연결’을 다시 눌러 주세요"
            "verifier_expired" -> "연결 요청이 만료됐어요(10분). 다시 눌러 주세요"
            "state_mismatch" -> "Spotify 연결 요청이 일치하지 않아요. 다시 시도해 주세요"
            else -> "Spotify 연결 실패 · $error"
        }
        refreshLinked()
        return error == null
    }
    fun spotifySignOut() { runtime.spotifyAuth.signOut(); refreshLinked(); messageMutable.value = "Spotify 연결을 해제했습니다" }

    fun label(p: ProviderId) = when (p) { ProviderId.YOUTUBE -> "YouTube 조회 (사용 안 함)"; ProviderId.SPOTIFY -> "Spotify"; ProviderId.MUSICBRAINZ -> "MusicBrainz"; ProviderId.LASTFM -> "Last.fm"; ProviderId.LISTENBRAINZ -> "ListenBrainz"; ProviderId.FIREBASE_AI -> "AI 추천 (Firebase)"; ProviderId.GEMINI_DIRECT -> "개인 Gemini 키"; ProviderId.WEATHER -> "날씨 (Open-Meteo)" }
    fun explain(e: IntegrationError) = when (e) { IntegrationError.FORMAT -> "입력 형식을 확인해 주세요"; IntegrationError.API_NOT_ENABLED -> "프로젝트에서 API 사용 설정이 필요합니다"; IntegrationError.KEY_RESTRICTED -> "키 제한(패키지·서명·API)이 이 앱과 맞지 않습니다"; IntegrationError.PERMISSION -> "권한 또는 계정 정보를 확인해 주세요"; IntegrationError.QUOTA -> "오늘 한도에 도달했습니다"; IntegrationError.NETWORK -> "네트워크를 확인하고 다시 시도해 주세요"; else -> "알 수 없는 오류" }
}
