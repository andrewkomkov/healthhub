package dev.healthhub.feature.sources

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.navigation.ActivityAction
import dev.healthhub.core.navigation.ActivityActionConfirmation
import dev.healthhub.core.navigation.ActivityActionProvider
import dev.healthhub.core.navigation.ActivityActionResult
import dev.healthhub.core.navigation.ActivityActionTarget
import dev.healthhub.core.ui.HealthHubIcons
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Archive and restore, offered on the activity detail screen.
 *
 * Session 2 step 4, which was blocked for one structural reason: this module owns the archive,
 * the visibility call and the words that explain them, `feature:activity` owns the detail
 * screen, and a feature module may not depend on another one. Neither half could reach the
 * other, so the action lived on the archive screen and over ADB while the screen the athlete
 * was actually looking at could not offer it.
 *
 * `ActivityActionProvider` is the seam that closes it. Nothing in `feature:activity` names this
 * class, and nothing here names that screen.
 */
@Singleton
class ArchiveActionProvider @Inject constructor(
    private val repository: SourcesRepository,
) : ActivityActionProvider {

    override fun actionsFor(activity: ActivityActionTarget): List<ActivityAction> = when {
        activity.archived -> listOf(
            ActivityAction(
                id = RESTORE,
                label = R.string.action_restore,
                icon = HealthHubIcons.Activities,
                order = 10,
                // No confirmation. Restoring puts a recording back where it was and is
                // reversible by the action that sits beside it; asking twice for something
                // that undoes itself teaches people to dismiss dialogs without reading them.
                confirm = null,
            ),
        )

        else -> listOf(
            ActivityAction(
                id = SET_ASIDE,
                label = R.string.action_set_aside,
                icon = HealthHubIcons.Archive,
                order = 10,
                // The words matter as much as the call. `VocabularyTest` reads every file in
                // this module and fails on a destructive verb, because the archive is the
                // athlete's evidence that nothing is ever thrown away — see `Labels.kt`, and
                // `web/src/features/archive/labels.ts`, which makes the same promise in the
                // same words on the other client.
                confirm = ActivityActionConfirmation(
                    title = R.string.set_aside_title,
                    body = R.string.set_aside_body,
                    confirmLabel = R.string.set_aside_confirm,
                ),
            ),
        )
    }

    /**
     * Writes the decision, and says what the server actually stored.
     *
     * `visibilityLocked` coming back true is the only honest evidence that this survives the
     * next sync rather than merely appearing to, so it is what the message is built from — not
     * from the request that was sent.
     */
    override suspend fun perform(
        actionId: String,
        activity: ActivityActionTarget,
    ): ActivityActionResult {
        val wanted = when (actionId) {
            RESTORE -> "active"
            SET_ASIDE -> "archived"
            else -> error("ArchiveActionProvider was asked for \"$actionId\", which it does not offer.")
        }

        val stored = repository.setVisibility(activity.activityId, wanted)

        val message = when {
            stored.visibility == "active" && stored.visibilityLocked -> R.string.restored_locked
            stored.visibility == "active" -> R.string.restored
            stored.visibilityLocked -> R.string.archived_locked
            else -> R.string.archived
        }

        return ActivityActionResult(message = message, reload = true)
    }

    private companion object {
        const val RESTORE = "sources:restore"
        const val SET_ASIDE = "sources:set-aside"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ArchiveActionModule {

    @Binds
    @IntoSet
    abstract fun bindArchiveActions(provider: ArchiveActionProvider): ActivityActionProvider
}
