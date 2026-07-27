package dev.healthhub.feature.activity

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.devcontrol.DevCommand
import dev.healthhub.core.model.UnitSystem
import javax.inject.Inject

/**
 * The activity detail screen over ADB (Constitution Principle VIII).
 *
 * The screen itself opens with a deep link:
 *
 * ```
 * adb shell am start -a android.intent.action.VIEW -d "healthhub://activity/<id>"
 * ```
 *
 * What a deep link cannot do is report the numbers the screen is showing, and those are exactly
 * what the device-QA pass has to compare against the browser (SC-008). These two commands read
 * the same objects the screen reads and print what it would draw, so a script can diff a phone
 * against a web page without a screenshot:
 *
 * ```
 * URI=content://dev.healthhub.debug.devcontrol
 * adb shell content call --uri $URI --method activity --extra id:s:<activityId>
 * adb shell content call --uri $URI --method activity-range \
 *     --extra id:s:<activityId> --extra from:s:0 --extra to:s:600
 * ```
 *
 * Contributed from this module's debug source set, so `feature:activity` still attaches without
 * any file outside it changing, and the release build contains none of it.
 */
class ActivityDetailCommand @Inject constructor(
    private val repository: ActivityRepository,
) : DevCommand {

    override val name = "activity"
    override val usage =
        "--extra id:s:<activityId> [--extra variant:s:preview|full] — the figures the detail " +
            "screen draws, including which channels got a panel"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val id = requireNotNull(args["id"]) { "id is required" }
        val detail = repository.detail(id)
        val variant = args["variant"] ?: if (detail.telemetry.full) "full" else "preview"
        val channels = loadChannels(detail, variant)

        val units = UnitSystem.METRIC
        return buildMap {
            put("id", detail.id)
            put("sport", detail.sport)
            put("title", detail.title)
            put("distance", Format.distance(detail.distanceM, units))
            put("moving", Format.duration(detail.movingSeconds ?: detail.elapsedSeconds))
            put("elapsed", Format.duration(detail.elapsedSeconds))
            put("avgSpeed", Format.paceOrSpeed(detail.avgSpeedMps, detail.sport, units))
            put("elevationGain", Format.elevation(detail.elevationGainM, units))
            put("avgHr", detail.avgHrBpm?.toString() ?: "")
            put("splits", detail.splits.size.toString())
            put("zones", detail.zones.size.toString())
            put("storedChannels", detail.channels.joinToString(","))
            put("sampleCount", detail.sampleCount.toString())
            put("telemetry", variant)
            put("hasPreview", detail.telemetry.preview.toString())
            put("hasFull", detail.telemetry.full.toString())
            if (channels == null) {
                put("loaded", "false")
            } else {
                put("loaded", "true")
                put("samples", channels.count.toString())
                put("decimated", channels.decimated.toString())
                // Exactly the panels the screen would stack, in stacking order: a channel the
                // source never recorded gets none, and this is how a script sees that.
                put("panels", PANEL_KEYS.filter { channels.channel(it) != null }.joinToString(","))
                put("hasRoute", (channels.lat != null && channels.lon != null).toString())
                put("distanceAxis", (channels.distance != null).toString())
                put(
                    "distanceAxisEnd",
                    channels.distance?.lastOrNull()?.let { Format.distance(it, units) } ?: "",
                )
            }
        }
    }

    private suspend fun loadChannels(
        detail: ActivityDetailDto,
        variant: String,
    ): TelemetryChannels? {
        val bytes = repository.telemetry(detail.id, variant) ?: return null
        return TelemetryChannels.decode(bytes, detail.distanceM)
    }

    private companion object {
        val PANEL_KEYS = listOf("elevation", "speed", "hr", "cadence", "power")
    }
}

/**
 * The drag-selected range, without a finger.
 *
 * The selection is a user action, so Principle VIII says it must be invocable over ADB; it is
 * also the one figure on the screen that is computed rather than read, which makes it the most
 * likely place for the two clients to disagree.
 */
class ActivityRangeCommand @Inject constructor(
    private val repository: ActivityRepository,
) : DevCommand {

    override val name = "activity-range"
    override val usage =
        "--extra id:s:<activityId> --extra from:s:<i> --extra to:s:<i> " +
            "[--extra variant:s:preview|full] — statistics for a sample range, as the drag " +
            "selection reports them"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val id = requireNotNull(args["id"]) { "id is required" }
        val from = requireNotNull(args["from"]?.toIntOrNull()) { "from is required" }
        val to = requireNotNull(args["to"]?.toIntOrNull()) { "to is required" }

        val detail = repository.detail(id)
        val variant = args["variant"] ?: if (detail.telemetry.full) "full" else "preview"
        val bytes = repository.telemetry(detail.id, variant)
            ?: return mapOf("loaded" to "false", "variant" to variant)

        val channels = TelemetryChannels.decode(bytes, detail.distanceM)
        val stats = TelemetryAnalysis.rangeStats(channels, from, to)
        val units = UnitSystem.METRIC

        return mapOf(
            "variant" to variant,
            "from" to stats.from.toString(),
            "to" to stats.to.toString(),
            "samples" to stats.samples.toString(),
            "distance" to Format.distance(stats.distanceM, units),
            "elapsed" to Format.duration(stats.elapsedSeconds),
            "moving" to Format.duration(stats.movingSeconds),
            "avgSpeed" to Format.paceOrSpeed(stats.avgSpeedMps, detail.sport, units),
            "maxSpeed" to Format.paceOrSpeed(stats.maxSpeedMps, detail.sport, units),
            "avgHr" to Format.heartRate(stats.avgHrBpm),
            "maxHr" to Format.heartRate(stats.maxHrBpm),
            "avgPower" to Format.power(stats.avgPowerW),
            "avgCadence" to Format.cadence(stats.avgCadenceRpm),
            "elevationGain" to Format.elevation(stats.elevationGainM, units),
            "elevationLoss" to Format.elevation(stats.elevationLossM, units),
        )
    }
}

/**
 * The route flow, as far as a script can drive it.
 *
 * `route-status` reports which of the four answers the detail screen would show, which is the
 * half of session 3 that has to be verified on a device with real recordings: a workout whose
 * source wrote no track has to come back `absent`, not `consent_required`.
 *
 * ```
 * adb shell content call --uri $URI --method route-status --extra id:s:<activityId>
 * adb shell content call --uri $URI --method route-import --extra id:s:<activityId>
 * ```
 *
 * `route-import` completes only when the platform hands the track over without a confirmation,
 * which is rare by design — the consent screen is an Activity and needs a tap. Anything else
 * returns the state the screen would be sitting in, so the operator knows what to tap.
 */
class RouteImportCommand @Inject constructor(
    private val repository: ActivityRepository,
    private val importer: RouteImporter,
) : DevCommand {

    override val name = "route-status"
    override val usage =
        "--extra id:s:<activityId> [--extra import:s:true] — whether this workout has a GPS " +
            "track to import, and why not when it has none"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val id = requireNotNull(args["id"]) { "id is required" }
        val detail = repository.detail(id)

        val state = if (detail.hasGps) RouteImportState.Hidden else importer.inspect(detail)
        val granted = (state as? RouteImportState.Offered)?.points
        val outcome = if (args["import"].equals("true", ignoreCase = true) && granted != null) {
            importer.import((state as RouteImportState.Offered).sessionId, granted)
        } else {
            state
        }

        return buildMap {
            put("id", detail.id)
            put("hasGps", detail.hasGps.toString())
            put("sourcePackage", detail.sourcePackage.orEmpty())
            put("state", outcome.slug())
            if (outcome is RouteImportState.Offered) put("sessionId", outcome.sessionId)
            if (outcome is RouteImportState.Offered) {
                put("granted", (outcome.points != null).toString())
            }
            if (outcome is RouteImportState.Imported) put("message", outcome.message)
            if (outcome is RouteImportState.Failed) put("message", outcome.message)
        }
    }

    /** Stable slugs, because a script matches on these and prose is not an interface. */
    private fun RouteImportState.slug(): String = when (this) {
        RouteImportState.Hidden -> "already_present"
        RouteImportState.Checking -> "checking"
        is RouteImportState.Offered -> "consent_required"
        RouteImportState.Absent -> "absent"
        RouteImportState.NotOnThisPhone -> "not_on_this_phone"
        RouteImportState.Unsupported -> "unsupported"
        RouteImportState.Archived -> "archived"
        RouteImportState.Importing -> "importing"
        is RouteImportState.Imported -> "imported"
        is RouteImportState.Declined -> "declined"
        is RouteImportState.Failed -> "failed"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ActivityDevCommandsModule {

    @Binds
    @IntoSet
    abstract fun bindDetail(command: ActivityDetailCommand): DevCommand

    @Binds
    @IntoSet
    abstract fun bindRange(command: ActivityRangeCommand): DevCommand

    @Binds
    @IntoSet
    abstract fun bindRouteStatus(command: RouteImportCommand): DevCommand
}
