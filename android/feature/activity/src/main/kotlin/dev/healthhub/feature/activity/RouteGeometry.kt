package dev.healthhub.feature.activity

import dev.healthhub.core.telemetry.Metrics
import kotlin.math.max

/**
 * Geometry for the route layer.
 *
 * Kept apart from the MapLibre wrapper so the rule that matters — a gap in the fix is a gap in
 * the line, never a straight edge across a tunnel — is testable without a GL context. This is
 * the Kotlin twin of `web/src/core/map/route.ts`, including its thresholds.
 */
internal data class RouteBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

/** One continuous run of fixes. Two segments in a route means one gap between them. */
internal class RouteSegment(
    val lon: DoubleArray,
    val lat: DoubleArray,
    /** Sample index of the first fix in this segment, for mapping a chart cursor onto the line. */
    val startIndex: Int,
)

internal class RouteGeometry(
    val segments: List<RouteSegment>,
    val bounds: RouteBounds?,
)

internal object Route {

    /**
     * A leg implying this speed is a dropout the recorder papered over, not movement. Drawing it
     * produces the long straight spike across a city that makes a route look corrupt.
     *
     * The test is implied speed rather than raw distance, because sampling rates vary by two
     * orders of magnitude: a preview downsampled to 2,000 points puts hundreds of legitimate
     * metres between samples, and a fixed distance threshold would shred it into fragments.
     */
    const val MAX_PLAUSIBLE_SPEED_MPS = 60.0

    /** Used only when the object carries no time channel to reason with. */
    const val MAX_PLAUSIBLE_JUMP_M = 2_000.0

    /**
     * How many vertices the line layer is given.
     *
     * The browser hands MapLibre a GeoJSON object it already holds; here the geometry crosses
     * the JNI boundary as text, and a hundred thousand coordinate pairs is several megabytes of
     * string built on the main thread. Striding above the budget costs shape nobody can see at
     * phone zoom levels and keeps the map from stalling the frame that opens the screen.
     * It affects only what is drawn — every reported number comes from the unstrided channels.
     */
    const val MAX_DRAWN_POINTS = 20_000

    fun geometry(
        lat: DoubleArray?,
        lon: DoubleArray?,
        time: DoubleArray? = null,
        maxPoints: Int = MAX_DRAWN_POINTS,
    ): RouteGeometry {
        if (lat == null || lon == null) return RouteGeometry(emptyList(), null)

        val segments = mutableListOf<RouteSegment>()
        var currentLon = mutableListOf<Double>()
        var currentLat = mutableListOf<Double>()
        var currentStart = 0

        var west = Double.POSITIVE_INFINITY
        var south = Double.POSITIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY
        var previousIndex = -1

        fun closeSegment() {
            // A single orphaned fix draws nothing; keeping it would put a stray dot on the map.
            if (currentLon.size > 1) {
                segments += RouteSegment(
                    lon = currentLon.toDoubleArray(),
                    lat = currentLat.toDoubleArray(),
                    startIndex = currentStart,
                )
            }
            currentLon = mutableListOf()
            currentLat = mutableListOf()
        }

        val count = minOf(lat.size, lon.size)
        for (i in 0 until count) {
            val y = lat[i]
            val x = lon[i]
            if (y.isNaN() || x.isNaN()) {
                closeSegment()
                continue
            }

            if (currentLon.isNotEmpty()) {
                val previousLon = currentLon.last()
                val previousLat = currentLat.last()
                if (implausible(previousLat, previousLon, y, x, previousIndex, i, time)) closeSegment()
            }

            if (currentLon.isEmpty()) currentStart = i
            currentLon += x
            currentLat += y
            previousIndex = i

            if (x < west) west = x
            if (x > east) east = x
            if (y < south) south = y
            if (y > north) north = y
        }
        closeSegment()

        if (segments.isEmpty()) return RouteGeometry(emptyList(), null)
        return RouteGeometry(
            segments = stride(segments, maxPoints),
            bounds = RouteBounds(west = west, south = south, east = east, north = north),
        )
    }

    /**
     * Thins the drawn vertices to a budget, uniformly and per segment.
     *
     * Each segment keeps its first and last vertex whatever the stride, so the gaps between
     * segments — the only thing this geometry exists to preserve — stay exactly where they were.
     */
    private fun stride(segments: List<RouteSegment>, maxPoints: Int): List<RouteSegment> {
        val total = segments.sumOf { it.lon.size }
        if (maxPoints <= 0 || total <= maxPoints) return segments
        val step = (total + maxPoints - 1) / maxPoints

        return segments.map { segment ->
            val kept = (segment.lon.size + step - 1) / step
            val size = if (kept < 2) 2 else kept + 1
            val lon = DoubleArray(size)
            val lat = DoubleArray(size)
            var target = 0
            var source = 0
            while (target < size - 1) {
                lon[target] = segment.lon[source]
                lat[target] = segment.lat[source]
                target++
                source += step
            }
            lon[size - 1] = segment.lon.last()
            lat[size - 1] = segment.lat.last()
            RouteSegment(lon, lat, segment.startIndex)
        }
    }

    private fun implausible(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        fromIndex: Int,
        toIndex: Int,
        time: DoubleArray?,
    ): Boolean {
        val metres = Metrics.haversineMetres(fromLat, fromLon, toLat, toLon)
        if (time == null || fromIndex < 0) return metres > MAX_PLAUSIBLE_JUMP_M

        val seconds = (time.getOrElse(toIndex) { Double.NaN } -
            time.getOrElse(fromIndex) { Double.NaN }) / 1000.0
        if (!seconds.isFinite() || seconds <= 0) return metres > MAX_PLAUSIBLE_JUMP_M
        return metres / seconds > MAX_PLAUSIBLE_SPEED_MPS
    }

    /** Pads a bounding box so a route never touches the edge of the viewport. */
    fun pad(bounds: RouteBounds, fraction: Double = 0.08): RouteBounds {
        // A point activity has zero span; without a floor the map would zoom to infinity.
        val spanX = max(bounds.east - bounds.west, 0.0005)
        val spanY = max(bounds.north - bounds.south, 0.0005)
        return RouteBounds(
            west = bounds.west - spanX * fraction,
            south = bounds.south - spanY * fraction,
            east = bounds.east + spanX * fraction,
            north = bounds.north + spanY * fraction,
        )
    }

    /**
     * The marker position for a sample, as `[lon, lat]`.
     *
     * A sample inside a gap has no position of its own; rather than hiding the marker — which
     * reads as a broken cursor — it holds at the last fix before the gap, which is where the
     * athlete actually was when the signal went.
     */
    fun positionAtOrBefore(lat: DoubleArray?, lon: DoubleArray?, index: Int): DoubleArray? {
        if (lat == null || lon == null || lat.isEmpty()) return null
        var i = minOf(index, lat.size - 1, lon.size - 1)
        while (i >= 0) {
            val y = lat[i]
            val x = lon[i]
            if (!y.isNaN() && !x.isNaN()) return doubleArrayOf(x, y)
            i--
        }
        return null
    }
}
