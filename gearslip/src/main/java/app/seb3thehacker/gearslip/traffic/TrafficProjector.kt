package app.seb3thehacker.gearslip.traffic

import kotlin.math.max
import kotlin.math.min

/**
 * Projects Google's congestion spans (measured on Google's line) onto the real OSRM geometry
 * that gets drawn. This is the same problem Vela solves with `RouteGeometry.transferSpans`;
 * here each span is sampled every 25 m and each sample is snapped to the nearest segment of
 * the OSRM line, so a jam is coloured on the road we actually render.
 */
object TrafficProjector {

    fun merge(routed: RoutePath, google: RouteTraffic): RouteTraffic {
        val routeCum = routed.cumulative
        val googleCum = google.distanceMeters
        val spans = google.spans.flatMap { span ->
            val from = span.startMeters
            val to = min(from + span.lengthMeters, googleCum.lastOrNull() ?: from)
            if (to <= from) return@flatMap emptyList()

            // Each successful sample is a projected metre position on the drawn route plus the
            // source metre position it came from. Consecutive samples form a run; a failed
            // projection or a gap larger than one sample step ends the run so congestion is not
            // stretched across a portion of the line we could not verify.
            val runs = mutableListOf<List<Pair<Double, Double>>>()
            var run = mutableListOf<Pair<Double, Double>>()
            var lastSource = Double.NaN
            var metres = from
            while (metres <= to) {
                val point = pointAt(google.points, googleCum, metres)
                val projected = nearestDistance(point, routed.points, routeCum)
                if (projected == null) {
                    if (run.isNotEmpty()) {
                        runs += run
                        run = mutableListOf()
                    }
                    lastSource = Double.NaN
                } else {
                    if (run.isNotEmpty() && metres - lastSource > SAMPLE_STEP + GAP_TOLERANCE) {
                        runs += run
                        run = mutableListOf()
                    }
                    run += projected to metres
                    lastSource = metres
                }
                metres += SAMPLE_STEP
            }
            if (run.isNotEmpty()) runs += run

            runs.mapNotNull { samples ->
                if (samples.isEmpty()) return@mapNotNull null
                val start = samples.minOf { it.first }
                val end = samples.maxOf { it.first }
                val length = max(25.0, min(end - start, routeCum.last() - start))
                TrafficSpan(span.level, start, length)
            }
        }
        return RouteTraffic(
            points = routed.points,
            spans = spans,
            typicalSeconds = google.typicalSeconds ?: routed.durationSeconds,
            trafficSeconds = google.trafficSeconds,
        )
    }

    private fun pointAt(points: List<MapPoint>, cumulative: List<Double>, metres: Double): MapPoint {
        if (points.isEmpty()) return MapPoint(0.0, 0.0)
        var lo = 0
        var hi = cumulative.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] < metres) lo = mid + 1 else hi = mid
        }
        val i = max(1, lo)
        val a = cumulative[i - 1]
        val b = cumulative[i]
        val f = if (b <= a) 0.0 else ((metres - a) / (b - a)).coerceIn(0.0, 1.0)
        return MapPoint(
            points[i - 1].lat + (points[i].lat - points[i - 1].lat) * f,
            points[i - 1].lng + (points[i].lng - points[i - 1].lng) * f,
        )
    }

    /** Cumulative distance along [points] of the point nearest to [target]. */
    internal fun nearestDistance(
        target: MapPoint,
        points: List<MapPoint>,
        cumulative: List<Double>,
    ): Double? {
        val cosLat = cosLat(target.lat)
        var best = Double.MAX_VALUE
        var bestMetres = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val rawDx = b.lng - a.lng
            val dx = rawDx * cosLat // one latitude-scaled metric for projection and rejection
            val dy = b.lat - a.lat
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else
                ((target.lng - a.lng) * cosLat * dx + (target.lat - a.lat) * dy) / len2
            val clamped = t.coerceIn(0.0, 1.0)
            val px = a.lng + rawDx * clamped // degrees, so dLng can use the same scale
            val py = a.lat + dy * clamped
            val dLat = target.lat - py
            val dLng = (target.lng - px) * cosLat
            val d2 = dLat * dLat + dLng * dLng
            if (d2 < best) {
                best = d2
                bestMetres = cumulative[i - 1] + (cumulative[i] - cumulative[i - 1]) * clamped
            }
        }
        // Reject a snap further than ~200 m; that road isn't part of this route.
        return bestMetres.takeIf { best * 1.21e10 < 200.0 * 200.0 }
    }

    private fun cosLat(lat: Double): Double = kotlin.math.cos(Math.toRadians(lat))

    private const val SAMPLE_STEP = 25.0
    private const val GAP_TOLERANCE = 5.0 // allow float drift beyond one sample step before splitting
}
