package app.seb3thehacker.gearslip.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.PermissionActivity
import app.seb3thehacker.gearslip.traffic.MapPoint
import app.seb3thehacker.gearslip.traffic.RealRoutePlanner
import app.seb3thehacker.gearslip.traffic.RouteTraffic
import app.seb3thehacker.gearslip.traffic.TrafficLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.ceil
import kotlin.math.max

/**
 * Process-scoped, so leaving and re-entering the screen keeps the warm traffic session and the
 * last route/traffic result. Nothing here is sample data: the car dot is AOSP location, the line
 * is an OSRM route over OpenStreetMap, congestion comes from keyless Google directions, and the
 * basemap is OpenFreeMap vector tiles. Tapping the map sets a real destination.
 */
private val dotMapPlanner = RealRoutePlanner()

/**
 * A first-party map that draws real roads as dots and colours the real route with live traffic.
 *
 * Load path is deliberately staged so the first frame is instant:
 *  1. last-known fix and a bundled style render immediately (no latch, no style download);
 *  2. a fresh fix, the traffic handshake and the map tiles arrive in the background;
 *  3. a tap draws OSRM geometry after one round-trip, then overlays traffic when it lands.
 */
@Composable
fun DotMapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var origin by remember { mutableStateOf<MapPoint?>(null) }
    var trip by remember { mutableStateOf<Pair<MapPoint, MapPoint>?>(null) }
    var traffic by remember { mutableStateOf<RouteTraffic?>(null) }
    var routePoints by remember { mutableStateOf<List<MapPoint>?>(null) }
    var status by remember { mutableStateOf("WAITING FOR LOCATION") }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var permissionGranted by remember(context) { mutableStateOf(hasLocation(context)) }
    var permissionRequested by remember { mutableStateOf(false) }
    var routeRequest by remember { mutableIntStateOf(0) }

    // A permission answer is delivered on the phone's activity; re-check the grant whenever one lands.
    LaunchedEffect(context) {
        PermissionActivity.permissionResults.collect {
            permissionGranted = hasLocation(context)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionGranted = hasLocation(context)
    }

    // 1. Instant: read the last cached fix synchronously. No 6-second latch on the UI path.
    LaunchedEffect(context, permissionGranted) {
        if (!permissionGranted) {
            origin = null
            status = "LOCATION PERMISSION NEEDED"
            if (!permissionRequested) {
                permissionRequested = true
                PermissionActivity.ask(
                    context,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
            return@LaunchedEffect
        }
        val known = lastKnownFix(context)
        origin = known
        status = if (known != null) "TAP MAP TO SET DESTINATION" else "WAITING FOR LOCATION"
        GearslipLog.i("dot map: last-known fix ${if (known != null) "found" else "none"}")
    }

    // 2a. Pay the keyless Google handshake now, while the user is still looking at the map.
    LaunchedEffect(Unit) {
        runCatching { dotMapPlanner.warmup() }
            .onFailure { GearslipLog.w("dot map: traffic warmup failed: ${it.message}") }
    }

    // 2b. Refine to a fresh fix off the UI path; short timeout, callback-driven, cancellable.
    LaunchedEffect(context, permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        val fresh = freshFix(context)
        if (fresh != null) {
            origin = fresh
            if (status == "WAITING FOR LOCATION" || status == "NO LOCATION FIX") {
                status = "TAP MAP TO SET DESTINATION"
            }
            GearslipLog.i("dot map: fresh fix received")
        }
    }

    // Continuous updates keep the car dot live while navigating.
    DisposableEffect(context, permissionGranted) {
        if (!permissionGranted) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(LocationManager::class.java) ?: return@DisposableEffect onDispose { }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                origin = MapPoint(location.latitude, location.longitude)
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
        }
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2_000L, 0f,
                ContextCompat.getMainExecutor(context), listener,
            )
        } catch (e: Exception) {
            GearslipLog.w("dot map: location updates unavailable: ${e.message}")
        }
        onDispose {
            try { manager.removeUpdates(listener) } catch (_: Exception) { }
        }
    }

    // MapLibre: vector tiles restyled into dotted roads. The style is bundled, so no download.
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            MapLibre.getInstance(ctx)
            val options = MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)
            MapView(ctx, options).apply {
                onCreate(null)
                onStart()
                onResume()
                getMapAsync { m ->
                    m.uiSettings.isLogoEnabled = false
                    m.uiSettings.isCompassEnabled = false
                    m.uiSettings.isRotateGesturesEnabled = false
                    m.uiSettings.isTiltGesturesEnabled = false
                    map = m
                }
            }
        },
        onRelease = { view -> runCatching { view.onPause(); view.onStop(); view.onDestroy() } },
    )

    LaunchedEffect(map, context) {
        val m = map ?: return@LaunchedEffect
        style = null
        val json = runCatching {
            context.assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromJson(json)) { loaded ->
            restyle(loaded)
            style = loaded
            GearslipLog.i("dot map: local style ready")
        }
    }

    // Centre once on the first available fix; do not fight the user on every GPS update.
    LaunchedEffect(map) {
        val m = map ?: return@LaunchedEffect
        val point = snapshotFlow { origin }.filterNotNull().first()
        runCatching { m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lng), 13.5)) }
    }

    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { point ->
            routeRequest++
            val here = origin
            if (here != null) {
                trip = here to MapPoint(point.latitude, point.longitude)
                traffic = null
                routePoints = null
                status = "ROUTING..."
            } else {
                status = "NO LOCATION FIX"
            }
            true
        }
        m.addOnMapClickListener(listener)
        onDispose { runCatching { m.removeOnMapClickListener(listener) } }
    }

    // Real routing + real traffic. Geometry is published by onGeometry as soon as OSRM answers.
    LaunchedEffect(trip, routeRequest) {
        val (from, to) = trip ?: return@LaunchedEffect
        status = "ROUTING..."
        val started = System.currentTimeMillis()
        val result = dotMapPlanner.plan(from, to, onGeometry = { geometry ->
            traffic = geometry
            routePoints = geometry.points
            GearslipLog.i("dot map: geometry in ${System.currentTimeMillis() - started} ms")
        })
        traffic = result
        GearslipLog.i("dot map: traffic in ${System.currentTimeMillis() - started} ms")
        status = when {
            result == null -> "NO ROUTE"
            result.spans.isEmpty() -> "ROUTE · NO LIVE TRAFFIC"
            else -> "LIVE TRAFFIC ON ROUTE"
        }
    }

    LaunchedEffect(origin, style) {
        val s = style ?: return@LaunchedEffect
        updateCar(s, origin)
    }

    // Build the GeoJSON off the main thread and cap the dot count.
    LaunchedEffect(traffic, style) {
        val s = style ?: return@LaunchedEffect
        val route = traffic
        if (route == null) {
            updateRoute(s, FeatureCollection.fromFeatures(emptyList()))
            return@LaunchedEffect
        }
        val features = withContext(Dispatchers.Default) { routeFeatures(route) }
        updateRoute(s, features)
    }

    // Fit the route once per trip, not again when traffic colours are overlaid.
    LaunchedEffect(routePoints, map) {
        val points = routePoints ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (points.size < 2) return@LaunchedEffect
        val bounds = LatLngBounds.Builder()
            .includes(points.map { LatLng(it.lat, it.lng) })
            .build()
        runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 32, 32, 32, 32)) }
    }

    // Thin Nothing chrome over the map; the map itself stays full-bleed.
    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp)
            .background(Color.Transparent),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("DOT MAP", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("● CLEAR   ● SLOW   ● JAM", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun hasLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Synchronous and cheap: the OS already cached the last fix, so the UI never waits for GPS. */
private fun lastKnownFix(context: Context): MapPoint? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    return manager.allProviders
        .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { MapPoint(it.latitude, it.longitude) }
}

/** One fresh fix, callback-driven with a short cap. Runs in the background; never blocks a frame. */
private suspend fun freshFix(context: Context): MapPoint? = withContext(Dispatchers.Main) {
    val manager = context.getSystemService(LocationManager::class.java) ?: return@withContext null
    if (!hasLocation(context)) return@withContext null
    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { it in manager.allProviders && manager.isProviderEnabled(it) }
        ?: return@withContext null
    withTimeoutOrNull(4_000L) {
        suspendCancellableCoroutine<MapPoint?> { cont ->
            try {
                manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { fix ->
                    if (cont.isActive) cont.resume(fix?.let { MapPoint(it.latitude, it.longitude) }) {}
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null) {}
            }
        }
    }
}

private fun restyle(style: Style) {
    style.layers.forEach { layer ->
        try { layer.setProperties(PropertyFactory.visibility(Property.NONE)) } catch (_: Exception) { }
    }
    style.getLayer("background")?.setProperties(
        PropertyFactory.visibility(Property.VISIBLE),
        PropertyFactory.backgroundColor(android.graphics.Color.parseColor("#050505")),
    )

    fun road(id: String, classes: List<String>, colour: Int, baseWidth: Float) {
        val filter = Expression.any(
            *classes.map { Expression.eq(Expression.get("class"), Expression.literal(it)) }.toTypedArray(),
        )
        val layer = LineLayer(id, "openmaptiles").withProperties(
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(colour),
            PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.literal(10), Expression.literal(baseWidth * 0.6),
                    Expression.literal(14), Expression.literal(baseWidth),
                    Expression.literal(18), Expression.literal(baseWidth * 1.8),
                ),
            ),
            PropertyFactory.lineDasharray(arrayOf(0.05f, 1.6f)),
        )
        layer.sourceLayer = "transportation"
        layer.setFilter(filter)
        style.addLayer(layer)
    }
    road("dot-motorway", listOf("motorway", "trunk"), android.graphics.Color.parseColor("#9A9A9A"), 3.4f)
    road("dot-primary", listOf("primary"), android.graphics.Color.parseColor("#797979"), 2.7f)
    road("dot-secondary", listOf("secondary", "tertiary"), android.graphics.Color.parseColor("#585858"), 2.1f)
    road("dot-minor", listOf("minor", "service"), android.graphics.Color.parseColor("#3C3C3C"), 1.5f)

    style.addSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyList())))
    style.addLayer(
        CircleLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.circleRadius(3.2f),
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get("level"),
                    Expression.literal(0), Expression.color(android.graphics.Color.parseColor("#4ADE80")),
                    Expression.literal(1), Expression.color(android.graphics.Color.parseColor("#FBBF24")),
                    Expression.literal(2), Expression.color(android.graphics.Color.parseColor("#FF3030")),
                    Expression.color(android.graphics.Color.parseColor("#555555")),
                ),
            ),
        ),
    )
    style.addSource(GeoJsonSource(CAR_SOURCE, FeatureCollection.fromFeatures(emptyList())))
    style.addLayer(
        CircleLayer(CAR_LAYER, CAR_SOURCE).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(android.graphics.Color.parseColor("#FF3030")),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
}

/** Cap dots so a long route never builds thousands of native GeoJSON features on the UI thread. */
private fun routeFeatures(route: RouteTraffic): FeatureCollection {
    val points = route.points
    val cumulative = route.distanceMeters
    val step = max(1, ceil(points.size / MAX_ROUTE_DOTS.toDouble()).toInt())
    val features = ArrayList<Feature>(points.size / step + 2)
    var i = 0
    while (i < points.size) {
        features.add(dotFeature(points[i], route.levelAt(cumulative[i])))
        i += step
    }
    if ((points.size - 1) % step != 0) {
        features.add(dotFeature(points.last(), route.levelAt(cumulative.last())))
    }
    return FeatureCollection.fromFeatures(features)
}

private fun dotFeature(point: MapPoint, level: TrafficLevel): Feature =
    Feature.fromGeometry(Point.fromLngLat(point.lng, point.lat)).apply {
        addNumberProperty("level", level.wire.toDouble())
    }

private fun updateRoute(style: Style, features: FeatureCollection) {
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
    source.setGeoJson(features)
}

private fun updateCar(style: Style, origin: MapPoint?) {
    val source = style.getSourceAs<GeoJsonSource>(CAR_SOURCE) ?: return
    val features = origin?.let { listOf(dotFeature(it, TrafficLevel.JAM)) }.orEmpty()
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

private const val STYLE_ASSET = "dotmap-style.json"
private const val MAX_ROUTE_DOTS = 1500
private const val ROUTE_SOURCE = "gearslip-dot-route"
private const val ROUTE_LAYER = "gearslip-dot-route-dots"
private const val CAR_SOURCE = "gearslip-dot-car"
private const val CAR_LAYER = "gearslip-dot-car-dot"
