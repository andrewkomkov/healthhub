package dev.healthhub.feature.activity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The reduction that lets a five-hour ride draw at sixty frames per second.
 *
 * The case that matters most here is the one the device found: a workout with fewer samples
 * than the chart has pixels. Every test below was written after watching a real 36-sample walk
 * render as a correct axis with no line beside it.
 */
class ChartSeriesTest {

    @Test
    fun `a sparse workout fills every column instead of rendering nothing`() {
        // A 46-minute walk sampled once a minute, asked to fill a 300-pixel chart.
        val x = DoubleArray(36) { it * 77.0 }
        val values = DoubleArray(36) { 1.0 + it % 3 }

        val series = ChartSeries.build(x, values, columns = 300)

        assertThat(series.columns).isEqualTo(36)
        // Not one gap: a column without samples breaks the path, and 264 of them erase the line.
        assertThat(series.present.count { it }).isEqualTo(36)
        assertThat(series.hasData).isTrue()
    }

    @Test
    fun `a dense ride still reduces to the requested width`() {
        val x = DoubleArray(20_000) { it.toDouble() }
        val values = DoubleArray(20_000) { (it % 50).toDouble() }

        val series = ChartSeries.build(x, values, columns = 400)

        assertThat(series.columns).isEqualTo(400)
        assertThat(series.present.all { it }).isTrue()
    }

    @Test
    fun `a spike survives the reduction`() {
        val x = DoubleArray(1000) { it.toDouble() }
        val values = DoubleArray(1000) { 120.0 }
        values[517] = 186.0

        val series = ChartSeries.build(x, values, columns = 100)

        // Picking one sample per column would drop this; taking the column's extremes keeps it.
        assertThat(series.max).isEqualTo(186.0)
    }

    @Test
    fun `a stretch with no recorded value stays a gap`() {
        val x = DoubleArray(40) { it.toDouble() }
        val values = DoubleArray(40) { if (it in 10..19) Double.NaN else 5.0 }

        val series = ChartSeries.build(x, values, columns = 40)

        assertThat(series.present.count { !it }).isEqualTo(10)
        assertThat(series.hasData).isTrue()
    }

    @Test
    fun `a channel that recorded nothing at all reports no data`() {
        val x = DoubleArray(10) { it.toDouble() }
        val values = DoubleArray(10) { Double.NaN }

        assertThat(ChartSeries.build(x, values, columns = 50).hasData).isFalse()
    }

    @Test
    fun `two samples are still drawable`() {
        val series = ChartSeries.build(doubleArrayOf(0.0, 1.0), doubleArrayOf(3.0, 4.0), 300)

        assertThat(series.columns).isEqualTo(2)
        assertThat(series.present.all { it }).isTrue()
    }

    @Test
    fun `one stopped sample does not stretch the axis over the whole walk`() {
        // A walk holding about 1 m per second that pauses once at a crossing. Scaling to the
        // pause squashes the entire recording into a sliver along the bottom edge.
        val x = DoubleArray(60) { it.toDouble() }
        val values = DoubleArray(60) { if (it == 30) 0.004 else 1.0 + (it % 5) * 0.05 }

        val series = ChartSeries.build(x, values, columns = 60)

        assertThat(series.min).isEqualTo(0.004)
        assertThat(series.displayMin).isGreaterThan(0.5)
        // Nothing is discarded — the sample is still there to be drawn, clamped to the edge.
        assertThat(series.hasData).isTrue()
    }

    @Test
    fun `a channel with no outliers keeps its own bounds`() {
        val x = DoubleArray(40) { it.toDouble() }
        val values = DoubleArray(40) { 100.0 + it }

        val series = ChartSeries.build(x, values, columns = 40)

        assertThat(series.displayMin).isEqualTo(series.min)
        assertThat(series.displayMax).isEqualTo(series.max)
    }

    @Test
    fun `a short recording is left alone rather than guessed at`() {
        val x = DoubleArray(6) { it.toDouble() }
        val values = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0, 40.0)

        val series = ChartSeries.build(x, values, columns = 6)

        assertThat(series.displayMax).isEqualTo(40.0)
    }
}
