package dev.healthhub.feature.health

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.devcontrol.DevCommand
import dev.healthhub.core.healthconnect.HealthConnectSource
import dev.healthhub.core.healthconnect.HealthDomain
import dev.healthhub.core.healthconnect.HealthFeatures
import dev.healthhub.core.healthconnect.HealthRecordRegistry
import dev.healthhub.core.sync.HealthRecordSync
import java.time.Instant
import javax.inject.Inject

/**
 * Sleep, recovery and readiness over ADB (Constitution Principle VIII).
 *
 * These exist because the interesting cases cannot be produced on demand through the glass: a
 * device either holds a night with stage detail or it does not, and the difference between "no
 * baseline yet" and "readiness is broken" is a number nobody can read off a screenshot.
 *
 * ```
 * URI=content://dev.healthhub.debug.devcontrol
 * adb shell content call --uri $URI --method health-domains
 * adb shell content call --uri $URI --method health-enable --extra domain:s:sleep --extra on:s:true
 * adb shell content call --uri $URI --method health-sync --extra days:s:90
 * adb shell content call --uri $URI --method sleep
 * adb shell content call --uri $URI --method readiness
 * ```
 *
 * Contributed from this module's debug source set, so `feature:health` still attaches without a
 * file outside it changing, and the release build contains none of it.
 */

/** Which domains are on, which are granted, and what each one would request. */
class HealthDomainsCommand @Inject constructor(
    private val healthConnect: HealthConnectSource,
    private val features: HealthFeatures,
) : DevCommand {

    override val name = "health-domains"
    override val usage = "— which record domains are on, granted, and what each would request"

    override suspend fun run(args: Map<String, String>): Map<String, String> = buildMap {
        put("availability", healthConnect.availability.name)
        put("enabled", features.enabled.value.joinToString(",") { it.slug })
        put("disabled", features.disabled().joinToString(",") { it.slug })
        put("notIngested", HealthRecordRegistry.notIngested.size.toString())

        HealthDomain.entries.forEach { domain ->
            val missing = runCatching { healthConnect.missingPermissionsFor(domain) }
                .getOrDefault(emptySet())
            put(
                "domain.${domain.slug}",
                "enabled=${features.isEnabled(domain)}|granted=${missing.isEmpty()}" +
                    "|types=${HealthRecordRegistry.forDomains(setOf(domain)).size}" +
                    "|missing=${missing.joinToString(";") { it.substringAfterLast('.') }}",
            )
        }
    }
}

/**
 * Switches a domain on or off.
 *
 * It cannot grant the permission — only the athlete can — so it reports what is still missing
 * rather than pretending the switch was enough.
 */
class HealthEnableCommand @Inject constructor(
    private val healthConnect: HealthConnectSource,
    private val features: HealthFeatures,
) : DevCommand {

    override val name = "health-enable"
    override val usage = "--extra domain:s:<sleep|recovery|body|vitals> --extra on:s:<true|false>"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val slug = requireNotNull(args["domain"]) { "domain is required" }
        val domain = requireNotNull(HealthDomain.fromSlug(slug)) {
            "unknown domain '$slug'; known: ${HealthDomain.entries.joinToString(",") { it.slug }}"
        }
        val on = args["on"]?.toBooleanStrictOrNull() ?: true

        features.setEnabled(domain, on)
        val missing = runCatching { healthConnect.missingPermissionsFor(domain) }
            .getOrDefault(emptySet())

        return mapOf(
            "domain" to domain.slug,
            "enabled" to features.isEnabled(domain).toString(),
            "granted" to missing.isEmpty().toString(),
            "missing" to missing.joinToString(",") { it.substringAfterLast('.') },
        )
    }
}

/** Runs the daily-grain pass alone, and reports what it read, uploaded and skipped. */
class HealthSyncCommand @Inject constructor(
    private val healthRecords: HealthRecordSync,
) : DevCommand {

    override val name = "health-sync"
    override val usage =
        "[--extra days:s:<n>] — sync sleep and measurements; days re-reads that far back"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val to = Instant.now()
        // Re-reading a window is always safe: every upload is idempotent on the Health Connect
        // record id, so a repeat produces updates rather than duplicates.
        val from = to.minusSeconds((args["days"]?.toLongOrNull() ?: DEFAULT_DAYS) * 86_400)

        val result = healthRecords.sync(from, to)
        return mapOf(
            "measurements" to result.measurementsSynced.toString(),
            "nights" to result.nightsSynced.toString(),
            "hypnograms" to result.hypnogramsSynced.toString(),
            "windows" to result.windowsRead.toString(),
            // The number the quota cares about. Eight per session is what exhausted it before.
            "reads" to result.readCalls.toString(),
            "failures" to result.failures.size.toString(),
            "firstFailure" to (result.failures.firstOrNull()?.reason ?: ""),
            "skipped" to result.skipped.joinToString(","),
        )
    }

    private companion object {
        const val DEFAULT_DAYS = 30L
    }
}

/** The nights as the screen draws them, stage totals included. */
class SleepCommand @Inject constructor(
    private val repository: HealthRepository,
) : DevCommand {

    override val name = "sleep"
    override val usage = "[--extra limit:s:<n>] — recent nights with their stage totals"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val nights = repository.nights(args["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT)
        return buildMap {
            put("count", nights.size.toString())
            nights.forEachIndexed { index, night ->
                val stages = night.stages
                    .filterValues { it != null && it > 0 }
                    .entries
                    .joinToString(";") { "${it.key}=${it.value}" }
                put(
                    "night.$index",
                    "${night.localDate}|total=${night.totalSeconds}" +
                        "|inBed=${night.timeInBedSeconds ?: -1}|stages=$stages" +
                        "|hypnogram=${night.hypnogram?.stored == true}" +
                        "|source=${night.sourcePackage ?: ""}",
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 14
    }
}

/**
 * The readiness score and every component that produced it.
 *
 * Printing the components rather than the score alone is the point: a score with no baseline
 * behind it is the failure this command is here to catch.
 */
class ReadinessCommand @Inject constructor(
    private val repository: HealthRepository,
) : DevCommand {

    override val name = "readiness"
    override val usage = "— today's readiness, with the baselines and readings behind it"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val hrv = Trends.daily(
            repository.measurements(HealthRecordRegistry.Kind.HRV_RMSSD, TREND_DAYS),
        )
        val restingHr = Trends.daily(
            repository.measurements(HealthRecordRegistry.Kind.RESTING_HEART_RATE, TREND_DAYS),
        )
        val nights = repository.nights(1)

        val hrvBaseline = Trends.baseline(hrv, BASELINE_DAYS)
        val restingHrBaseline = Trends.baseline(restingHr, BASELINE_DAYS)

        val score = Readiness.of(
            Readiness.Input(
                hrvToday = Trends.latest(hrv)?.value,
                hrvBaseline = hrvBaseline,
                restingHrToday = Trends.latest(restingHr)?.value,
                restingHrBaseline = restingHrBaseline,
                sleptSeconds = nights.firstOrNull()?.totalSeconds,
            ),
        )

        return buildMap {
            put("score", score.value?.toString() ?: "")
            put("note", score.note)
            put("hrvDays", hrv.size.toString())
            put("hrvToday", Trends.latest(hrv)?.value?.toString() ?: "")
            put("hrvBaseline", hrvBaseline?.toString() ?: "")
            put("restingHrDays", restingHr.size.toString())
            put("restingHrToday", Trends.latest(restingHr)?.value?.toString() ?: "")
            put("restingHrBaseline", restingHrBaseline?.toString() ?: "")
            put("sleptSeconds", nights.firstOrNull()?.totalSeconds?.toString() ?: "")
            score.components.forEachIndexed { index, component ->
                put("component.$index", "${component.label}|${component.score}|${component.detail}")
            }
        }
    }

    private companion object {
        const val TREND_DAYS = 90
        const val BASELINE_DAYS = 60
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HealthDevCommandsModule {
    @Binds @IntoSet abstract fun domains(command: HealthDomainsCommand): DevCommand
    @Binds @IntoSet abstract fun enable(command: HealthEnableCommand): DevCommand
    @Binds @IntoSet abstract fun healthSync(command: HealthSyncCommand): DevCommand
    @Binds @IntoSet abstract fun sleep(command: SleepCommand): DevCommand
    @Binds @IntoSet abstract fun readiness(command: ReadinessCommand): DevCommand
}
