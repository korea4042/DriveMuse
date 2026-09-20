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
