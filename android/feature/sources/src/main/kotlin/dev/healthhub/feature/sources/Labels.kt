package dev.healthhub.feature.sources

/**
 * The words these two screens use.
 *
 * Kept out of the composables because the phrasing *is* the feature: an athlete opening the
 * archive has to come away certain that nothing was thrown away, only set aside. The
 * accompanying test holds the whole feature to it, and every string matches
 * `web/src/features/archive/labels.ts` — the same promise has to read the same way on both
 * clients or one of them is lying by omission.
 */
internal object Labels {

    /**
     * "Also recorded by N other apps", or nothing at all.
     *
     * `sourceCount` counts every app that recorded the workout, this recording included, so the
     * interesting number is one less. A count of one means no other app saw it — there is
     * nothing to say, and "recorded by 0 other apps" would be noise on every card.
     */
    fun alsoRecordedBy(sourceCount: Int): String? {
        val others = (sourceCount - 1).coerceAtLeast(0)
        return when (others) {
            0 -> null
            1 -> "Also recorded by 1 other app"
            else -> "Also recorded by $others other apps"
        }
    }

    /** Why this recording is set aside, in the athlete's terms rather than the column's. */
    fun archivedBecause(reason: String?): String = when (reason) {
        "duplicate" -> "Set aside automatically — a source you trust more recorded the same workout"
        "manual" -> "You set this one aside yourself"
        else -> "Set aside"
    }

    /**
     * Whether a locked decision is worth mentioning.
     *
     * Locked-and-archived is the athlete's own choice and already explained by
     * [archivedBecause]. Locked matters most on the way back: it is the promise that a restored
     * workout stays restored however many times the phone syncs afterwards.
     */
    fun lockNote(locked: Boolean): String? =
        if (locked) "Your decision, kept through every future sync" else null

    /** "1 activity" / "N activities", for the count a source contributed. */
    fun activityCount(count: Int): String =
        if (count == 1) "1 activity" else "$count activities"

    /**
     * A package name as a person would say it.
     *
     * Identical to `sourceLabel` in `web/src/core/format.ts`: the athlete compares the two
     * screens, and "Wahoofitness" on one and "com.wahoofitness.bolt" on the other reads as two
     * different apps.
     */
    fun sourceLabel(packageName: String?, label: String? = null): String {
        if (!label.isNullOrBlank()) return label
        if (packageName.isNullOrBlank()) return "Unknown app"

        val segments = packageName.split('.').filter { it.isNotEmpty() }
        // Trailing segments like `.app` or `.android` name the platform, not the product.
        val generic = setOf("app", "apps", "android", "mobile", "client")
        val name = segments.lastOrNull { it !in generic }
            ?: segments.lastOrNull()
            ?: packageName
        return name.replaceFirstChar { it.uppercase() }
    }
}
