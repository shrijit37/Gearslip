package app.seb3thehacker.gearslip.traffic

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * Draws a route as Nothing-style dots: clear green, slow amber, jam red, with the car as a
 * solid red dot. Only the route is drawn here — the caller greys or hides the surrounding road
 * dots while navigating.
 */
@Composable
fun DotRoute(
    traffic: RouteTraffic,
    modifier: Modifier,
    carIndex: Int = 0,
    pitch: Dp = 7.dp,
    radius: Dp = 1.8.dp,
) {
    Canvas(modifier) {
        val points = traffic.points
        if (points.size < 2) return@Canvas

        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }
        val pad = 10.dp.toPx()
        val usableW = (size.width - 2 * pad).coerceAtLeast(1f)
        val usableH = (size.height - 2 * pad).coerceAtLeast(1f)
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-9)
        val lngSpan = (maxLng - minLng).coerceAtLeast(1e-9)

        fun project(p: MapPoint): Offset = Offset(
            x = pad + ((p.lng - minLng) / lngSpan * usableW).toFloat(),
            y = pad + ((1 - (p.lat - minLat) / latSpan) * usableH).toFloat(),
        )

        val screen = points.map(::project)
        val cumulative = traffic.distanceMeters
        val pitchPx = pitch.toPx()
        val r = radius.toPx()

        for (i in 1 until screen.size) {
            val a = screen[i - 1]
            val b = screen[i]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            if (length <= 0f) continue
            val fromMetres = cumulative[i - 1]
            val spanMetres = cumulative[i] - fromMetres
            var d = 0f
            while (d <= length) {
                val f = d / length
                val level = traffic.levelAt(fromMetres + spanMetres * f)
                val colour = when (level) {
                    TrafficLevel.CLEAR -> Color(0xFF4ADE80)
                    TrafficLevel.SLOW -> Color(0xFFFBBF24)
                    TrafficLevel.JAM -> Color(0xFFFF3030)
                }
                drawCircle(colour, if (level == TrafficLevel.JAM) r * 1.4f else r, Offset(a.x + dx * f, a.y + dy * f))
                d += pitchPx
            }
        }

        val car = screen[carIndex.coerceIn(0, screen.lastIndex)]
        drawCircle(Color(0xFFFF3030).copy(alpha = 0.25f), r * 6f, car)
        drawCircle(Color(0xFFFF3030), r * 4f, car)
        drawCircle(Color.White, r * 1.2f, car)
    }
}
