package ai.drivemuse.domain

/**
 * Position and weather expire on separate clocks (§4).
 *
 * They used to share one. The selection path dropped the region when the fix was older than two
 * minutes, and dropping the region dropped the weather with it — so a forecast observed eight
 * minutes ago for the region the car is still sitting in was thrown away because the *fix* had
 * aged out. Two minutes is the right ceiling for "which zone am I in", which changes as you drive.
 * It is the wrong ceiling for "which ~11 km cell is this weather for", which does not.
 */
object ContextFreshness {
    /** How long a fix may be used to say which registered zone the car is in. */
    const val FIX_FOR_ZONE_MS = 120_000L

    /**
     * How long a fix may be used to say which weather region the car is in. The weather fact keeps
     * its own one-hour window on top of this, so this bounds the pairing, not the observation.
     */
    const val FIX_FOR_REGION_MS = 3_600_000L

    /** §5: a reconnection within ten minutes continues the same drive, departure point included. */
    const val SESSION_RESUME_MS = 600_000L

    fun zoneUsable(measuredAt: Long, now: Long) = now - measuredAt in 0..FIX_FOR_ZONE_MS
    fun regionUsableForWeather(measuredAt: Long, now: Long) = now - measuredAt in 0..FIX_FOR_REGION_MS
}

/**
 * Why a location request did not produce a usable fix (§7).
 *
 * "null" was the only answer the adapter gave, so the screen said "위치를 가져오지 못했어요" whether
 * the permission was refused, the radios were off, or the fix simply took too long — three problems
 * with three different things for the user to do about them.
 */
enum class LocationStatus {
    AVAILABLE,
    /** The app holds neither coarse nor fine location permission. */
    PERMISSION_DENIED,
    /** Permission is held but the device's location services are switched off. */
    DISABLED,
    /** No provider answered within the deadline. */
    TIMEOUT,
    /** A fix arrived but was too coarse or too old to use. */
    INACCURATE,
    /** A provider answered with nothing, or the platform threw. */
    UNAVAILABLE;

    val retryable get() = this == TIMEOUT || this == INACCURATE || this == UNAVAILABLE

    /** What the user can actually do about it. Empty when there is nothing to do but wait. */
    val advice get() = when (this) {
        AVAILABLE -> ""
        PERMISSION_DENIED -> "위치 권한을 허용하면 지역 날씨와 등록 장소를 반영할 수 있어요"
        DISABLED -> "기기 설정에서 위치 서비스를 켜 주세요"
        TIMEOUT -> "위치를 확인하는 데 시간이 걸리고 있어요. 하늘이 보이는 곳에서 다시 시도해 주세요"
        INACCURATE -> "위치 정확도가 낮아 사용하지 않았어요. 잠시 후 다시 시도해 주세요"
        UNAVAILABLE -> "위치를 확인할 수 없어 기본 상황으로 추천합니다"
    }
}

/**
 * §7/§3: the three outcomes of a context refresh, which the first version collapsed into two.
 *
 * "날씨를 갱신했습니다" was shown whenever a usable fact existed afterwards — including when the
 * position lookup had failed and the fact on screen was the one already held. Reuse is a fine
 * result, but it is not a refresh and must not be reported as one.
 */
enum class ContextRefresh(val detail: String) {
    REFRESHED("날씨를 갱신했어요"),
    /**
     * Why the cache was served is not knowable from the fact that it was: the lookup throttle holds
     * for five minutes whether or not the position lookup worked. Saying "새 위치를 확인하지 못해"
     * unconditionally asserted a cause that is often simply wrong.
     */
    REUSED("기존에 받아둔 같은 지역 날씨를 사용합니다"),
    NONE("날씨를 확인하지 못했어요");

    val refreshed get() = this == REFRESHED

    /**
     * [locationAdvice] is appended only for a reuse that followed a position failure the app
     * actually observed. A successful fix plus a throttled lookup gets the plain sentence.
     */
    fun describe(locationAdvice: String? = null) =
        if (this == REUSED && !locationAdvice.isNullOrBlank()) "$detail · $locationAdvice" else detail

    companion object {
        /**
         * [fetchedAtChanged] is the only evidence that a lookup actually happened: the repository
         * returns its cache when the five-minute throttle is in force, and that cache is
         * indistinguishable from a fresh answer by any other field.
         */
        fun of(hasUsableFact: Boolean, fetchedAtChanged: Boolean) = when {
            hasUsableFact && fetchedAtChanged -> REFRESHED
            hasUsableFact -> REUSED
            else -> NONE
        }
    }
}

/**
 * §3: what a pool refresh actually achieved. A candidate count is not evidence of a successful
 * refresh — the pool that is still there is the pool that was already there.
 */
sealed interface PoolRefresh {
    data class Refreshed(val total: Int, val added: Int) : PoolRefresh {
        val detail get() = if (added > 0) "후보 ${total}곡 · 새로 ${added}곡" else "후보 ${total}곡 · 새로 추가된 곡 없음"
    }
    data class Failed(val kept: Int, val reason: String) : PoolRefresh {
        val detail get() = "후보를 갱신하지 못했어요 · 기존 ${kept}곡은 그대로예요 · $reason"
    }

    companion object {
        fun of(before: Int, after: Int, failure: String?): PoolRefresh =
            if (failure == null) Refreshed(after, (after - before).coerceAtLeast(0)) else Failed(after, failure)
    }
}
