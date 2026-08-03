package dev.healthhub.feature.health

import dev.healthhub.core.healthconnect.HealthRecordRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.healthhub.core.ui.Format as Shared

/**
 * The number and date formatting these screens use.
 *
 * A fourth copy of the workout formatter is deliberately *not* what this is — nothing here
 * formats a distance or a pace. These are the sleep and biometric units, which no other screen
 * shows, so they live with the only feature that has them. The two rules that are *not* unique
 * to this feature — a duration in hours and minutes, and a clock reading in the athlete's own
 * offset — now come from `core:ui` and are delegated to below rather than written out again.
 */
internal object Format {

    private val dayLabel = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    /** "7h 42m". Sleep is read in hours and minutes, never in seconds or in decimal hours. */
    fun duration(seconds: Long): String = Shared.hoursAndMinutes(seconds)

    /** The local date the edge already computed, as a label. Falls back to the raw string. */
    fun date(localDate: String): String = runCatching {
        LocalDate.parse(localDate).format(dayLabel)
    }.getOrDefault(localDate)

    /** A measured instant as a day label, in this phone's zone. */
    fun dateOf(epochMs: Long): String = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(dayLabel)

    /** A measured instant in the athlete's own clock, using the offset stored beside it. */
    fun timeOfDay(epochMs: Long, tzOffsetMinutes: Int): String =
        Shared.timeOfDay(epochMs, tzOffsetMinutes)

    fun measurement(reading: LatestReading): String = when (reading.kind) {
        // The one kind that is two numbers. Rendered the way a cuff prints it.
        HealthRecordRegistry.Kind.BLOOD_PRESSURE -> {
            val diastolic = reading.secondaryValue?.roundToInt()?.toString() ?: "—"
            "${reading.value.roundToInt()}/$diastolic"
        }

        HealthRecordRegistry.Kind.WEIGHT -> "%.1f".format(reading.value)
        HealthRecordRegistry.Kind.BODY_FAT, HealthRecordRegistry.Kind.OXYGEN_SATURATION ->
            "%.1f".format(reading.value)

        else -> "%.0f".format(reading.value)
    }

    /**
     * "4% above normal" — direction in words, because a signed percentage means the opposite
     * thing for heart-rate variability and for resting heart rate, and a reader should not have
     * to remember which.
     */
    fun deviation(percent: Double?): String? {
        if (percent == null) return null
        val rounded = percent.roundToInt()
        if (abs(rounded) < 1) return "at your normal"
        return if (rounded > 0) "$rounded% above normal" else "${abs(rounded)}% below normal"
    }
}
