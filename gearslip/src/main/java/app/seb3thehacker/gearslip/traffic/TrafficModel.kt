package app.seb3thehacker.gearslip.traffic

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A lat/lng pair in WGS84 degrees, independent of any map SDK. */
data class MapPoint(val lat: Double, val lng: Double)

/** Congestion level used by the dot renderer. Values match Vela's traffic spans (1, 2). */
enum class TrafficLevel(val wire: Int) {
    CLEAR(0),
    SLOW(1),
    JAM(2);

    companion object {
        fun fromWire(level: Int): TrafficLevel = when {
            level >= 2 -> JAM
            level == 1 -> SLOW
            else -> CLEAR
        }
    }
}

/** A congested stretch measured from the start of the route, in metres. */
data class TrafficSpan(
    val level: TrafficLevel,
    val startMeters: Double,
    val lengthMeters: Double,
)

/**
 * A route line plus the traffic spans Google returned for it.
 *
 * Google only sends congested stretches; everything else is free flow, so [levelAt] returns
 * [TrafficLevel.CLEAR] outside the spans rather than an "unknown" state.
 */
data class RouteTraffic(
    val points: List<MapPoint>,
    val spans: List<TrafficSpan>,
    val typicalSeconds: Double? = null,
    val trafficSeconds: Double? = null,
) {
    /** Cumulative distance of every vertex, used to project spans onto the drawn dots. */
    val distanceMeters: List<Double> by lazy {
        var total = 0.0
        points.mapIndexed { i, p ->
            if (i > 0) total += distanceMeters(points[i - 1], p)
            total
        }
    }

    fun levelAt(metres: Double): TrafficLevel =
        spans.firstOrNull { metres >= it.startMeters && metres < it.startMeters + it.lengthMeters }
            ?.level ?: TrafficLevel.CLEAR
}

/** Great-circle distance in metres. */
fun distanceMeters(a: MapPoint, b: MapPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val la1 = Math.toRadians(a.lat)
    val la2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

/** A swappable source of route traffic. Renderers only ever see [TrafficLevel]. */
interface TrafficSource {
    suspend fun routeTraffic(origin: MapPoint, destination: MapPoint): RouteTraffic?

    /** Pay any one-off connection cost now instead of on the first route request. */
    suspend fun warmup() {}
}
