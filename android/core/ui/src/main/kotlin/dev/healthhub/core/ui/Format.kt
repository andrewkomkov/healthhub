package dev.healthhub.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.healthhub.core.model.UnitSystem
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The unit suffixes, in the reader's language.
 *
 * A default parameter rather than a required one, and the default is the English set: `Format`
 * is a pure object with no `Context`, its rounding is pinned by `FormatTest` against
 * `web/src/core/format.ts`, and a required argument would have made every one of those
 * assertions carry a language. What a *screen* does is call [unitLabels] and pass the result;
 * what a test does is leave it alone and keep asserting on "41.20 km".
 *
 * The suffixes are the last thing on a screen that was not translated. They matter more than
 * they look: "14:17" and "21:35 /km" beside "Ходьба" is a card in two languages, and the whole
 * point of localising was that a screen should speak one.
 */
data class UnitLabels(
    val kilometres: String = "km",
    val miles: String = "mi",
    val kilometresPerHour: String = "km/h",
    val milesPerHour: String = "mph",
    val perKilometre: String = "/km",
    val perMile: String = "/mi",
    val metres: String = "m",
    val feet: String = "ft",
    val beatsPerMinute: String = "bpm",
    val watts: String = "W",
    val revolutionsPerMinute: String = "rpm",
    val kilocalories: String = "kcal",
) {
    companion object {
        /** What the tests assert against, and what the web client prints. */
        val ENGLISH = UnitLabels()
    }
}

/** The suffixes for the language this composition is being drawn in. */
@Composable
fun unitLabels(): UnitLabels = UnitLabels(
    kilometres = stringResource(R.string.unit_km),
    miles = stringResource(R.string.unit_mi),
    kilometresPerHour = stringResource(R.string.unit_kmh),
    milesPerHour = stringResource(R.string.unit_mph),
    perKilometre = stringResource(R.string.unit_per_km),
    perMile = stringResource(R.string.unit_per_mi),
    metres = stringResource(R.string.unit_m),
    feet = stringResource(R.string.unit_ft),
    beatsPerMinute = stringResource(R.string.unit_bpm),
    watts = stringResource(R.string.unit_w),
    revolutionsPerMinute = stringResource(R.string.unit_rpm),
    kilocalories = stringResource(R.string.unit_kcal),
)

/**
 * How this product writes a number down. One copy, for every screen.
 *
 * There were four. `feature:activity`, `feature:feed`, `feature:sources` and — for its own
 * durations — `feature:health` each carried a version of these rules, because a feature module
 * may not depend on another one and this module had no source. Three copies of a rounding rule
 * is three chances to drift, and one had already taken it: `FeedScreen`'s speed-versus-pace
 * sport set was missing `swimming`, so a swim read "1:23 /km" in the feed and "0.7 km/h" on the
 * screen it opened. That is exactly the disagreement SC-008 forbids, sitting one tap apart.
 *
 * The rounding rules match `web/src/core/format.ts` constant for constant, because SC-008 is
 * checked by a person holding the phone and the browser side by side: "5.36 km/h" against
 * "5.4 km/h" reads as a disagreement even when the stored number is identical.
 *
 * Nothing here computes a metric. It only renders one the phone already computed.
 */
object Format {

    /**
     * Sports read as speed. The rest read as pace.
     *
     * Same set as `web/src/core/format.ts`. Swimming is in it — a swimmer reads 100 m splits,
     * not kilometres, and until the two clients disagree about that the set stays as the web
     * client writes it.
     */
    private val SPEED_SPORTS =
        setOf("cycling", "ebiking", "rowing", "swimming", "skiing", "skating")

    fun usesSpeed(sport: String): Boolean = sport in SPEED_SPORTS

    fun distance(
        metres: Double?,
        units: UnitSystem,
        labels: UnitLabels = UnitLabels.ENGLISH,
    ): String {
        if (metres == null) return EM_DASH
        return if (units == UnitSystem.IMPERIAL) {
            String.format(Locale.US, "%.2f %s", metres / MILE_M, labels.miles)
        } else {
            String.format(Locale.US, "%.2f %s", metres / 1000, labels.kilometres)
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
     * "7h 42m". A night is read in hours and minutes, never as 27,720 seconds.
     *
     * The workout durations above are a stopwatch and read like one; a sleep duration is a
     * quantity and reads like one. Two renderings of the same unit, deliberately.
     */
    fun hoursAndMinutes(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /**
     * Cycling reads as speed, running and walking as pace — showing a runner 12.4 km/h instead
     * of 4:50 /km is technically correct and practically useless.
     */
    fun paceOrSpeed(
        metresPerSecond: Double?,
        sport: String,
        units: UnitSystem,
        labels: UnitLabels = UnitLabels.ENGLISH,
    ): String {
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
                if (units == UnitSystem.IMPERIAL) labels.milesPerHour else labels.kilometresPerHour,
            )
        }

        val perUnit = if (units == UnitSystem.IMPERIAL) MILE_M else 1000.0
        val secondsPerUnit = perUnit / metresPerSecond
        val m = floor(secondsPerUnit / 60).toInt()
        val s = (secondsPerUnit % 60).roundToInt()
        // 59.6 s rounds to 60, which has to carry rather than print "7:60".
        val carry = s == 60
        val suffix = if (units == UnitSystem.IMPERIAL) labels.perMile else labels.perKilometre
        return String.format(
            Locale.US,
            "%d:%02d %s",
            if (carry) m + 1 else m,
            if (carry) 0 else s,
            suffix,
        )
    }

    fun elevation(
        metres: Double?,
        units: UnitSystem,
        labels: UnitLabels = UnitLabels.ENGLISH,
    ): String {
        if (metres == null) return EM_DASH
        return if (units == UnitSystem.IMPERIAL) {
            "${(metres * M_TO_FT).roundToInt()} ${labels.feet}"
        } else {
            "${metres.roundToInt()} ${labels.metres}"
        }
    }

    fun heartRate(bpm: Double?, labels: UnitLabels = UnitLabels.ENGLISH): String =
        if (bpm == null) EM_DASH else "${bpm.roundToInt()} ${labels.beatsPerMinute}"

    fun power(watts: Double?, labels: UnitLabels = UnitLabels.ENGLISH): String =
        if (watts == null) EM_DASH else "${watts.roundToInt()} ${labels.watts}"

    fun cadence(rpm: Double?, labels: UnitLabels = UnitLabels.ENGLISH): String =
        if (rpm == null) EM_DASH else "${rpm.roundToInt()} ${labels.revolutionsPerMinute}"

    fun calories(kcal: Double?, labels: UnitLabels = UnitLabels.ENGLISH): String =
        if (kcal == null) EM_DASH else "${kcal.roundToInt()} ${labels.kilocalories}"

    /**
     * The date formatter for whatever language the phone is set to *now*.
     *
     * Not a `val`. `Locale.getDefault()` read once at class-load freezes the language for the
     * life of the process, so an athlete who switches their phone to another language sees
     * English weekday names until something kills the app — and nothing on the screen explains
     * why. Cached by locale so the common case is still one lookup rather than a parse.
     */
    @Volatile
    private var cached: Pair<Locale, DateTimeFormatter>? = null

    private fun dateFormatter(): DateTimeFormatter {
        val locale = Locale.getDefault()
        cached?.let { (cachedLocale, formatter) -> if (cachedLocale == locale) return formatter }
        val formatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", locale)
        cached = locale to formatter
        return formatter
    }

    /** Rendered in the timezone the workout was recorded in, not the viewer's. */
    fun localDate(startTime: Long, tzOffsetMinutes: Int): String =
        Instant.ofEpochMilli(startTime)
            .atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60))
            .format(dateFormatter())

    /** The clock the athlete saw, from the offset stored beside the measurement. */
    fun timeOfDay(epochMs: Long, tzOffsetMinutes: Int): String {
        val local = Instant.ofEpochMilli(epochMs)
            .atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60))
        return String.format(Locale.US, "%02d:%02d", local.hour, local.minute)
    }

    fun sportLabel(sport: String): String =
        sport.replaceFirstChar { it.uppercase() }.replace('_', ' ').replace('-', ' ')

    /**
     * The line under a card's headline.
     *
     * Health Connect writes no title of its own, so the sport is stored as one and a naive
     * join reads "Walking · Walking". The title earns its own words only when somebody — the
     * athlete, or the app that recorded it — actually wrote one.
     */
    fun sportAndTitle(sport: String, title: String): String {
        val label = sportLabel(sport)
        return if (title.isBlank() || title.equals(sport, ignoreCase = true)) {
            label
        } else {
            "$label · $title"
        }
    }

    const val EM_DASH = "—"
    const val MILE_M = 1609.344
    private const val MPS_TO_MPH = 2.236936
    private const val M_TO_FT = 3.28084
}
