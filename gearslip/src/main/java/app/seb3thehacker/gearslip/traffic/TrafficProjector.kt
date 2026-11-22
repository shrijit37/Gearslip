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
        val identicalGeometry = routed.points == google.points
        val grid by lazy { SegmentGrid(routed.points) }
        val spans = google.spans.flatMap { span ->
            val from = max(0.0, span.startMeters)
            val to = min(span.startMeters + span.lengthMeters, googleCum.lastOrNull() ?: from)
            if (!from.isFinite() || !to.isFinite() || to <= from) return@flatMap emptyList()
            if (identicalGeometry) {
                return@flatMap listOf(
                    if (from == span.startMeters && to == from + span.lengthMeters) span
                    else TrafficSpan(span.level, from, to - from),
                )
            }

            // A rejected sample ends the current run. Only colour the span covered by
            // accepted positions, including the exact end sample even for a short source span.
            val projectedSpans = mutableListOf<TrafficSpan>()
            var start = Double.POSITIVE_INFINITY
            var end = Double.NEGATIVE_INFINITY
            fun finishRun() {
                if (end > start) projectedSpans += TrafficSpan(span.level, start, end - start)
                start = Double.POSITIVE_INFINITY
                end = Double.NEGATIVE_INFINITY
            }

            var metres = from
            while (true) {
                val point = pointAt(google.points, googleCum, metres)
                val projected = nearestDistance(point, routed.points, routeCum, grid.candidates(point))
                if (projected == null) {
                    finishRun()
                } else {
                    start = min(start, projected)
                    end = max(end, projected)
                }
                if (metres == to) break
                metres = min(metres + SAMPLE_STEP, to)
            }
            finishRun()
            projectedSpans
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

    /** Cumulative distance along [points] of the point nearest to [target] (full scan). */
    internal fun nearestDistance(
        target: MapPoint,
        points: List<MapPoint>,
        cumulative: List<Double>,
    ): Double? = nearestDistance(target, points, cumulative, 1 until points.size)

    /** Same projection as the full scan, but only over the segment indices worth testing. */
    internal fun nearestDistance(
        target: MapPoint,
        points: List<MapPoint>,
        cumulative: List<Double>,
        candidates: Iterable<Int>,
    ): Double? {
        val cosLat = cosLat(target.lat)
        var best = Double.MAX_VALUE
        var bestMetres = 0.0
        for (i in candidates) {
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

    /**
     * Coarse lat/lng bins of route segments. Each segment is registered in every cell its bbox
     * covers after padding by the snap radius, so any segment that could pass the 200 m cutoff
     * is in the candidate list of the cell holding a sample. This turns the per-sample scan of
     * the whole route into a lookup of a handful of nearby segments.
     */
    private class SegmentGrid(points: List<MapPoint>) {
        private val cells = HashMap<Long, MutableList<Int>>()

        init {
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val padLat = SNAP_RADIUS_M / 111_320.0
                val cos = minOf(cosLat(a.lat), cosLat(b.lat)).coerceAtLeast(0.01)
                val padLng = padLat / cos
                val minY = cell(minOf(a.lat, b.lat) - padLat)
                val maxY = cell(maxOf(a.lat, b.lat) + padLat)
                val minX = cell(minOf(a.lng, b.lng) - padLng)
                val maxX = cell(maxOf(a.lng, b.lng) + padLng)
                for (y in minY..maxY) {
                    for (x in minX..maxX) {
                        cells.getOrPut(key(y, x)) { ArrayList() } += i
                    }
                }
            }
        }

        fun candidates(target: MapPoint): List<Int> =
            cells[key(cell(target.lat), cell(target.lng))].orEmpty()

        private fun cell(value: Double): Int = Math.floor(value / CELL_DEGREES).toInt()

        private fun key(y: Int, x: Int): Long = (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)

        private companion object {
            const val CELL_DEGREES = 0.01
            const val SNAP_RADIUS_M = 200.0
        }
    }

    private fun cosLat(lat: Double): Double = kotlin.math.cos(Math.toRadians(lat))

    private const val SAMPLE_STEP = 25.0
}
