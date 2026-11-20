package app.seb3thehacker.gearslip.traffic

/** A real routed path from a routing engine, independent of any map SDK. */
data class RoutePath(
    val points: List<MapPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double? = null,
) {
    val cumulative: List<Double> by lazy {
        var total = 0.0
        points.mapIndexed { i, p ->
            if (i > 0) total += distanceMeters(points[i - 1], p)
            total
        }
    }
}

/** A real route source (e.g. OSRM). Keyless and independent of the traffic feed. */
interface RouteSource {
    suspend fun route(origin: MapPoint, destination: MapPoint): RoutePath?
}
