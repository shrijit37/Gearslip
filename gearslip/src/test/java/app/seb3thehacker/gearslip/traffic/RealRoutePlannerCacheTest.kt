package app.seb3thehacker.gearslip.traffic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RealRoutePlannerCacheTest {

    private class CountingRouter : RouteSource {
        var calls = 0
            private set

        override suspend fun route(origin: MapPoint, destination: MapPoint): RoutePath {
            calls++
            return RoutePath(listOf(origin, destination), 100.0)
        }
    }

    private object NoTraffic : TrafficSource {
        override suspend fun routeTraffic(origin: MapPoint, destination: MapPoint): RouteTraffic? = null
    }

    @Test fun `nearby destinations within the cache window are not conflated`() = runBlocking {
        // Both destinations snap to the same 1/500-degree cell, but they are ~200 m apart.
        val router = CountingRouter()
        val planner = RealRoutePlanner(router, NoTraffic)
        val origin = MapPoint(52.5200, 13.4050)

        planner.plan(origin, MapPoint(52.5201, 13.4051))
        planner.plan(origin, MapPoint(52.5219, 13.4051))

        assertEquals(2, router.calls)
    }

    @Test fun `an identical destination still hits the cache`() = runBlocking {
        val router = CountingRouter()
        val planner = RealRoutePlanner(router, NoTraffic)
        val origin = MapPoint(52.5200, 13.4050)
        val destination = MapPoint(52.5300, 13.4150)

        planner.plan(origin, destination)
        planner.plan(origin, destination)

        assertEquals(1, router.calls)
    }
}
