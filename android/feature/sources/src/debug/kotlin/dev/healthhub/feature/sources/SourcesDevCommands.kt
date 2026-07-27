package dev.healthhub.feature.sources

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.devcontrol.DevCommand
import dev.healthhub.core.network.HealthHubApi
import dev.healthhub.core.network.SourceDto
import javax.inject.Inject

/**
 * The archive and the source order over ADB (Constitution Principle VIII).
 *
 * The screens open with a deep link, but a deep link cannot assert. These commands drive and
 * observe the same calls the screens make, which is what session 2 step 5 needs: reorder the
 * sources, run a sync, and check that the representative changed and that nothing disappeared.
 *
 * ```
 * URI=content://dev.healthhub.debug.devcontrol
 * adb shell content call --uri $URI --method sources
 * adb shell content call --uri $URI --method sources-order \
 *     --extra order:s:com.wahoofitness.bolt,com.strava --extra disabled:s:com.google.android.apps.fitness
 * adb shell content call --uri $URI --method archive
 * adb shell content call --uri $URI --method restore --extra id:s:<activityId>
 * adb shell content call --uri $URI --method set-aside --extra id:s:<activityId>
 * ```
 *
 * Contributed from this module's debug source set, so `feature:sources` still attaches without
 * a file outside it changing, and the release build contains none of it.
 */
class SourcesListCommand @Inject constructor(
    private val api: HealthHubApi,
) : DevCommand {

    override val name = "sources"
    override val usage = "— the trust order as the sources screen draws it, most trusted first"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val sources = api.sources()
        return buildMap {
            put("count", sources.size.toString())
            put("order", sources.joinToString(",") { it.packageName })
            put("disabled", sources.filter { !it.enabled }.joinToString(",") { it.packageName })
            sources.forEachIndexed { index, source ->
                put(
                    "source.$index",
                    "${source.packageName}|${Labels.sourceLabel(source.packageName, source.label)}" +
                        "|priority=${source.priority}|enabled=${source.enabled}" +
                        "|activities=${source.activityCount}",
                )
            }
        }
    }
}

/**
 * Writes a whole trust order.
 *
 * `order` names the packages to hoist, most trusted first; anything the athlete has that is not
 * named keeps its current relative position underneath. The whole list is always sent — a
 * partial write would leave two sources claiming the same rank.
 */
class SourcesOrderCommand @Inject constructor(
    private val api: HealthHubApi,
    private val repository: SourcesRepository,
) : DevCommand {

    override val name = "sources-order"
    override val usage =
        "[--extra order:s:<pkg,pkg,…>] [--extra disabled:s:<pkg,pkg,…>] — stores the priority " +
            "order; unnamed sources keep their relative position below the named ones, so " +
            "`disabled` on its own turns a source off without disturbing the order"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val current = api.sources()
        val requested = args["order"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val disabled = args["disabled"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val unknown = requested.filter { pkg -> current.none { it.packageName == pkg } }
        require(unknown.isEmpty()) {
            "Unknown source(s): ${unknown.joinToString(",")}. Sources are discovered by a sync, " +
                "never created here."
        }

        val hoisted = requested.mapNotNull { pkg -> current.firstOrNull { it.packageName == pkg } }
        val rest = current.filter { it.packageName !in requested }
        val next = (hoisted + rest).map { source ->
            if (source.packageName in disabled) source.copy(enabled = false) else source
        }

        repository.putOrder(next.asOrderEntries())

        val stored = api.sources()
        return mapOf(
            "requested" to next.joinToString(",") { it.packageName },
            "stored" to stored.joinToString(",") { it.packageName },
            "disabled" to stored.filter { !it.enabled }.joinToString(",") { it.packageName },
            "applied" to stored.orderMatches(next).toString(),
        )
    }

    /** Read back from the server rather than assumed: the point of the command is the proof. */
    private fun List<SourceDto>.orderMatches(expected: List<SourceDto>): Boolean =
        orderChanged(this, expected) { it.packageName }.not()
}

/** The archive screen's list, as a script can read it. */
class ArchiveListCommand @Inject constructor(
    private val repository: SourcesRepository,
) : DevCommand {

    override val name = "archive"
    override val usage =
        "[--extra cursor:s:<cursor>] [--extra limit:s:<n>] — the recordings that are set aside, " +
            "with why and whether the athlete's decision is locked"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val page = repository.archivePage(
            cursor = args["cursor"],
            limit = args["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT,
        )
        return buildMap {
            put("count", page.activities.size.toString())
            put("nextCursor", page.nextCursor.orEmpty())
            page.activities.forEachIndexed { index, activity ->
                put(
                    "activity.$index",
                    "${activity.id}|${activity.sport}|${activity.title}" +
                        "|source=${activity.sourcePackage.orEmpty()}" +
                        "|sourceCount=${activity.sourceCount}" +
                        "|reason=${activity.archivedReason.orEmpty()}" +
                        "|locked=${activity.visibilityLocked}",
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 30
    }
}

/**
 * Puts one recording back in the feed.
 *
 * `locked` in the result is the figure that matters to the device-QA pass: it is what the
 * Worker's `ON CONFLICT` clause reads on the next sync to leave the athlete's choice alone.
 * A restore that reports `locked=false` did not take, however right the feed looks afterwards.
 */
class RestoreCommand @Inject constructor(
    private val repository: SourcesRepository,
) : DevCommand {

    override val name = "restore"
    override val usage =
        "--extra id:s:<activityId> — puts a recording back in the feed and locks that decision " +
            "against every later sync"

    override suspend fun run(args: Map<String, String>): Map<String, String> =
        repository.reportVisibility(args, ArchivedActivityDto.ACTIVE)
}

/** Sets one recording aside by hand. It moves to the archive with everything it measured. */
class SetAsideCommand @Inject constructor(
    private val repository: SourcesRepository,
) : DevCommand {

    override val name = "set-aside"
    override val usage =
        "--extra id:s:<activityId> — moves a recording to the archive by hand and locks that " +
            "decision; the row and both telemetry objects stay exactly where they are"

    override suspend fun run(args: Map<String, String>): Map<String, String> =
        repository.reportVisibility(args, ArchivedActivityDto.ARCHIVED)
}

private suspend fun SourcesRepository.reportVisibility(
    args: Map<String, String>,
    visibility: String,
): Map<String, String> {
    val id = requireNotNull(args["id"]) { "id is required" }
    val updated = setVisibility(id, visibility)
    return mapOf(
        "id" to updated.id,
        "title" to updated.title,
        "visibility" to updated.visibility,
        "locked" to updated.visibilityLocked.toString(),
        "reason" to updated.archivedReason.orEmpty(),
        "sourceCount" to updated.sourceCount.toString(),
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SourcesDevCommandsModule {

    @Binds
    @IntoSet
    abstract fun bindSourcesList(command: SourcesListCommand): DevCommand

    @Binds
    @IntoSet
    abstract fun bindSourcesOrder(command: SourcesOrderCommand): DevCommand

    @Binds
    @IntoSet
    abstract fun bindArchiveList(command: ArchiveListCommand): DevCommand

    @Binds
    @IntoSet
    abstract fun bindRestore(command: RestoreCommand): DevCommand

    @Binds
    @IntoSet
    abstract fun bindSetAside(command: SetAsideCommand): DevCommand
}
