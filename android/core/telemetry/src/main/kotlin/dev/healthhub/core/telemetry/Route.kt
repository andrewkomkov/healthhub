package dev.healthhub.core.telemetry

import dev.healthhub.core.model.Bounds
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Route simplification and encoding.
 *
 * The feed needs a route thumbnail for every visible card. Storing the full GPS series for
 * that would mean an R2 read per card; a simplified encoded polyline is a few hundred bytes
 * on the activity row, so the feed stays a single indexed query (research.md R-005).
 */
object Route {

    /** About 5 m at the equator — invisible in a 120 px thumbnail, and it removes ~95% of points. */
    const val DEFAULT_TOLERANCE_DEGREES = 0.00005

    /**
     * Douglas–Peucker simplification.
     *
     * Iterative rather than recursive: a million-point track would blow the stack, and this
     * runs on a phone.
     */
    fun simplify(
        lat: DoubleArray,
        lon: DoubleArray,
        tolerance: Double = DEFAULT_TOLERANCE_DEGREES,
    ): List<DoublePair> {
        val points = buildList {
            for (i in lat.indices) {
                if (!lat[i].isNaN() && !lon[i].isNaN()) add(DoublePair(lat[i], lon[i]))
            }
        }
        if (points.size <= 2) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        val stack = ArrayDeque<IntRange>()
        stack.addLast(0..points.size - 1)

        while (stack.isNotEmpty()) {
            val range = stack.removeLast()
            val first = range.first
            val last = range.last
            if (last <= first + 1) continue

            var farthest = -1
            var farthestDistance = 0.0
            for (i in first + 1 until last) {
                val distance = perpendicularDistance(points[i], points[first], points[last])
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthest = i
                }
            }

            if (farthestDistance > tolerance && farthest > 0) {
                keep[farthest] = true
                stack.addLast(first..farthest)
                stack.addLast(farthest..last)
            }
        }

        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun perpendicularDistance(point: DoublePair, start: DoublePair, end: DoublePair): Double {
        val dx = end.lon - start.lon
        val dy = end.lat - start.lat
        if (dx == 0.0 && dy == 0.0) {
            return max(abs(point.lon - start.lon), abs(point.lat - start.lat))
        }
        val numerator = abs(dy * point.lon - dx * point.lat + end.lon * start.lat - end.lat * start.lon)
        return numerator / kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Google encoded-polyline format, precision 5.
     *
     * The web client's decoder is validated against the same vector as this encoder, so the
     * two cannot drift apart unnoticed.
     */
    fun encodePolyline(points: List<DoublePair>, precision: Int = 5): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val builder = StringBuilder()
        var previousLat = 0L
        var previousLon = 0L

        for (point in points) {
            val lat = (point.lat * factor).roundToLong()
            val lon = (point.lon * factor).roundToLong()
            encodeValue(lat - previousLat, builder)
            encodeValue(lon - previousLon, builder)
            previousLat = lat
            previousLon = lon
        }
        return builder.toString()
    }

    private fun encodeValue(value: Long, builder: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            builder.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        builder.append((v.toInt() + 63).toChar())
    }

    fun bounds(lat: DoubleArray, lon: DoubleArray): Bounds? {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var any = false

        for (i in lat.indices) {
            if (lat[i].isNaN() || lon[i].isNaN()) continue
            any = true
            if (lat[i] < minLat) minLat = lat[i]
            if (lat[i] > maxLat) maxLat = lat[i]
            if (lon[i] < minLon) minLon = lon[i]
            if (lon[i] > maxLon) maxLon = lon[i]
        }
        return if (any) Bounds(minLat, minLon, maxLat, maxLon) else null
    }
}

data class DoublePair(val lat: Double, val lon: Double)

/**
 * Largest-Triangle-Three-Buckets downsampling.
 *
 * Used to build the ~2,000-point preview object that the detail screen paints immediately
 * while the full-resolution telemetry is still arriving. LTTB rather than naive decimation
 * because it preserves peaks — a sprint or a summit survives, where taking every nth sample
 * would flatten it.
 */
object Lttb {
    fun downsample(x: DoubleArray, y: DoubleArray, threshold: Int): IntArray {
        val n = x.size
        if (threshold >= n || threshold < 3) return IntArray(n) { it }

        val sampled = IntArray(threshold)
        var sampledIndex = 0
        val every = (n - 2).toDouble() / (threshold - 2)

        sampled[sampledIndex++] = 0
        var a = 0

        for (i in 0 until threshold - 2) {
            val rangeStart = ((i + 1) * every).toInt() + 1
            val rangeEnd = minOf(((i + 2) * every).toInt() + 1, n)

            var averageX = 0.0
            var averageY = 0.0
            var count = 0
            for (j in rangeStart until rangeEnd) {
                if (y[j].isNaN()) continue
                averageX += x[j]
                averageY += y[j]
                count++
            }
            if (count > 0) {
                averageX /= count
                averageY /= count
            }

            val currentStart = ((i + 0) * every).toInt() + 1
            val currentEnd = minOf(((i + 1) * every).toInt() + 1, n)

            var maxArea = -1.0
            var maxAreaIndex = currentStart
            for (j in currentStart until currentEnd) {
                if (y[j].isNaN()) continue
                val area = abs(
                    (x[a] - averageX) * (y[j] - y[a]) - (x[a] - x[j]) * (averageY - y[a]),
                ) * 0.5
                if (area > maxArea) {
                    maxArea = area
                    maxAreaIndex = j
                }
            }

            sampled[sampledIndex++] = maxAreaIndex
            a = maxAreaIndex
        }

        sampled[sampledIndex] = n - 1
        return sampled
    }
}
