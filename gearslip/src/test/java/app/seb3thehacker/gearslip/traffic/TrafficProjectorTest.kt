package app.seb3thehacker.gearslip.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrafficProjectorTest {

    /** A straight line of points ~25 m apart, the same spacing the projector samples at. */
    private fun straightLine(): List<MapPoint> = (0 until 41).map { MapPoint(52.5 + it * 0.000225, 13.4) }

    @Test fun `identical geometry keeps the original congestion boundaries`() {
        val points = straightLine()
        val routed = RoutePath(points, 1_000.0)
        val span = TrafficSpan(TrafficLevel.JAM, 12.0, 431.5)
        val google = RouteTraffic(points, listOf(span))

        assertEquals(listOf(span), TrafficProjector.merge(routed, google).spans)
    }

    @Test fun `short span on nearby geometry keeps both boundaries`() {
        val points = straightLine()
        val routed = RoutePath(points, 1_000.0)
        val nearby = points.map { it.copy(lng = it.lng + 0.0001) }
        val span = TrafficSpan(TrafficLevel.SLOW, 61.0, 8.0)

        val projected = TrafficProjector.merge(routed, RouteTraffic(nearby, listOf(span))).spans.single()

        assertEquals(span.startMeters, projected.startMeters, 0.001)
        assertEquals(span.lengthMeters, projected.lengthMeters, 0.001)
    }

    @Test fun `samples too far from the route leave a clear gap`() {
        val points = straightLine()
        val routed = RoutePath(points, 1_000.0)
        val detour = points.toMutableList().apply {
            this[20] = this[20].copy(lng = this[20].lng + 0.008)
        }
        val span = TrafficSpan(TrafficLevel.JAM, 0.0, 3_000.0)

        val merged = TrafficProjector.merge(routed, RouteTraffic(detour, listOf(span)))

        assertEquals(2, merged.spans.size)
        assertEquals(TrafficLevel.JAM, merged.levelAt(routed.cumulative[10]))
        assertEquals(TrafficLevel.CLEAR, merged.levelAt(routed.cumulative[20]))
        assertEquals(TrafficLevel.JAM, merged.levelAt(routed.cumulative[30]))
    }

    @Test fun `long route projects traffic through the spatial lookup`() {
        // 600 vertices ~25 m apart (~15 km): far enough that a per-sample full scan is costly and
        // a sample near the end has no chance of finding its segment in the first few bins.
        val points = (0 until 600).map { MapPoint(52.5 + it * 0.000225, 13.4) }
        val routed = RoutePath(points, 15_000.0)
        val offset = points.map { it.copy(lng = it.lng + 0.00005) } // ~3.5 m east at this latitude
        val span = TrafficSpan(TrafficLevel.JAM, 2_000.0, 4_000.0)

        val merged = TrafficProjector.merge(routed, RouteTraffic(offset, listOf(span)))
        val projected = merged.spans.single()

        assertEquals(TrafficLevel.JAM, projected.level)
        assertEquals("span start", 2_000.0, projected.startMeters, 60.0)
        assertEquals("span end", 6_000.0, projected.startMeters + projected.lengthMeters, 60.0)
        assertEquals(TrafficLevel.JAM, merged.levelAt(routed.cumulative[120]))
        assertEquals(TrafficLevel.CLEAR, merged.levelAt(routed.cumulative[400]))
    }

    @Test fun `projection and rejection use the same latitude scaled metric`() {
        // A diagonal segment at 60 N: 0.0035 degrees of latitude is ~390 m, but the same
        // longitude delta is only ~195 m. An unscaled projection snaps the target below to the
        // segment midpoint (~218 m away) and rejects it; a scaled projection snaps it ~80 m away.
        val segment = listOf(MapPoint(60.0, 0.0), MapPoint(60.0035, 0.0035))
        val cumulative = listOf(0.0, 435.6)
        val target = MapPoint(60.0, 0.0035)

        val projected = TrafficProjector.nearestDistance(target, segment, cumulative)

        assertNotNull("the sample should be within the 200 m cutoff", projected)
    }
}
