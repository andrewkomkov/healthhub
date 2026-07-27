package dev.healthhub.core.telemetry

import com.google.common.truth.Truth.assertThat
import dev.healthhub.core.model.DistanceUnit
import dev.healthhub.core.model.ZoneKind
import org.junit.jupiter.api.Test

class MetricsTest {

    @Test
    fun `haversine matches a known distance`() {
        // Moscow Kremlin to Saint Basil's, roughly 700 m.
        val metres = Metrics.haversineMetres(55.7520, 37.6175, 55.7525, 37.6231)
        assertThat(metres).isWithin(30.0).of(355.0)
    }

    @Test
    fun `distance skips gps gaps instead of cutting across them`() {
        val lat = doubleArrayOf(55.0, 55.001, Double.NaN, 55.010)
        val lon = doubleArrayOf(37.0, 37.0, Double.NaN, 37.0)
        val cumulative = Metrics.cumulativeDistance(lat, lon)

        // The first leg counts; the two legs touching the gap contribute nothing, so the
        // total must not include the ~1 km jump across the tunnel.
        assertThat(cumulative.last()).isWithin(5.0).of(111.0)
    }

    @Test
    fun `moving time excludes stops`() {
        val time = doubleArrayOf(0.0, 1000.0, 2000.0, 3000.0, 4000.0)
        val speed = doubleArrayOf(3.0, 3.0, 0.0, 0.0, 3.0)
        assertThat(Metrics.movingSeconds(time, speed)).isEqualTo(2.0)
    }

    @Test
    fun `moving time ignores recording gaps`() {
        // A five-minute pause is the watch being paused, not five minutes of effort.
        val time = doubleArrayOf(0.0, 1000.0, 301_000.0)
        val speed = doubleArrayOf(3.0, 3.0, 3.0)
        assertThat(Metrics.movingSeconds(time, speed)).isEqualTo(1.0)
    }

    @Test
    fun `elevation gain ignores sub-metre jitter`() {
        val elevation = doubleArrayOf(100.0, 100.4, 100.2, 100.6, 100.3)
        val change = Metrics.elevationChange(elevation)
        assertThat(change.gainM).isEqualTo(0.0)
        assertThat(change.lossM).isEqualTo(0.0)
    }

    @Test
    fun `elevation gain counts real climbs and descents separately`() {
        val elevation = doubleArrayOf(100.0, 110.0, 105.0, 120.0)
        val change = Metrics.elevationChange(elevation)
        assertThat(change.gainM).isEqualTo(25.0)
        assertThat(change.lossM).isEqualTo(5.0)
    }

    @Test
    fun `splits land on interpolated kilometre boundaries`() {
        // Ten samples, 200 m apart, one every 100 s — exactly 2 km at a steady 2 m/s.
        val count = 11
        val time = DoubleArray(count) { it * 100_000.0 }
        val distance = DoubleArray(count) { it * 200.0 }

        val splits = Metrics.splits(time, distance, DistanceUnit.KILOMETRE)

        assertThat(splits).hasSize(2)
        assertThat(splits[0].elapsedSeconds).isWithin(0.001).of(500.0)
        assertThat(splits[1].elapsedSeconds).isWithin(0.001).of(500.0)
        assertThat(splits[0].avgSpeedMps!!).isWithin(0.001).of(2.0)
    }

    @Test
    fun `a partial final kilometre is not reported as a split`() {
        val time = DoubleArray(8) { it * 100_000.0 }
        val distance = DoubleArray(8) { it * 200.0 } // 1.4 km
        assertThat(Metrics.splits(time, distance, DistanceUnit.KILOMETRE)).hasSize(1)
    }

    @Test
    fun `zones weight by interval, not by sample count`() {
        // Two samples in zone 1 a second apart, then one sample in zone 2 ten seconds later.
        val time = doubleArrayOf(0.0, 1000.0, 11_000.0)
        val hr = doubleArrayOf(100.0, 100.0, 160.0)
        val zones = Metrics.zones(time, hr, listOf(0.0, 150.0), ZoneKind.HR)

        assertThat(zones).hasSize(2)
        assertThat(zones[0].seconds).isEqualTo(1.0)
        assertThat(zones[1].seconds).isEqualTo(10.0)
    }

    @Test
    fun `mean and max ignore unrecorded samples`() {
        val values = doubleArrayOf(10.0, Double.NaN, 20.0)
        assertThat(Metrics.mean(values)).isEqualTo(15.0)
        assertThat(Metrics.max(values)).isEqualTo(20.0)
        assertThat(Metrics.mean(doubleArrayOf(Double.NaN))).isNull()
    }
}

class RouteTest {

    @Test
    fun `polyline encoding matches the reference vector`() {
        // The vector from Google's polyline documentation; the web decoder is tested against
        // the same one, which is what keeps the two implementations in agreement.
        val points = listOf(
            DoublePair(38.5, -120.2),
            DoublePair(40.7, -120.95),
            DoublePair(43.252, -126.453),
        )
        assertThat(Route.encodePolyline(points)).isEqualTo("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
    }

    @Test
    fun `simplify keeps the endpoints and drops collinear points`() {
        val lat = doubleArrayOf(55.0, 55.001, 55.002, 55.003)
        val lon = doubleArrayOf(37.0, 37.0, 37.0, 37.0)
        val simplified = Route.simplify(lat, lon)

        assertThat(simplified.first()).isEqualTo(DoublePair(55.0, 37.0))
        assertThat(simplified.last()).isEqualTo(DoublePair(55.003, 37.0))
        assertThat(simplified.size).isLessThan(4)
    }

    @Test
    fun `simplify skips samples with no fix`() {
        val lat = doubleArrayOf(55.0, Double.NaN, 55.01)
        val lon = doubleArrayOf(37.0, Double.NaN, 37.01)
        assertThat(Route.simplify(lat, lon)).hasSize(2)
    }

    @Test
    fun `bounds cover every recorded point`() {
        val lat = doubleArrayOf(55.0, 55.5, Double.NaN)
        val lon = doubleArrayOf(37.0, 37.5, Double.NaN)
        val bounds = Route.bounds(lat, lon)!!

        assertThat(bounds.minLat).isEqualTo(55.0)
        assertThat(bounds.maxLat).isEqualTo(55.5)
        assertThat(bounds.maxLon).isEqualTo(37.5)
    }
}

class LttbTest {

    @Test
    fun `downsampling preserves the peak`() {
        val n = 1000
        val x = DoubleArray(n) { it.toDouble() }
        val y = DoubleArray(n) { 0.0 }
        y[500] = 100.0 // a sprint in the middle of an otherwise flat ride

        val indices = Lttb.downsample(x, y, 50)

        assertThat(indices).hasLength(50)
        assertThat(indices.toList()).contains(500)
        assertThat(indices.first()).isEqualTo(0)
        assertThat(indices.last()).isEqualTo(n - 1)
    }

    @Test
    fun `downsampling below the threshold returns everything`() {
        val x = DoubleArray(10) { it.toDouble() }
        assertThat(Lttb.downsample(x, x, 50)).hasLength(10)
    }
}
