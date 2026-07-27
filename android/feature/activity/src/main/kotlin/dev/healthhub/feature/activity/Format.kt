package dev.healthhub.feature.activity

import dev.healthhub.core.model.UnitSystem
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Presentation helpers. Nothing here computes a metric — it only renders one.
 *
 * The rounding rules match `web/src/core/format.ts` exactly, because SC-008 is checked by a
 * human reading the phone and the browser side by side: "5.36 km/h" against "5.4 km/h" reads
 * as a disagreement even when the stored number is identical.
 */
internal object Format {

    /** Sports read as speed. The rest read as pace. Same set as the web client. */
    private val SPEED_SPORTS =
        setOf("cycling", "ebiking", "rowing", "swimming", "skiing", "skating")

    fun usesSpeed(sport: String): Boolean = sport in SPEED_SPORTS

    fun distance(metres: Double?, units: UnitSystem): String {
        if (metres == null) return EM_DASH
        return if (units == UnitSystem.IMPERIAL) {
            String.format(Locale.US, "%.2f mi", metres / MILE_M)
        } else {
            String.format(Locale.US, "%.2f km", metres / 1000)
        }
    }

    fun duration(seconds: Double?): String {
        if (seconds == null) return EM_DASH
        val total = seconds.roundToLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    fun duration(seconds: Long?): String = duration(seconds?.toDouble())

    /**
     * Cycling reads as speed, running and walking as pace — showing a runner 12.4 km/h instead
     * of 4:50 /km is technically correct and practically useless.
     */
    fun paceOrSpeed(metresPerSecond: Double?, sport: String, units: UnitSystem): String {
        if (metresPerSecond == null || metresPerSecond <= 0) return EM_DASH

        if (usesSpeed(sport)) {
            val value = if (units == UnitSystem.IMPERIAL) {
                metresPerSecond * MPS_TO_MPH
            } else {
                metresPerSecond * 3.6
            }
            return String.format(
                Locale.US,
                "%.1f %s",
                value,
                if (units == UnitSystem.IMPERIAL) "mph" else "km/h",
            )
        }

        val perUnit = if (units == UnitSystem.IMPERIAL) MILE_M else 1000.0
        val secondsPerUnit = perUnit / metresPerSecond
        val m = floor(secondsPerUnit / 60).toInt()
        val s = (secondsPerUnit % 60).roundToInt()
        // 59.6 s rounds to 60, which has to carry rather than print "7:60".
        val carry = s == 60
        val suffix = if (units == UnitSystem.IMPERIAL) "/mi" else "/km"
        return String.format(
            Locale.US,
            "%d:%02d %s",
            if (carry) m + 1 else m,
            if (carry) 0 else s,
            suffix,
        )
    }

    fun elevation(metres: Double?, units: UnitSystem): String {
        if (metres == null) return EM_DASH
        return if (units == UnitSystem.IMPERIAL) {
            "${(metres * M_TO_FT).roundToInt()} ft"
        } else {
            "${metres.roundToInt()} m"
        }
    }

    fun heartRate(bpm: Double?): String =
        if (bpm == null) EM_DASH else "${bpm.roundToInt()} bpm"

    fun power(watts: Double?): String =
        if (watts == null) EM_DASH else "${watts.roundToInt()} W"

    fun cadence(rpm: Double?): String =
        if (rpm == null) EM_DASH else "${rpm.roundToInt()} rpm"

    private val dateFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())

    /** Rendered in the timezone the workout was recorded in, not the viewer's. */
    fun localDate(startTime: Long, tzOffsetMinutes: Int): String =
        Instant.ofEpochMilli(startTime)
            .atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60))
            .format(dateFormatter)

    fun sportLabel(sport: String): String =
        sport.replaceFirstChar { it.uppercase() }.replace('_', ' ').replace('-', ' ')

    const val EM_DASH = "—"
    const val MILE_M = 1609.344
    private const val MPS_TO_MPH = 2.236936
    private const val M_TO_FT = 3.28084
}
