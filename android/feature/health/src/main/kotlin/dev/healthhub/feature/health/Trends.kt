package dev.healthhub.feature.health

/** One local calendar day's value for one kind. */
internal data class DayValue(val date: String, val value: Double)

/**
 * Turning a list of readings into a trend — on the device, because that is where analysis lives.
 *
 * Two decisions are worth stating rather than reading out of the code:
 *
 *  - **A day is one value.** Health Connect is a hub, so a watch and a phone app both report
 *    this morning's resting heart rate and both rows are stored. They are collapsed by taking
 *    the day's median rather than by summing — the workout rule against summing across sources
 *    is the same rule, and a chart that added two apps' readings would draw a resting pulse of
 *    96 for someone whose pulse is 48.
 *  - **A baseline is a median, not a mean.** One bad night's HRV reading is exactly the kind of
 *    outlier a mean drags the baseline towards, which then makes the *next* day look fine.
 */
internal object Trends {

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    /**
     * One point per local day, oldest first.
     *
     * The edge already computed `localDate` from the reading's own timezone offset, so a trip
     * across two timezones does not fold two mornings into one column.
     */
    fun daily(measurements: List<MeasurementDto>): List<DayValue> = measurements
        .filter { it.localDate.isNotEmpty() }
        .groupBy { it.localDate }
        .mapNotNull { (date, readings) -> median(readings.map { it.value })?.let { DayValue(date, it) } }
        .sortedBy { it.date }

    /** The most recent day that has a value. */
    fun latest(days: List<DayValue>): DayValue? = days.lastOrNull()

    /**
     * The typical value over [window] days, ignoring the most recent [excludingLast].
     *
     * Today is excluded from its own baseline: comparing a reading against a window that
     * contains it pulls the comparison towards zero difference exactly when the difference is
     * the thing being measured.
     */
    fun baseline(days: List<DayValue>, window: Int, excludingLast: Int = 1): Double? {
        val usable = days.dropLast(excludingLast)
        if (usable.size < MIN_BASELINE_DAYS) return null
        return median(usable.takeLast(window).map { it.value })
    }

    /** How far today sits from the baseline, as a percentage. Null when either is missing. */
    fun deviationPercent(today: Double?, baseline: Double?): Double? {
        if (today == null || baseline == null || baseline == 0.0) return null
        return (today - baseline) / baseline * 100
    }

    /**
     * Fewer than this many days is not a baseline, it is a coincidence.
     *
     * Showing a readiness score against three readings would be the app inventing confidence it
     * does not have, which is worse than saying it is still learning.
     */
    const val MIN_BASELINE_DAYS = 7
}
