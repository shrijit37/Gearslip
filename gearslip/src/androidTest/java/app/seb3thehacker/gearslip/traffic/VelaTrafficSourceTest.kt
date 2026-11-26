package app.seb3thehacker.gearslip.traffic

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device check of the keyless Google Directions traffic client. */
@RunWith(AndroidJUnit4::class)
class VelaTrafficSourceTest {

    @Test
    fun fetchesRouteTrafficWithoutKey() = runBlocking {
        val traffic = VelaTrafficSource().routeTraffic(
            MapPoint(52.5200, 13.4050), // Berlin
            MapPoint(53.5500, 9.9900),  // Hamburg
        )
        assertNotNull("keyless directions returned no route", traffic)
        val result = traffic!!
        assertTrue("route geometry missing", result.points.size > 10)
        assertTrue("cumulative distances missing", result.distanceMeters.size == result.points.size)
        Log.i(
            "VelaTrafficTest",
            "points=${result.points.size} spans=${result.spans} " +
                "typical=${result.typicalSeconds} traffic=${result.trafficSeconds}",
        )
    }
}
