package dev.healthhub.feature.health

import kotlin.math.roundToInt

/**
 * Readiness: how last night compares with this athlete's own recent normal.
 *
 * Computed here, on the device, from rows the edge stored verbatim (Principle I). There is no
 * readiness field anywhere on the API and there must not be one — the whole reason this app has
 * no backend is that arithmetic like this runs on the phone that already holds the data.
 *
 * Three deliberate limits, because a number with a colour attached is very easy to over-trust:
 *
 *  - **It is a comparison, not a measurement.** Every component is a ratio against this
 *    athlete's own median, never against a population value. There is no healthy HRV.
 *  - **It refuses to guess.** Below [Trends.MIN_BASELINE_DAYS] days of history there is no
 *    baseline and therefore no score — [Score.value] is null and [Score.note] says what is
 *    still missing, rather than showing 50 out of 100 and looking like an answer.
 *  - **It is not a medical claim** and the screen says so in words next to it.
 */
internal object Readiness {

    data class Input(
        val hrvToday: Double? = null,
        val hrvBaseline: Double? = null,
        val restingHrToday: Double? = null,
        val restingHrBaseline: Double? = null,
        val sleptSeconds: Long? = null,
        val sleepNeedSeconds: Long = DEFAULT_SLEEP_NEED_SECONDS,
    )

    data class Component(
        val label: String,
        /** 0–100. */
        val score: Double,
        val weight: Double,
        /** What the number came from, in the athlete's own terms. */
        val detail: String,
    )

    data class Score(
        /** 0–100, or null when there is not enough history to compare against. */
        val value: Int?,
        val components: List<Component>,
        val note: String,
    )

    fun of(input: Input): Score {
        val components = buildList {
            // Heart-rate variability carries the most weight: it is the earliest of the three to
            // move, which is the only reason a morning number is worth reading at all.
            component(
                label = "Heart-rate variability",
                today = input.hrvToday,
                baseline = input.hrvBaseline,
                weight = HRV_WEIGHT,
                score = { ratio -> ramp(ratio, HRV_LOW, HRV_HIGH) },
                detail = { today, baseline ->
                    "${today.roundToInt()} ms against a normal of ${baseline.roundToInt()} ms"
                },
            )?.let { add(it) }

            component(
                label = "Resting heart rate",
                today = input.restingHrToday,
                baseline = input.restingHrBaseline,
                weight = RESTING_HR_WEIGHT,
                // Inverted on purpose: a resting pulse *above* normal is the tired direction.
                score = { ratio -> ramp(ratio, RESTING_HR_HIGH, RESTING_HR_LOW) },
                detail = { today, baseline ->
                    "${today.roundToInt()} bpm against a normal of ${baseline.roundToInt()} bpm"
                },
            )?.let { add(it) }

            if (input.sleptSeconds != null && input.sleepNeedSeconds > 0) {
                val ratio = input.sleptSeconds.toDouble() / input.sleepNeedSeconds
                add(
                    Component(
                        label = "Sleep",
                        score = ramp(ratio, SLEEP_LOW, SLEEP_FULL) * 100,
                        weight = SLEEP_WEIGHT,
                        detail = "${formatHours(input.sleptSeconds)} of " +
                            "${formatHours(input.sleepNeedSeconds)}",
                    ),
                )
            }
        }

        if (components.isEmpty()) {
            return Score(
                value = null,
                components = emptyList(),
                note = "Readiness needs a week of heart-rate variability, resting heart rate or " +
                    "sleep before it can compare today with your normal.",
            )
        }

        // Weighted over whatever is present rather than penalising what is absent: an athlete
        // whose watch reports sleep but no HRV should still get a sleep-shaped answer instead
        // of a third of one.
        val totalWeight = components.sumOf { it.weight }
        val value = components.sumOf { it.score * it.weight } / totalWeight

        return Score(
            value = value.roundToInt().coerceIn(0, 100),
            components = components,
            note = missingNote(components),
        )
    }

    private fun component(
        label: String,
        today: Double?,
        baseline: Double?,
        weight: Double,
        score: (Double) -> Double,
        detail: (Double, Double) -> String,
    ): Component? {
        if (today == null || baseline == null || baseline <= 0.0) return null
        return Component(
            label = label,
            score = score(today / baseline) * 100,
            weight = weight,
            detail = detail(today, baseline),
        )
    }

    private fun missingNote(components: List<Component>): String {
        val present = components.map { it.label }.toSet()
        val missing = ALL_LABELS.filterNot { it in present }
        return if (missing.isEmpty()) {
            "Compared with your own median over the last few weeks. Not a medical assessment."
        } else {
            "Based on ${present.joinToString(" and ") { it.lowercase() }}. " +
                "No baseline yet for ${missing.joinToString(" or ") { it.lowercase() }}. " +
                "Not a medical assessment."
        }
    }

    /**
     * A linear ramp between "as bad as this gets" and "as good as this gets", clamped.
     *
     * Works in both directions: pass [at100] below [at0] for a quantity where lower is better,
     * which is what resting heart rate needs.
     */
    private fun ramp(value: Double, at0: Double, at100: Double): Double =
        ((value - at0) / (at100 - at0)).coerceIn(0.0, 1.0)

    private fun formatHours(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /*
     * The bands. Chosen so that "exactly your normal" lands around two thirds rather than at
     * the top: a day that matches the baseline is an ordinary day, not a personal best, and a
     * scale whose midpoint is unreachable is a scale nobody trusts twice.
     */
    private const val HRV_LOW = 0.80
    private const val HRV_HIGH = 1.10
    private const val RESTING_HR_HIGH = 1.06
    private const val RESTING_HR_LOW = 0.94
    private const val SLEEP_LOW = 0.50
    private const val SLEEP_FULL = 1.00

    private const val HRV_WEIGHT = 0.4
    private const val RESTING_HR_WEIGHT = 0.3
    private const val SLEEP_WEIGHT = 0.3

    /** Until the athlete can set their own; the roadmap's settings screen is where that lands. */
    const val DEFAULT_SLEEP_NEED_SECONDS = 8L * 3600

    private val ALL_LABELS = listOf("Heart-rate variability", "Resting heart rate", "Sleep")
}
