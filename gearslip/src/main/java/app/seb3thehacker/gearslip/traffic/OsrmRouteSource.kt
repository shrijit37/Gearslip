package app.seb3thehacker.gearslip.traffic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Real driving routes from FOSSGIS OSRM (the public, keyless router Vela uses), not sample data.
 * The returned geometry follows actual OSM roads and is what the dot renderer draws.
 */
class OsrmRouteSource : RouteSource {

    private val http = OkHttpClient.Builder().build()

    override suspend fun route(origin: MapPoint, destination: MapPoint): RoutePath? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("routing.openstreetmap.de")
                    .addPathSegments("routed-car/route/v1/driving")
                    .addPathSegment("${origin.lng},${origin.lat};${destination.lng},${destination.lat}")
                    .addQueryParameter("overview", "full")
                    .addQueryParameter("geometries", "geojson")
                    .addQueryParameter("steps", "false")
                    .addQueryParameter("alternatives", "false")
                    .build()
                val request = Request.Builder().url(url).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    parse(response.body.string())
                }
            }.getOrNull()
        }

    private fun parse(body: String): RoutePath? {
        val root = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
        val routes = root.optJSONArray("routes") ?: return null
        val route = routes.optJSONObject(0) ?: return null
        val coordinates = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val points = coordinates.toPoints()
        if (points.size < 2) return null
        val distance = route.optDouble("distance", 0.0)
        if (distance <= 0.0) return null
        return RoutePath(
            points = points,
            distanceMeters = distance,
            durationSeconds = route.optDouble("duration", Double.NaN)
                .takeIf { !it.isNaN() },
        )
    }

    private fun JSONArray.toPoints(): List<MapPoint> = (0 until length()).mapNotNull { i ->
        val c = optJSONArray(i) ?: return@mapNotNull null
        val lng = c.optDouble(0, Double.NaN)
        val lat = c.optDouble(1, Double.NaN)
        if (lat.isNaN() || lng.isNaN()) null else MapPoint(lat, lng)
    }
}
