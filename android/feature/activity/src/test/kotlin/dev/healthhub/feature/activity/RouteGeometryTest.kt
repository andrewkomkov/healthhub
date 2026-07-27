package dev.healthhub.feature.activity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The rule the map exists to respect: a gap in the fix is a gap in the line.
 *
 * Mirrors `web/src/core/map/route.test.ts`. Both are written without a renderer so the rule is
 * checked rather than eyeballed on a screenshot.
 */
class RouteGeometryTest {

    @Test
    fun `a continuous track is one segment`() {
        val lat = doubleArrayOf(50.0, 50.001, 50.002)
        val lon = doubleArrayOf(30.0, 30.001, 30.002)

        val geometry = Route.geometry(lat, lon)

        assertThat(geometry.segments).hasSize(1)
        assertThat(geometry.segments[0].lon).hasLength(3)
        assertThat(geometry.bounds!!.west).isEqualTo(30.0)
        assertThat(geometry.bounds!!.north).isEqualTo(50.002)
    }

    @Test
    fun `a missing fix splits the line rather than bridging it`() {
        val lat = doubleArrayOf(50.0, 50.001, Double.NaN, 50.010, 50.011)
        val lon = doubleArrayOf(30.0, 30.001, Double.NaN, 30.010, 30.011)

        val geometry = Route.geometry(lat, lon)

        assertThat(geometry.segments).hasSize(2)
        assertThat(geometry.segments[0].startIndex).isEqualTo(0)
        assertThat(geometry.segments[1].startIndex).isEqualTo(3)
    }

    @Test
    fun `a jump no athlete could have made is a dropout, not a leg`() {
        // One second per fix. Eleven metres between the ordinary ones, sixteen kilometres across
        // the third: the spike a recorder papers over. Same figures as the TypeScript twin.
        val time = doubleArrayOf(0.0, 1000.0, 2000.0, 3000.0)
        val lat = doubleArrayOf(55.75, 55.7501, 55.9, 55.9001)
        val lon = doubleArrayOf(37.61, 37.6101, 37.61, 37.6101)

        val geometry = Route.geometry(lat, lon, time)

        assertThat(geometry.segments).hasSize(2)
        assertThat(geometry.segments[0].startIndex).isEqualTo(0)
        assertThat(geometry.segments[1].startIndex).isEqualTo(2)
    }

    /**
     * The reason the threshold is a speed and not a distance: a preview downsampled to 2,000
     * points puts hundreds of legitimate metres between neighbours, and a fixed metre bound
     * would shred it into fragments.
     */
    @Test
    fun `a downsampled preview stays in one piece`() {
        val time = doubleArrayOf(0.0, 9000.0, 18000.0, 27000.0)
        val lat = doubleArrayOf(55.75, 55.7522, 55.7544, 55.7566)
        val lon = doubleArrayOf(37.61, 37.61, 37.61, 37.61)

        assertThat(Route.geometry(lat, lon, time).segments).hasSize(1)
    }

    @Test
    fun `a lone fix draws nothing`() {
        val lat = doubleArrayOf(Double.NaN, 50.0, Double.NaN)
        val lon = doubleArrayOf(Double.NaN, 30.0, Double.NaN)

        assertThat(Route.geometry(lat, lon).segments).isEmpty()
    }

    @Test
    fun `an indoor workout has no geometry at all`() {
        assertThat(Route.geometry(null, null).bounds).isNull()
    }

    @Test
    fun `striding keeps the ends of every segment`() {
        val size = 5_000
        val lat = DoubleArray(size) { 50.0 + it * 0.00001 }
        val lon = DoubleArray(size) { 30.0 + it * 0.00001 }

        val geometry = Route.geometry(lat, lon, maxPoints = 100)
        val segment = geometry.segments.single()

        assertThat(segment.lon.size).isAtMost(102)
        assertThat(segment.lat[0]).isEqualTo(lat[0])
        assertThat(segment.lat.last()).isEqualTo(lat.last())
    }

    @Test
    fun `padding gives a point activity a viewport instead of infinite zoom`() {
        val padded = Route.pad(RouteBounds(west = 30.0, south = 50.0, east = 30.0, north = 50.0))

        assertThat(padded.east).isGreaterThan(padded.west)
        assertThat(padded.north).isGreaterThan(padded.south)
    }

    @Test
    fun `the marker holds at the last fix rather than disappearing into a gap`() {
        val lat = doubleArrayOf(50.0, 50.001, Double.NaN, Double.NaN)
        val lon = doubleArrayOf(30.0, 30.001, Double.NaN, Double.NaN)

        val position = Route.positionAtOrBefore(lat, lon, 3)!!

        assertThat(position[0]).isEqualTo(30.001)
        assertThat(position[1]).isEqualTo(50.001)
    }
}
