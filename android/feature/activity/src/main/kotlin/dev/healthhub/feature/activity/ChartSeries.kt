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

            return ChartSeries(safeColumns, at, low, high, present, min, max)
        }

        /**
         * More columns than this buys nothing: the chart is never that wide, and the arrays are
         * rebuilt whenever the athlete rotates the phone.
         */
        const val MAX_COLUMNS = 1024
    }
}
