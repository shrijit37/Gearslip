package app.seb3thehacker.gearslip.traffic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

/**
 * Keyless route traffic from Google's public web directions endpoint, following the approach
 * documented by Vela Maps (https://github.com/PimpinPumpkin/Vela, GPL-3.0, © PimpinPumpkin):
 * a per-install logged-out browser session, no API key, no account, no backend. See NOTICE.md.
 *
 * Only the route is coloured — Google's keyless reply carries congestion spans for the returned
 * route, not a vector traffic layer for every road. Free-flow gaps are absent from the reply and
 * are rendered as CLEAR, never invented.
 *
 * The captured `pb` template below is reproduced from Vela's DirectionsPb.kt so the request has
 * the same shape as a logged-out desktop browser's. Field indices are compiled defaults; to
 * survive a Google response reshape, pass a replacement [paths] map (Vela ships these through a
 * signed calibration channel, which Gearslip does not currently have).
 */
class VelaTrafficSource(
    private val paths: Map<String, List<Int>> = DEFAULT_PATHS,
    dns: Dns? = null,
) : TrafficSource {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .apply { if (dns != null) dns(dns) }
        .cookieJar(MemoryCookieJar())
        .build()

    @Volatile
    private var warmed = false

    override suspend fun routeTraffic(origin: MapPoint, destination: MapPoint): RouteTraffic? =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureSession()
                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("www.google.com")
                    .addPathSegments("maps/preview/directions")
                    .addQueryParameter("authuser", "0")
                    .addQueryParameter("hl", "en")
                    .addQueryParameter("gl", "us")
                    .addQueryParameter("pb", buildPb(origin, destination))
                    .build()
                val request = Request.Builder().url(url).browserHeaders(document = false).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) parse(response.body.string()) else null
                }
            }.getOrNull()
        }

    /** Warm a logged-out session once per process so consent-gated regions don't redirect. */
    override suspend fun warmup() = ensureSession()

    private suspend fun ensureSession() = withContext(Dispatchers.IO) {
        if (warmed) return@withContext
        runCatching {
            val url = HttpUrl.Builder()
                .scheme("https").host("www.google.com")
                .addPathSegment("maps")
                .addQueryParameter("hl", "en")
                .addQueryParameter("gl", "us")
                .build()
            val request = Request.Builder().url(url).browserHeaders(document = true).build()
            client.newCall(request).execute().use { it.body.close() }
        }
        warmed = true
    }

    // ------------------------------------------------------------------ parsing

    private fun parse(body: String): RouteTraffic? {
        val stripped = if (body.startsWith(")]}'")) body.substringAfter('\n').ifEmpty { body.removePrefix(")]}'") } else body
        val root = runCatching { JSONArray(stripped.trim()) }.getOrNull() ?: return null
        val routes = atPath(root, "routes", 0, 1).asArray() ?: return null
        val geometries = atPath(root, "geometries", 0, 7).asArray()
        val route = routes.optJSONArray(0) ?: return null
        val summary = atPath(route, "summary", 0).asArray() ?: return null

        val points = decodeGeometry(geometries?.optJSONArray(0)).ifEmpty { fallbackPoints(summary) }
        if (points.size < 2) return null

        val spanArray = atPath(route, "spans", 3, 5, 0).asArray().orEmptyArray()
        val spans = (0 until spanArray.length())
            .mapNotNull { i -> parseSpan(spanArray.optJSONArray(i)) }
            .sortedBy { it.startMeters }

        return RouteTraffic(
            points = points,
            spans = spans,
            typicalSeconds = atPath(summary, "typical", 3, 0).number(),
            trafficSeconds = atPath(summary, "traffic", 10, 0, 0).number(),
        )
    }

    private fun parseSpan(span: JSONArray?): TrafficSpan? {
        if (span == null) return null
        val level = span.opt(0).number()?.toInt() ?: return null
        val start = span.opt(1).number() ?: return null
        val length = span.opt(2).number() ?: return null
        if (level < 1 || length <= 0.0) return null
        return TrafficSpan(TrafficLevel.fromWire(level), start, length)
    }

    /** Google sends each route's real geometry as E7 delta arrays at root[0][7][i]. */
    private fun decodeGeometry(node: JSONArray?): List<MapPoint> {
        if (node == null) return emptyList()
        val lat = node.optJSONArray(0) ?: return emptyList()
        val lng = node.optJSONArray(1) ?: return emptyList()
        if (lat.length() != lng.length() || lat.length() < 2) return emptyList()
        var la = 0L
        var ln = 0L
        return (0 until lat.length()).map { i ->
            la += lat.opt(i).number()?.toLong() ?: 0L
            ln += lng.opt(i).number()?.toLong() ?: 0L
            MapPoint(la / 1e7, ln / 1e7)
        }
    }

    private fun fallbackPoints(summary: JSONArray): List<MapPoint> = listOfNotNull(
        atPath(summary, "start", 7, 3, 2).asArray()?.toPoint(),
        atPath(summary, "end", 7, 3, 3).asArray()?.toPoint(),
    )

    private fun JSONArray.toPoint(): MapPoint? {
        val lat = opt(2).number() ?: return null
        val lng = opt(3).number() ?: return null
        return MapPoint(lat, lng)
    }

    // ------------------------------------------------------------------ request

    private fun buildPb(origin: MapPoint, destination: MapPoint): String {
        var template = TEMPLATE
        val span = max(1500.0, distanceMeters(origin, destination) * 1.35)
        val centreLat = (origin.lat + destination.lat) / 2
        val centreLng = (origin.lng + destination.lng) / 2
        template = VIEWPORT.replace(template, "!1m3!1d${fmt(span)}!2d${fmt(centreLng)}!3d${fmt(centreLat)}")
        return template
            .replace("{OLAT}", fmt(origin.lat))
            .replace("{OLNG}", fmt(origin.lng))
            .replace("{DLAT}", fmt(destination.lat))
            .replace("{DLNG}", fmt(destination.lng))
            .replace("{MODE}", "0") // driving
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.7f", v)

    private fun Request.Builder.browserHeaders(document: Boolean): Request.Builder = apply {
        addHeader("User-Agent", USER_AGENT)
        addHeader("Accept-Language", "en-US,en;q=0.9")
        addHeader("Accept", if (document) "text/html,application/xhtml+xml" else "*/*")
        addHeader("Sec-CH-UA", SEC_CH_UA)
        addHeader("Sec-CH-UA-Mobile", "?0")
        addHeader("Sec-CH-UA-Platform", "\"Windows\"")
        addHeader("Sec-Fetch-Site", if (document) "none" else "same-origin")
        addHeader("Sec-Fetch-Mode", if (document) "navigate" else "cors")
        addHeader("Sec-Fetch-Dest", if (document) "document" else "empty")
        if (!document) addHeader("Referer", "https://www.google.com/maps/")
    }

    /** In-memory, per-install cookies. Nothing is persisted or shared across installs. */
    private class MemoryCookieJar : CookieJar {
        private val cookies = LinkedHashMap<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.filter { it.expiresAt > System.currentTimeMillis() }
                .forEach { this@MemoryCookieJar.cookies[it.name] = it }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values
            .filter { it.expiresAt > System.currentTimeMillis() && (it.secure || url.isHttps) }

        fun clear() = cookies.clear()
    }

    // ------------------------------------------------------------------ helpers

    private fun atPath(root: Any, key: String, vararg fallback: Int): Any? {
        var current: Any? = root
        for (i in (paths[key] ?: fallback.toList())) {
            current = current.child(i) ?: return null
        }
        return current
    }

    private fun Any?.child(index: Int): Any? = when (this) {
        is JSONArray -> if (index in 0 until length()) opt(index) else null
        is JSONObject -> opt(index.toString())
        else -> null
    }

    private fun Any?.asArray(): JSONArray? = this as? JSONArray

    private fun JSONArray?.orEmptyArray(): JSONArray = this ?: JSONArray()

    private fun Any?.number(): Double? = when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }

    companion object {
        /** Compiled field indices. Replace through the constructor when Google reshapes a reply. */
        val DEFAULT_PATHS: Map<String, List<Int>> = mapOf(
            "routes" to listOf(0, 1),
            "geometries" to listOf(0, 7),
            "summary" to listOf(0),
            "distance" to listOf(2, 0),
            "typical" to listOf(3, 0),
            "traffic" to listOf(10, 0, 0),
            "start" to listOf(7, 3, 2),
            "end" to listOf(7, 3, 3),
            "spans" to listOf(3, 5, 0),
        )

        private val VIEWPORT = Regex("!1m3!1d[0-9.]+!2d-?[0-9.]+!3d-?[0-9.]+")

        /** Captured desktop-browser request shape; reproduced from Vela's DirectionsPb.kt. */
        private const val TEMPLATE =
            "!1m4!3m2!3d{OLAT}!4d{OLNG}!6e2!1m4!3m2!3d{DLAT}!4d{DLNG}!6e2" +
                "!3m12!1m3!1d24960.741896132306!2d-121.7527808!3d38.554674999999996!2m3!1f0.0!2f0.0!3f0.0" +
                "!3m2!1i1024!2i768!4f13.1!6m56!1m5!18b1!30b1!31m1!1b1!34e1!2m4!5m1!6e2!20e3!39b1!6m27!32i1" +
                "!49b1!63m0!66b1!85b1!114b1!149b1!206b1!209b1!212b1!216b1!222b1!223b1!232b1!234b1!235b1" +
                "!244b1!246b1!250b1!253b1!260b1!266b1!270b1!273b1!279b1!281b1!291m0!10b1!12b1!13b1!14b1" +
                "!16b1!17m1!3e1!20m5!1e{MODE}!2e3!5e2!6b1!14b1!46m1!1b0!96b1!99b1!15i10142!20m28!1m6!1m2" +
                "!1i0!2i0!2m2!1i530!2i768!1m6!1m2!1i974!2i0!2m2!1i1024!2i768!1m6!1m2!1i0!2i0!2m2!1i1024" +
                "!2i20!1m6!1m2!1i0!2i748!2m2!1i1024!2i768!27b1!40i783!47m2!8b1!10e2"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/152.0.0.0 Safari/537.36"

        private const val SEC_CH_UA =
            "\"Chromium\";v=\"152\", \"Google Chrome\";v=\"152\", \"Not?A.Brand\";v=\"99\""
    }
}
