package dev.healthhub.feature.activity

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.healthhub.core.designsystem.ExpressiveShapes
import dev.healthhub.core.designsystem.GeneratedTokens
import dev.healthhub.core.designsystem.LocalChartChrome
import dev.healthhub.core.designsystem.channelColor
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * The route, drawn by MapLibre Native under a style built from the design tokens.
 *
 * There is no map account to sign up for and no key to configure, which is a deliberate
 * consequence of the constitution's "clone and run" constraint: the default style is the
 * product's own surface colour with the route on top, and a basemap appears only if a
 * deployment sets `hh_map_tiles_url` to a tile source it is entitled to use — the same rule and
 * the same look as the web client's `VITE_MAP_TILES_URL`. A route without a basemap still
 * answers the question the athlete is asking: what shape was this ride, and where on it was I.
 *
 * Gaps in the fix are gaps in the line. That is [Route.geometry]'s job, and it is unit-tested
 * without a GL context precisely so this file can stay thin.
 */
private const val ROUTE_SOURCE = "route"
private const val START_SOURCE = "route-start"
private const val FINISH_SOURCE = "route-finish"
private const val MARKER_SOURCE = "cursor"
private const val BASEMAP_SOURCE = "basemap"

private const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""
private const val CAMERA_PADDING_DP = 24f

private val MAP_HEIGHT = 260.dp

@Composable
internal fun RouteMap(
    lat: DoubleArray?,
    lon: DoubleArray?,
    time: DoubleArray?,
    cursor: State<Int?>,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(lat, lon, time) { Route.geometry(lat, lon, time) }
    val bounds = geometry.bounds ?: return

    val context = LocalContext.current
    val chrome = LocalChartChrome.current
    // The map is a container like the chart bed, so it wears the container colour rather than the
    // page colour. It doubles as the casing under the route: a halo only reads if it is the
    // colour of whatever the route is lying on.
    val bed = MaterialTheme.colorScheme.surfaceContainerHighest
    val routeColour = channelColor("speed")
    val startColour = channelColor("elevation")
    val finishColour = channelColor("hr")
    val tilesUrl = stringResource(R.string.hh_map_tiles_url)
    val tilesAttribution = stringResource(R.string.hh_map_tiles_attribution)
    val cameraPaddingPx = with(LocalDensity.current) { CAMERA_PADDING_DP.dp.roundToPx() }

    // MapLibre loads its native libraries here, before any MapView is constructed. The call is
    // idempotent, so doing it at the only place that needs it beats an Application hook that
    // every other feature would pay for.
    remember(context) { MapLibre.getInstance(context) }
    val mapView = remember(context) { createMapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    /*
     * One effect for the whole map: build the style, then keep the marker on the cursor.
     *
     * They are joined because the second is only meaningful once the first has finished — a
     * separate effect would race the style load and drop the first few marker updates. It is
     * restarted when the palette changes, which is what carries a Material You switch or a move
     * to dark onto the map rather than leaving it wearing yesterday's colours.
     */
    LaunchedEffect(mapView, geometry, chrome, bed, routeColour, tilesUrl) {
        val map = mapView.awaitMap()
        map.uiSettings.apply {
            isAttributionEnabled = tilesUrl.isNotBlank()
            isLogoEnabled = tilesUrl.isNotBlank()
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
        }

        val style = map.awaitStyle(Style.Builder().fromJson(backgroundStyle(bed)))

        if (tilesUrl.isNotBlank()) {
            val tiles = TileSet(TILEJSON_VERSION, tilesUrl).apply {
                // Whoever configured the tile source is the one who knows what it must credit.
                if (tilesAttribution.isNotBlank()) attribution = tilesAttribution
            }
            style.addSource(RasterSource(BASEMAP_SOURCE, tiles, 256))
            style.addLayer(
                RasterLayer(BASEMAP_SOURCE, BASEMAP_SOURCE).withProperties(
                    // Held back from full opacity so the route stays the brightest thing on it.
                    PropertyFactory.rasterOpacity(0.85f),
                    PropertyFactory.rasterSaturation(-0.3f),
                ),
            )
        }

        style.addSource(GeoJsonSource(ROUTE_SOURCE, routeGeoJson(geometry)))
        style.addSource(GeoJsonSource(START_SOURCE, pointGeoJson(geometry.startPosition())))
        style.addSource(GeoJsonSource(FINISH_SOURCE, pointGeoJson(geometry.finishPosition())))
        style.addSource(GeoJsonSource(MARKER_SOURCE, EMPTY_COLLECTION))

        val lineWidth = GeneratedTokens.lineWidth.value
        // A casing under the line keeps the route legible over a pale basemap and a dark one
        // alike — the same trick the token layer uses for text on an unpredictable surface.
        style.addLayer(
            LineLayer("route-casing", ROUTE_SOURCE).withProperties(
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineColor(bed.toArgb()),
                PropertyFactory.lineWidth(lineWidth * 2.5f),
                PropertyFactory.lineOpacity(0.7f),
            ),
        )
        style.addLayer(
            LineLayer("route-line", ROUTE_SOURCE).withProperties(
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineColor(routeColour.toArgb()),
                PropertyFactory.lineWidth(lineWidth * 1.5f),
            ),
        )
        style.addLayer(endpointLayer("route-start-dot", START_SOURCE, startColour, bed))
        style.addLayer(endpointLayer("route-finish-dot", FINISH_SOURCE, finishColour, bed))
        style.addLayer(
            CircleLayer(MARKER_SOURCE, MARKER_SOURCE).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(routeColour.toArgb()),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(bed.toArgb()),
            ),
        )

        val padded = Route.pad(bounds)
        map.moveCamera(
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.from(padded.north, padded.east, padded.south, padded.west),
                cameraPaddingPx,
            ),
        )

        // The marker is pushed straight at the source rather than through recomposition: the
        // cursor moves with the finger, and a recomposition per frame of a drag would be felt.
        val marker = style.getSourceAs<GeoJsonSource>(MARKER_SOURCE)
        snapshotFlow { cursor.value }.collect { index ->
            val position = if (index == null) null else Route.positionAtOrBefore(lat, lon, index)
            marker?.setGeoJson(pointGeoJson(position))
        }
    }

    // Shaped like every other container on the screen rather than left as a full-bleed rectangle.
    // A GL surface does not respect a parent's rounded outline on its own, so the clip goes on
    // the view itself; without it the map is the one square corner in a screen of capsules.
    AndroidView(
        factory = { mapView },
        modifier = modifier
            .fillMaxWidth()
            .height(MAP_HEIGHT)
            .clip(ExpressiveShapes.extraLargeIncreased)
            .semantics { contentDescription = "Route map" },
    )
}

private const val TILEJSON_VERSION = "2.2.0"

private fun endpointLayer(id: String, source: String, fill: Color, stroke: Color): CircleLayer =
    CircleLayer(id, source).withProperties(
        PropertyFactory.circleRadius(5f),
        PropertyFactory.circleColor(fill.toArgb()),
        PropertyFactory.circleStrokeWidth(2f),
        PropertyFactory.circleStrokeColor(stroke.toArgb()),
    )

private fun createMapView(context: Context): MapView = MapView(context).apply { onCreate(null) }

/** The GL surface exists asynchronously; suspending on it beats a callback per caller. */
private suspend fun MapView.awaitMap(): MapLibreMap = suspendCancellableCoroutine { continuation ->
    getMapAsync { map -> continuation.resume(map) }
}

private suspend fun MapLibreMap.awaitStyle(builder: Style.Builder): Style =
    suspendCancellableCoroutine { continuation ->
        setStyle(builder) { style -> continuation.resume(style) }
    }

private fun backgroundStyle(surface: Color): String =
    """{"version":8,"sources":{},"layers":[{"id":"background","type":"background",""" +
        """"paint":{"background-color":"${surface.toHex()}"}}]}"""

private fun Color.toHex(): String {
    val argb = toArgb()
    return String.format(
        Locale.US,
        "#%02X%02X%02X",
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
    )
}

private fun RouteGeometry.startPosition(): DoubleArray? =
    segments.firstOrNull()?.let { doubleArrayOf(it.lon[0], it.lat[0]) }

private fun RouteGeometry.finishPosition(): DoubleArray? =
    segments.lastOrNull()?.let { doubleArrayOf(it.lon.last(), it.lat.last()) }

/**
 * GeoJSON is built as text rather than through the object model on purpose: the geometry crosses
 * to the native map as a string either way, and assembling it in one buffer avoids allocating a
 * feature object per coordinate on the frame that opens the screen.
 */
private fun routeGeoJson(geometry: RouteGeometry): String {
    val builder = StringBuilder(geometry.segments.sumOf { it.lon.size } * 24 + 64)
    builder.append("""{"type":"FeatureCollection","features":[""")
    geometry.segments.forEachIndexed { index, segment ->
        if (index > 0) builder.append(',')
        builder.append("""{"type":"Feature","properties":{"segment":""")
            .append(index)
            .append("""},"geometry":{"type":"LineString","coordinates":[""")
        for (i in segment.lon.indices) {
            if (i > 0) builder.append(',')
            builder.append('[').append(segment.lon[i]).append(',').append(segment.lat[i]).append(']')
        }
        builder.append("]}}")
    }
    builder.append("]}")
    return builder.toString()
}

private fun pointGeoJson(position: DoubleArray?): String {
    if (position == null) return EMPTY_COLLECTION
    return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},""" +
        """"geometry":{"type":"Point","coordinates":[${position[0]},${position[1]}]}}]}"""
}
