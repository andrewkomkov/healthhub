package dev.healthhub.feature.activity

/**
 * A channel reduced to one column per pixel, ready to be drawn.
 *
 * A five-hour ride is tens of thousands of samples and a phone chart is a few hundred pixels
 * wide, so drawing every sample means emitting several segments per pixel every frame while the
 * athlete's finger is on the screen. Reducing to the *minimum and maximum* in each column
 * instead of picking one sample per column is what keeps a one-second heart-rate spike visible
 * after the reduction — an every-nth-sample thinning would drop it.
 *
 * Columns are found by walking the x axis forward once, so this works for the distance axis
 * (irregularly spaced) exactly as it does for time.
 */
internal class ChartSeries(
    val columns: Int,
    /** Fraction of the x span at the centre of each column, 0..1. */
    val at: FloatArray,
    val low: FloatArray,
    val high: FloatArray,
    /** False where no sample in the column recorded a value — a gap, drawn as a gap. */
    val present: BooleanArray,
    val min: Double,
    val max: Double,
    /**
     * The range the axis is drawn against, which is not the same as [min]..[max].
     *
     * One sample of near-zero speed — a walk pausing at a crossing — is a pace of 122 minutes per
     * kilometre, and scaling to it squashed the entire workout into a sliver along the bottom
     * edge of an otherwise empty panel. The axis is therefore built from a trimmed range and
     * samples outside it are drawn clamped to the edge, so a spike still reads as a line running
     * along the top rather than as a spike that silently vanished. Nothing is dropped; only the
     * scale ignores the tails.
     */
    val displayMin: Double,
    val displayMax: Double,
) {

    val hasData: Boolean get() = min <= max

    companion object {

        fun build(x: DoubleArray, values: DoubleArray, columns: Int): ChartSeries {
            val available = minOf(x.size, values.size)
            // Never more columns than samples. This reduction assumes samples are denser than
            // pixels, which a five-hour ride certainly is — but a walk recorded once a minute is
            // not, and asking for three hundred columns from thirty-six samples leaves most of
            // them empty. Empty reads as a gap, the path breaks at every one of them, and the
            // chart renders as nothing at all with a perfectly correct axis beside it.
            val safeColumns = columns.coerceIn(2, MAX_COLUMNS).coerceAtMost(maxOf(2, available))
            val at = FloatArray(safeColumns)
            val low = FloatArray(safeColumns)
            val high = FloatArray(safeColumns)
            val present = BooleanArray(safeColumns)

            val count = available
            if (count == 0) {
                return ChartSeries(
                    columns = safeColumns,
                    at = at,
                    low = low,
                    high = high,
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

                var columnLow = Double.POSITIVE_INFINITY
                var columnHigh = Double.NEGATIVE_INFINITY
                // The last column takes whatever is left, so a rounding error cannot drop the
                // final samples of the ride off the right-hand edge.
                val last = column == safeColumns - 1
                while (index < count && (last || x[index] < upper)) {
                    val v = values[index]
                    if (!v.isNaN()) {
                        if (v < columnLow) columnLow = v
                        if (v > columnHigh) columnHigh = v
                    }
                    index++
                }

                if (columnLow <= columnHigh) {
                    present[column] = true
                    low[column] = columnLow.toFloat()
                    high[column] = columnHigh.toFloat()
                    if (columnLow < min) min = columnLow
                    if (columnHigh > max) max = columnHigh
                }
            }

            val (displayMin, displayMax) = trimmedRange(low, high, present, min, max)
            return ChartSeries(
                safeColumns,
                at,
                low,
                high,
                present,
                min,
                max,
                displayMin,
                displayMax,
            )
        }

        /**
         * The range with its outliers discounted, for the axis to be drawn against.
         *
         * Tukey fences — a quartile and a half of interquartile range beyond each quartile —
         * rather than a fixed percentage off each end. The shape of the tail is the whole
         * problem and it differs per channel: a walk that stops at four crossings has four
         * near-zero speed samples, not one, so trimming a fixed 2% leaves the axis exactly as
         * stretched as it was. A fence adapts to the distribution instead of guessing at it.
         *
         * Computed over the reduced columns rather than the raw samples: the reduction has
         * already happened, it is at most a couple of thousand numbers, and a column that is
         * *entirely* an outlier is precisely what should be discounted.
         *
         * The fences never widen the range — a channel with no outliers keeps its own bounds —
         * and a distribution too small or too flat to have quartiles worth the name is left
         * alone, because guessing at a tail there distorts more than it fixes.
         */
        private fun trimmedRange(
            low: FloatArray,
            high: FloatArray,
            present: BooleanArray,
            min: Double,
            max: Double,
        ): Pair<Double, Double> {
            val values = ArrayList<Float>(present.size * 2)
            for (i in present.indices) {
                if (!present[i]) continue
                values += low[i]
                values += high[i]
            }
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
         * More columns than this buys nothing: the chart is never that wide, and the arrays are
         * rebuilt whenever the athlete rotates the phone.
         */
        const val MAX_COLUMNS = 1024
    }
}
