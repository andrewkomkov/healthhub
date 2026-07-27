package dev.healthhub.core.sync

import androidx.health.connect.client.records.ExerciseSessionRecord

/**
 * Works out which sessions are the same workout seen through different apps.
 *
 * Health Connect is a hub. One ride can arrive from Strava, from Samsung Health, from Google
 * Fit and from the bike computer's own app — four sessions, four ids, four slightly different
 * distances, and the athlete rode once. Showing all four is the single most obvious way this
 * product could look broken.
 *
 * Grouping happens here, on the phone, because this is where every candidate session is
 * already in memory (Principle I). The edge stores the verdict and never re-derives it.
 */
object SessionGrouping {

    /**
     * Two sessions of the same sport whose time ranges overlap by at least this much of the
     * shorter one are the same workout.
     *
     * Set at 70% deliberately: apps disagree about when a workout starts — one begins at the
     * first pedal stroke, another when the athlete pressed a button in the car park — so
     * demanding near-identical ranges misses real duplicates. Going much lower starts
     * swallowing genuinely separate back-to-back sessions.
     */
    private const val OVERLAP_THRESHOLD = 0.7

    data class Verdict(
        /** The `sourceUid` of the session chosen to represent this workout, or null if this is it. */
        val duplicateOf: String?,
        /** How many sources reported this workout, including this one. */
        val sourceCount: Int,
    )

    /** Maps every session's `sourceUid` to its verdict. */
    fun classify(
        sessions: List<ExerciseSessionRecord>,
        /** Package name to priority, lower first. Absent sources sort last. */
        sourcePriority: Map<String, Int> = emptyMap(),
        /** Sources the athlete switched off: never chosen to represent a workout. */
        disabledSources: Set<String> = emptySet(),
    ): Map<String, Verdict> {
        val ordered = sessions.sortedBy { it.startTime }
        val clusters = mutableListOf<MutableList<ExerciseSessionRecord>>()

        for (session in ordered) {
            val cluster = clusters.firstOrNull { existing ->
                existing.any { it.exerciseType == session.exerciseType && overlaps(it, session) }
            }
            if (cluster != null) cluster += session else clusters += mutableListOf(session)
        }

        val verdicts = mutableMapOf<String, Verdict>()
        for (cluster in clusters) {
            // The athlete's trust order wins when they have expressed one; the content
            // heuristic only breaks ties between sources they rank equally.
            val representative = cluster.maxWith(
                compareBy<ExerciseSessionRecord> {
                    if (it.metadata.dataOrigin.packageName in disabledSources) 0 else 1
                }
                    .thenByDescending {
                        sourcePriority[it.metadata.dataOrigin.packageName] ?: Int.MAX_VALUE
                    }
                    .then(richness),
            )
            for (session in cluster) {
                verdicts[session.metadata.id] = Verdict(
                    duplicateOf = if (session.metadata.id == representative.metadata.id) {
                        null
                    } else {
                        representative.metadata.id
                    },
                    sourceCount = cluster.size,
                )
            }
        }
        return verdicts
    }

    private fun overlaps(a: ExerciseSessionRecord, b: ExerciseSessionRecord): Boolean {
        val start = maxOf(a.startTime, b.startTime)
        val end = minOf(a.endTime, b.endTime)
        val overlapMs = end.toEpochMilli() - start.toEpochMilli()
        if (overlapMs <= 0) return false

        val shorter = minOf(
            a.endTime.toEpochMilli() - a.startTime.toEpochMilli(),
            b.endTime.toEpochMilli() - b.startTime.toEpochMilli(),
        )
        if (shorter <= 0) return false
        return overlapMs.toDouble() / shorter >= OVERLAP_THRESHOLD
    }

    /**
     * Which recording of a workout to keep.
     *
     * Preference order: one that carries a GPS route, then one the athlete or an app actually
     * named ("Заезд Cycplus M2" beats a bare "Cycling"), then the longest. The aim is to keep
     * the version a human would recognise, not merely the first one seen.
     */
    private val richness = compareBy<ExerciseSessionRecord>(
        { if (it.exerciseRouteResult is androidx.health.connect.client.records.ExerciseRouteResult.Data) 1 else 0 },
        { if (!it.title.isNullOrBlank()) 1 else 0 },
        { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() },
    )
}
