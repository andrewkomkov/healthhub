package dev.healthhub.feature.activity

/**
 * A channel reduced to one point per bucket, ready to be drawn.
 *
 * A five-hour ride is tens of thousands of samples and a phone chart is a few hundred pixels
 * wide, so drawing every sample means emitting several segments per pixel every frame while the
 * athlete's finger is on the screen.
 *
 * The reduction is the **mean of each bucket**, and that is a deliberate reversal. This used to
 * keep each bucket's minimum *and* maximum and draw the envelope between them, on the reasoning
 * that a one-second heart-rate spike must survive — which it did, at the cost that every bucket
 * became a vertical whisker and a dense ride rendered as a brush rather than as a line. Nobody
 * reads a shape out of that. A mean is the line the eye is looking for; the extremes are still
 * reported honestly, in the summary card above and in the range statistics below, which is where
 * a number is read rather than a trend.
 *
 * Buckets are found by walking the x axis forward once, so this works for the distance axis
 * (irregularly spaced) exactly as it does for time.
 */
internal class ChartSeries(
    val columns: Int,
    /** Fraction of the x span at the centre of each bucket, 0..1. */
    val at: FloatArray,
    /** Mean of the samples in each bucket. */
    val value: FloatArray,
    /** Index of a representative sample per bucket, for the readout under the finger. */
    val sample: IntArray,
    /** False where no sample in the bucket recorded a value — a gap, drawn as a gap. */
    val present: BooleanArray,
    val min: Double,
    val max: Double,
    /**
     * The range the axis is drawn against, which is not the same as [min]..[max].
     *
     * One sample of near-zero speed — a walk pausing at a crossing — is a pace of 122 minutes per
     * kilometre, and scaling to it squashed the entire workout into a sliver along the bottom
     * edge of an otherwise empty panel. The axis is therefore built from a trimmed range and
     * points outside it are drawn clamped to the edge, so a peak still reads as a line running
     * along the top rather than as a peak that silently vanished. Nothing is dropped; only the
     * scale ignores the tails.
     */
    val displayMin: Double,
    val displayMax: Double,
    /**
     * The lowest value the axis may reach once [displayMin] has been padded for headroom.
     *
     * The fences alone are not enough for pace. A commute that stands at traffic lights for two
     * fifths of its samples has a *quartile* of stopped speeds, not a tail, so the lower fence
     * sits underneath them and the bottom of the axis still says 122:54 /km. The speed channel
     * passes `TelemetryAnalysis.MOVING_SPEED_THRESHOLD_MPS` here: below it the athlete is
     * stopped, "pace while stopped" is not a quantity, and an axis that reserves half its height
     * for it is spending that height on nothing.
     */
    val axisFloor: Double = Double.NEGATIVE_INFINITY,
) {

    val hasData: Boolean get() = min <= max

    companion object {

        fun build(
            x: DoubleArray,
            values: DoubleArray,
            columns: Int = BUCKETS,
            axisFloor: Double = Double.NEGATIVE_INFINITY,
        ): ChartSeries {
            val available = minOf(x.size, values.size)
            // Never more buckets than samples. The reduction assumes samples are denser than
            // buckets, which a five-hour ride certainly is — but a walk recorded once a minute is
            // not, and asking for two hundred buckets from thirty-six samples leaves most of them
            // empty. Empty reads as a gap, the path breaks at every one of them, and the chart
            // renders as nothing at all with a perfectly correct axis beside it.
            val safeColumns = columns.coerceIn(2, MAX_COLUMNS).coerceAtMost(maxOf(2, available))
            val at = FloatArray(safeColumns)
            val value = FloatArray(safeColumns)
            val sample = IntArray(safeColumns)
            val present = BooleanArray(safeColumns)

            val count = available
            if (count == 0) {
                return ChartSeries(
                    columns = safeColumns,
                    at = at,
                    value = value,
                    sample = sample,
                    present = present,
                    min = Double.POSITIVE_INFINITY,
                    max = Double.NEGATIVE_INFINITY,
                    displayMin = Double.POSITIVE_INFINITY,
                    displayMax = Double.NEGATIVE_INFINITY,
                )
            }

            val xMin = x[0]
            val xMax = x[count - 1]
            val span = if (xMax > xMin) xMax - xMin else 1.0

            var min = Double.POSITIVE_INFINITY
            var max = Double.NEGATIVE_INFINITY

            var index = 0
            for (column in 0 until safeColumns) {
                val upper = xMin + span * (column + 1).toDouble() / safeColumns
                at[column] = (column + 0.5f) / safeColumns

                var sum = 0.0
                var seen = 0
                val from = index
                // The last bucket takes whatever is left, so a rounding error cannot drop the
                // final samples of the ride off the right-hand edge.
                val last = column == safeColumns - 1
                while (index < count && (last || x[index] < upper)) {
                    val v = values[index]
                    if (!v.isNaN()) {
                        sum += v
                        seen++
                    }
                    index++
                }
                // The middle of what the bucket covered, so the readout under the finger names a
                // sample inside the stretch the point was drawn from.
                sample[column] = ((from + maxOf(from, index - 1)) / 2).coerceIn(0, count - 1)

                if (seen > 0) {
                    val mean = sum / seen
                    present[column] = true
                    value[column] = mean.toFloat()
                    if (mean < min) min = mean
                    if (mean > max) max = mean
                }
            }

            val (trimmedMin, displayMax) = trimmedRange(value, present, min, max)
            // Only ever raises the bottom, and only while there is a workout left above it: a
            // channel that never crossed the threshold at all — a trainer session with the wheel
            // speed reading zero — keeps its own range rather than being flattened onto a line.
            val flooring = displayMax > axisFloor
            val displayMin = if (flooring) maxOf(trimmedMin, axisFloor) else trimmedMin
            return ChartSeries(
                safeColumns,
                at,
                value,
                sample,
                present,
                min,
                max,
                displayMin,
                displayMax,
                if (flooring) axisFloor else Double.NEGATIVE_INFINITY,
            )
        }

        /**
         * The range with its outliers discounted, for the axis to be drawn against.
         *
         * Tukey fences — a quartile and a half of interquartile range beyond each quartile —
         * rather than a fixed percentage off each end. The shape of the tail is the whole
         * problem and it differs per channel: a walk that stops at four crossings has four
         * near-zero speed buckets, not one, so trimming a fixed 2% leaves the axis exactly as
         * stretched as it was. A fence adapts to the distribution instead of guessing at it.
         *
         * The fences never widen the range — a channel with no outliers keeps its own bounds —
         * and a distribution too small or too flat to have quartiles worth the name is left
         * alone, because guessing at a tail there distorts more than it fixes.
         */
        private fun trimmedRange(
            value: FloatArray,
            present: BooleanArray,
            min: Double,
            max: Double,
        ): Pair<Double, Double> {
            val values = ArrayList<Float>(present.size)
            for (i in present.indices) if (present[i]) values += value[i]
            if (values.size < MIN_VALUES_TO_TRIM) return min to max

            values.sort()
            val q1 = quantile(values, 0.25f)
            val q3 = quantile(values, 0.75f)
            val iqr = q3 - q1
            if (iqr <= 0) return min to max

            val lowFence = (q1 - FENCE * iqr).coerceAtLeast(min)
            val highFence = (q3 + FENCE * iqr).coerceAtMost(max)
            if (highFence <= lowFence) return min to max
            return lowFence to highFence
        }

        /** Linear interpolation between the two order statistics either side of [fraction]. */
        private fun quantile(sorted: List<Float>, fraction: Float): Double {
            val position = (sorted.size - 1) * fraction
            val lower = position.toInt()
            val upper = (lower + 1).coerceAtMost(sorted.size - 1)
            val weight = position - lower
            return sorted[lower] * (1 - weight).toDouble() + sorted[upper] * weight.toDouble()
        }

        /** Below this there is no distribution to speak of, only a short recording. */
        private const val MIN_VALUES_TO_TRIM = 16

        /** Tukey's constant. 1.5 IQR is the conventional fence for a mild outlier. */
        private const val FENCE = 1.5

        /**
         * Points across the chart. More than this is not distinguishable on a phone and not
         * useful in a browser either; the web client reduces to the same number, because two
         * clients drawing one ride at different smoothings is two different rides (SC-008).
         */
        const val BUCKETS = 220

        /**
         * More buckets than this buys nothing: the chart is never that wide, and the arrays are
         * rebuilt whenever the athlete rotates the phone.
         */
        const val MAX_COLUMNS = 1024
    }
}
