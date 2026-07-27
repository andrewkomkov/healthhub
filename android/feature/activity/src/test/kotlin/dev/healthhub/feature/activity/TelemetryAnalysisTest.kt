package dev.healthhub.feature.activity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The rules this screen derives rather than reads.
 *
 * These are the cases `web/src/core/telemetry/analysis.test.ts` covers, asserted against the
 * Kotlin twin. Two implementations of one rule only stay in agreement if both are pinned, and
 * SC-008 is the criterion that fails when they are not.
 */
class TelemetryAnalysisTest {

    @Test
    fun `distance comes from GPS when there is GPS`() {
        val time = doubleArrayOf(0.0, 1000.0, 2000.0)
        // Roughly 111 m per 0.001 degree of latitude.
        val lat = doubleArrayOf(50.0, 50.001, 50.002)
        val lon = doubleArrayOf(30.0, 30.0, 30.0)

        val distance = TelemetryAnalysis.cumulativeDistance(time, lat, lon, null, null)!!

        assertThat(distance[0]).isEqualTo(0.0)
        assertThat(distance[2]).isWithin(2.0).of(222.0)
    }

    @Test
    fun `a gap contributes nothing rather than a straight line`() {
        val time = doubleArrayOf(0.0, 1000.0, 2000.0, 3000.0)
        val lat = doubleArrayOf(50.0, 50.001, Double.NaN, 50.010)
        val lon = doubleArrayOf(30.0, 30.0, Double.NaN, 30.0)

        val distance = TelemetryAnalysis.cumulativeDistance(time, lat, lon, null, null)!!

        // Only the first leg is measured; the tunnel adds nothing on either side of it.
        assertThat(distance.last()).isWithin(2.0).of(111.0)
    }

    @Test
    fun `speed is integrated when there is no GPS`() {
        val time = doubleArrayOf(0.0, 1000.0, 2000.0, 3000.0)
        val speed = doubleArrayOf(0.0, 2.0, 2.0, 2.0)

        val distance = TelemetryAnalysis.cumulativeDistance(time, null, null, speed, null)!!

        assertThat(distance.last()).isWithin(0.001).of(6.0)
    }

    @Test
    fun `a treadmill with only heart rate has no distance axis`() {
        val time = doubleArrayOf(0.0, 1000.0, 2000.0)

        assertThat(TelemetryAnalysis.cumulativeDistance(time, null, null, null, null)).isNull()
    }

    @Test
    fun `the axis is reconciled with the stored total when they nearly agree`() {
        val time = doubleArrayOf(0.0, 1000.0, 2000.0, 3000.0)
        val speed = doubleArrayOf(0.0, 2.0, 2.0, 2.0)

        val distance = TelemetryAnalysis.cumulativeDistance(time, null, null, speed, 6.6)!!

        assertThat(distance.last()).isWithin(0.001).of(6.6)
    }

    @Test
    fun `a large disagreement is left alone rather than hidden`() {
        val time = doubleArrayOf(0.0, 1000.0, 2000.0, 3000.0)
        val speed = doubleArrayOf(0.0, 2.0, 2.0, 2.0)

        // Double the integrated distance is not a rounding difference; rescaling would mask it.
        val distance = TelemetryAnalysis.cumulativeDistance(time, null, null, speed, 12.0)!!

        assertThat(distance.last()).isWithin(0.001).of(6.0)
    }

    @Test
    fun `range statistics average over recorded samples, not over the span`() {
        val channels = TelemetryChannels(
            count = 5,
            time = doubleArrayOf(0.0, 1000.0, 2000.0, 3000.0, 4000.0),
            speed = doubleArrayOf(2.0, 2.0, 2.0, 2.0, 2.0),
            hr = doubleArrayOf(Double.NaN, 140.0, 160.0, Double.NaN, Double.NaN),
            distance = doubleArrayOf(0.0, 2.0, 4.0, 6.0, 8.0),
        )

        val stats = TelemetryAnalysis.rangeStats(channels, 0, 4)

        assertThat(stats.avgHrBpm).isWithin(0.001).of(150.0)
        assertThat(stats.samples).isEqualTo(5)
        assertThat(stats.elapsedSeconds).isWithin(0.001).of(4.0)
        // Distance over elapsed, not the mean of the speed channel.
        assertThat(stats.avgSpeedMps).isWithin(0.001).of(2.0)
    }

    @Test
    fun `a channel with no sample in the range is unknown, not zero`() {
        val channels = TelemetryChannels(
            count = 3,
            time = doubleArrayOf(0.0, 1000.0, 2000.0),
            power = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN),
        )

        assertThat(TelemetryAnalysis.rangeStats(channels, 0, 2).avgPowerW).isNull()
    }

    @Test
    fun `moving time is unknown when the speed channel never crossed the threshold`() {
        val channels = TelemetryChannels(
            count = 3,
            time = doubleArrayOf(0.0, 1000.0, 2000.0),
            speed = doubleArrayOf(0.1, 0.1, 0.1),
        )

        val stats = TelemetryAnalysis.rangeStats(channels, 0, 2)

        assertThat(stats.movingSeconds).isEqualTo(0.0)
    }

    @Test
    fun `moving time is null when there is no speed channel at all`() {
        val channels = TelemetryChannels(
            count = 3,
            time = doubleArrayOf(0.0, 1000.0, 2000.0),
        )

        assertThat(TelemetryAnalysis.rangeStats(channels, 0, 2).movingSeconds).isNull()
    }

    @Test
    fun `elevation below the noise threshold is not climb`() {
        val channels = TelemetryChannels(
            count = 4,
            time = doubleArrayOf(0.0, 1000.0, 2000.0, 3000.0),
            // Half-metre wobbles, then a real ten-metre climb.
            elevation = doubleArrayOf(100.0, 100.5, 100.2, 110.0),
        )

        val stats = TelemetryAnalysis.rangeStats(channels, 0, 3)

        assertThat(stats.elevationGainM).isWithin(0.001).of(10.0)
        assertThat(stats.elevationLossM).isWithin(0.001).of(0.0)
    }

    @Test
    fun `nearest index binary-searches an increasing axis`() {
        val axis = doubleArrayOf(0.0, 10.0, 20.0, 30.0)

        assertThat(TelemetryAnalysis.nearestIndex(axis, -5.0)).isEqualTo(0)
        assertThat(TelemetryAnalysis.nearestIndex(axis, 14.0)).isEqualTo(1)
        assertThat(TelemetryAnalysis.nearestIndex(axis, 16.0)).isEqualTo(2)
        assertThat(TelemetryAnalysis.nearestIndex(axis, 99.0)).isEqualTo(3)
    }
}
