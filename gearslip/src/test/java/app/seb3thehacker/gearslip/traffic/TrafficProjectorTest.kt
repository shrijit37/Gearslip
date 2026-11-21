package app.seb3thehacker.gearslip.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficProjectorTest {

    /** A straight line of points ~25 m apart, the same spacing the projector samples at. */
    private fun straightLine(): List<MapPoint> = (0 until 41).map { MapPoint(52.5 + it * 0.000225, 13.4) }

    @Test fun `identical geometry keeps the original congestion boundaries`() {
        val points = straightLine()
        val routed = RoutePath(points, 1_000.0)
        val google = RouteTraffic(
            points = points,
            spans = listOf(TrafficSpan(TrafficLevel.JAM, 0.0, 1_000.0)),
        )

        val merged = TrafficProjector.merge(routed, google)
        val span = merged.spans.single()

        // The old averaging code started this span at ~500 m and ran past its real end.
        assertEquals(TrafficLevel.JAM, span.level)
        assertTrue("span start ${span.startMeters} should be near 0", span.startMeters < 50.0)
        assertTrue("span length ${span.lengthMeters} should be near 1000", span.lengthMeters > 950.0)
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
