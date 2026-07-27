package dev.healthhub.feature.activity

import dev.healthhub.core.telemetry.HhtReader

/**
 * The channels of one `.hht`, decoded and index-aligned, plus the distance axis derived from
 * them.
 *
 * A channel the source never recorded is null rather than an array of zeros. That distinction
 * is load-bearing all the way to the screen: an absent channel gets no chart panel at all,
 * because an empty axis reading zero claims a sensor was there and measured nothing.
 */
class TelemetryChannels(
    val count: Int,
    val time: DoubleArray? = null,
    val lat: DoubleArray? = null,
    val lon: DoubleArray? = null,
    val speed: DoubleArray? = null,
    val hr: DoubleArray? = null,
    val power: DoubleArray? = null,
    val cadence: DoubleArray? = null,
    val elevation: DoubleArray? = null,
    val distance: DoubleArray? = null,
    /** True when the object was longer than [MAX_ANALYSIS_SAMPLES] and was strided to fit. */
    val decimated: Boolean = false,
    /** How many samples the object actually held, before any striding. */
    val storedCount: Int = count,
) {

    fun channel(name: String): DoubleArray? = when (name) {
        "speed" -> speed
        "hr" -> hr
        "power" -> power
        "cadence" -> cadence
        "elevation" -> elevation
        else -> null
    }

    companion object {

        /**
         * The most samples a single screen will hold in memory at once.
         *
         * Nine channels of `DoubleArray` at this length is about twelve megabytes, which a
         * mid-range phone can carry while a map holds a GL context. A million-sample object —
         * the size SC-003 says import must survive — would be sixty-four, and the app would be
         * killed for it. Above the budget the object is strided uniformly, every channel by the
         * same stride so indices stay aligned, and the screen says so rather than quietly
         * reporting statistics over a thinner series than the browser is looking at.
         *
         * The threshold sits above the hundred-thousand-sample activity SC-005 benchmarks, so
         * for every workout the spec cares about no striding happens and the two clients are
         * reading exactly the same samples.
         */
        const val MAX_ANALYSIS_SAMPLES = 200_000

        /**
         * Decodes an object one channel at a time.
         *
         * [HhtReader.read] allocates a full-length array per call, so channels are read, strided
         * and released in sequence rather than gathered first — at no point is more than one
         * un-strided channel alive.
         */
        fun decode(bytes: ByteArray, totalDistanceM: Double?): TelemetryChannels {
            val reader = HhtReader(bytes)
            val stored = reader.header.count
            val stride = if (stored > MAX_ANALYSIS_SAMPLES) {
                (stored + MAX_ANALYSIS_SAMPLES - 1) / MAX_ANALYSIS_SAMPLES
            } else {
                1
            }

            fun read(name: String): DoubleArray? {
                if (reader.channel(name) == null) return null
                val full = reader.read(name)
                if (stride == 1) return full
                val out = DoubleArray((full.size + stride - 1) / stride)
                var target = 0
                var source = 0
                while (target < out.size) {
                    out[target] = full[source]
                    target++
                    source += stride
                }
                return out
            }

            val time = read("t")
            val lat = read("lat")
            val lon = read("lon")
            val speed = read("speed")

            return TelemetryChannels(
                count = time?.size ?: lat?.size ?: speed?.size ?: 0,
                time = time,
                lat = lat,
                lon = lon,
                speed = speed,
                hr = read("hr"),
                power = read("power"),
                cadence = read("cadence"),
                elevation = read("elevation"),
                distance = TelemetryAnalysis.cumulativeDistance(time, lat, lon, speed, totalDistanceM),
                decimated = stride > 1,
                storedCount = stored,
            )
        }
    }
}
