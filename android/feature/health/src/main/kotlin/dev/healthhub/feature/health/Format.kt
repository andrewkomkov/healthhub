package dev.healthhub.feature.health

import dev.healthhub.core.healthconnect.HealthRecordRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The number and date formatting these screens use.
 *
 * A fourth copy of the workout formatter is deliberately *not* what this is — nothing here
 * formats a distance or a pace. These are the sleep and biometric units, which no other screen
 * shows, so they live with the only feature that has them. When `core:ui` finally acquires
 * source, the duration helper is the one line of this file that belongs there.
 */
internal object Format {

    private val dayLabel = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    /** "7h 42m". Sleep is read in hours and minutes, never in seconds or in decimal hours. */
    fun duration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

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
    fun timeOfDay(epochMs: Long, tzOffsetMinutes: Int): String {
        val local = Instant.ofEpochMilli(epochMs)
            .atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60))
        return "%02d:%02d".format(local.hour, local.minute)
    }

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
