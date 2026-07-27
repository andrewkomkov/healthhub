package dev.healthhub.core.telemetry

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The rule that keeps a hub from reporting fiction.
 *
 * Health Connect hands the same ride over from several apps, and a route imported one activity
 * at a time covers only what its own recorder saw. Both failures are silent — the number simply
 * comes out wrong — so the check is against a third, independent figure: average speed over
 * elapsed time.
 */
class DistanceReconciliationTest {

    @Test
    fun `a track that matches the speed channel becomes the distance`() {
        // 40 km at 10 m/s for an hour: every figure agrees.
        val verdict = Metrics.reconcileDistance(
            gpsM = 36_000.0,
            recordedM = 35_500.0,
            expectedM = Metrics.expectedDistance(10.0, 3_600.0),
        )

        assertThat(verdict.origin).isEqualTo(Metrics.DistanceOrigin.GPS)
        assertThat(verdict.agreement).isEqualTo(Metrics.DistanceAgreement.AGREES)
        assertThat(verdict.distanceM).isEqualTo(36_000.0)
    }

    @Test
    fun `a partial track does not shrink the ride`() {
        // The imported route covers the last six kilometres of a forty-two kilometre ride —
        // the case that made preferring GPS unconditionally a bug rather than a preference.
        val verdict = Metrics.reconcileDistance(
            gpsM = 6_000.0,
            recordedM = 42_000.0,
            expectedM = Metrics.expectedDistance(11.6, 3_600.0),
        )

        assertThat(verdict.origin).isEqualTo(Metrics.DistanceOrigin.RECORDED)
        assertThat(verdict.distanceM).isEqualTo(42_000.0)
    }

    @Test
    fun `a distance summed across two sources is rejected`() {
        // 89.59 km reported for a ride that was really ~42: two apps, two DistanceRecord sets,
        // one ride. Average speed over elapsed time is what gave it away.
        val verdict = Metrics.reconcileDistance(
            gpsM = null,
            recordedM = 89_590.0,
            expectedM = Metrics.expectedDistance(11.6, 3_600.0),
        )

        assertThat(verdict.agreement).isEqualTo(Metrics.DistanceAgreement.DISAGREES)
        // Still reported — a workout with no distance is a worse answer — but never as verified.
        assertThat(verdict.distanceM).isEqualTo(89_590.0)
    }

    @Test
    fun `without a speed channel the track wins on precision and says the check did not run`() {
        val verdict = Metrics.reconcileDistance(gpsM = 5_000.0, recordedM = 4_800.0, expectedM = null)

        assertThat(verdict.origin).isEqualTo(Metrics.DistanceOrigin.GPS)
        assertThat(verdict.agreement).isEqualTo(Metrics.DistanceAgreement.UNKNOWN)
    }

    @Test
    fun `without a speed channel a partial track still does not shrink the ride`() {
        // Plenty of recorders write a distance aggregate and no speed samples, which is exactly
        // where preferring the imported track unchecked would take 42 km down to 6.
        val verdict = Metrics.reconcileDistance(
            gpsM = 6_000.0,
            recordedM = 42_000.0,
            expectedM = null,
        )

        assertThat(verdict.origin).isEqualTo(Metrics.DistanceOrigin.RECORDED)
        assertThat(verdict.distanceM).isEqualTo(42_000.0)
        // Nothing independent confirmed it, and the screen must not claim otherwise.
        assertThat(verdict.agreement).isEqualTo(Metrics.DistanceAgreement.UNKNOWN)
    }

    @Test
    fun `a track with nothing to compare against is still the distance`() {
        val verdict = Metrics.reconcileDistance(gpsM = 6_000.0, recordedM = null, expectedM = null)

        assertThat(verdict.origin).isEqualTo(Metrics.DistanceOrigin.GPS)
        assertThat(verdict.distanceM).isEqualTo(6_000.0)
    }

    @Test
    fun `a treadmill run keeps the recorded aggregate`() {
        val verdict = Metrics.reconcileDistance(
            gpsM = null,
            recordedM = 10_000.0,
            expectedM = Metrics.expectedDistance(2.8, 3_600.0),
        )

        assertThat(verdict.origin).isEqualTo(Metrics.DistanceOrigin.RECORDED)
        assertThat(verdict.agreement).isEqualTo(Metrics.DistanceAgreement.AGREES)
    }

    @Test
    fun `nothing recorded stays nothing rather than becoming zero`() {
        val verdict = Metrics.reconcileDistance(gpsM = null, recordedM = null, expectedM = 1_000.0)

        assertThat(verdict.distanceM).isNull()
        assertThat(verdict.origin).isEqualTo(Metrics.DistanceOrigin.NONE)
    }

    @Test
    fun `zero is not a measurement`() {
        val verdict = Metrics.reconcileDistance(gpsM = 0.0, recordedM = 8_000.0, expectedM = 8_100.0)

        assertThat(verdict.origin).isEqualTo(Metrics.DistanceOrigin.RECORDED)
        assertThat(verdict.distanceM).isEqualTo(8_000.0)
    }

    @Test
    fun `the accepted band is the one both detail screens use`() {
        assertThat(Metrics.MIN_DISTANCE_CORRECTION).isEqualTo(0.8)
        assertThat(Metrics.MAX_DISTANCE_CORRECTION).isEqualTo(1.25)
    }
}
