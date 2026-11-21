package app.seb3thehacker.gearslip.traffic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Real routes with real traffic: OSRM supplies the road geometry, Google's keyless directions
 * supplies live congestion. No sample, synthetic or placeholder data is produced here — if a
 * source is unavailable the result simply has no traffic spans, never invented ones.
 *
 * The renderer is handed the geometry as soon as OSRM answers ([onGeometry]); traffic colours
 * arrive later. That keeps the route on screen after one round-trip instead of two.
 */
class RealRoutePlanner(
    private val router: RouteSource = OsrmRouteSource(),
    private val traffic: TrafficSource = VelaTrafficSource(),
) {
    private var cachedKey: RouteKey? = null
    private var cachedAt = 0L
    private var cachedResult: RouteTraffic? = null

    /** Pay the traffic client's one-off handshake before the first route is requested. */
    suspend fun warmup() = traffic.warmup()

    suspend fun plan(
        origin: MapPoint,
        destination: MapPoint,
        onGeometry: ((RouteTraffic) -> Unit)? = null,
    ): RouteTraffic? {
        val key = RouteKey(origin.grid(), destination)
        val now = System.currentTimeMillis()
        if (key == cachedKey && now - cachedAt < CACHE_MS) {
            cachedResult?.let { result ->
                onGeometry?.invoke(result)
                return result
            }
        }
        return coroutineScope {
            val routeDeferred = async { router.route(origin, destination) }
            val trafficDeferred = async { traffic.routeTraffic(origin, destination) }

            val routed = routeDeferred.await() ?: return@coroutineScope null
            val geometryOnly = RouteTraffic(routed.points, emptyList(), routed.durationSeconds, null)
            onGeometry?.invoke(geometryOnly)

            val live = trafficDeferred.await()
            val result = if (live != null) {
                withContext(Dispatchers.IO) { TrafficProjector.merge(routed, live) }
            } else {
                geometryOnly
            }
            cachedKey = key
            cachedAt = System.currentTimeMillis()
            cachedResult = result
            result
        }
    }

    private data class RouteKey(val origin: Pair<Double, Double>, val destination: MapPoint)
}

/** Snap to ~220 m so a stationary car moving a few metres still reuses the last result. */
private fun MapPoint.grid(): Pair<Double, Double> =
    (lat * 500).toLong() / 500.0 to (lng * 500).toLong() / 500.0

private const val CACHE_MS = 30_000L
