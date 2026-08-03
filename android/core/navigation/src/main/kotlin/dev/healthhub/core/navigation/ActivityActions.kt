package dev.healthhub.core.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * How a feature module puts an action on the activity detail screen.
 *
 * The second seam in this file's family, and the one session 2 stopped at. `NavContribution`
 * lets a module add a *route*, and `menuEntries` lets it add a *way in* — but there was no way
 * for a module to add an *action to somebody else's screen*, and archive-and-restore is
 * precisely that. `ActivityScreen` is `internal` to `feature:activity`; `feature:sources` owns
 * the archive, the restore call and the vocabulary that explains them. Neither may depend on
 * the other (Principle VII), so the action existed on the archive screen and over ADB while the
 * detail screen the athlete was actually looking at could not offer it.
 *
 * The shape is deliberately the same as `menuEntries`: a module declares what it offers, `:app`
 * — here, `feature:activity` — collects the multibinding and renders it, and the provider is
 * asked what applies to the activity in front of the athlete rather than assuming. That last
 * part is what lets one provider offer "Set aside" on an active recording and "Restore" on an
 * archived one without the detail screen knowing either word.
 */
interface ActivityActionProvider {

    /**
     * What this module offers for *this* activity, which may be nothing.
     *
     * Not a composable and not suspending: it is called while the top bar is being laid out, so
     * it has to answer from what it was handed. Anything that needs the network belongs in
     * [perform], behind the athlete's tap.
     */
    fun actionsFor(activity: ActivityActionTarget): List<ActivityAction>

    /**
     * Runs one of them.
     *
     * Throwing is the way to report failure — the detail screen catches it and says so, rather
     * than every provider inventing its own way to describe a request that did not happen.
     */
    suspend fun perform(actionId: String, activity: ActivityActionTarget): ActivityActionResult
}

/**
 * Everything a contributing module is told about the activity on screen.
 *
 * Deliberately not the detail DTO: that type belongs to `feature:activity` and naming it here
 * would make every contributor depend on the feature they are contributing to, which is the
 * dependency this seam exists to avoid. These are the fields a decision is actually made from.
 */
data class ActivityActionTarget(
    val activityId: String,
    val title: String,
    val archived: Boolean,
    /** True when a person decided this, which outranks anything a later sync concludes. */
    val visibilityLocked: Boolean,
    /** The recording this one was archived in favour of, if it was. */
    val duplicateOf: String?,
    /** How many apps recorded this workout. One means there is no duplicate to reason about. */
    val sourceCount: Int,
)

data class ActivityAction(
    /** Unique across every provider; it is what [ActivityActionProvider.perform] is given. */
    val id: String,
    /** A resource id: `feature:activity` draws this menu and cannot translate somebody
     *  else's string. See [BottomBarEntry.label]. */
    @StringRes val label: Int,
    val icon: ImageVector,
    /** Lower sorts earlier. Explicit so bar order does not depend on injection order. */
    val order: Int,
    /**
     * True for an action that changes what the athlete sees rather than merely where they are.
     * The detail screen confirms these before running them.
     */
    val confirm: ActivityActionConfirmation? = null,
)

/** What to ask before doing it, when it is worth asking. */
data class ActivityActionConfirmation(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val confirmLabel: Int,
)

/**
 * What happened, in words the screen can show.
 *
 * [reload] rather than the provider reaching back into the screen: a restore changes the row
 * the detail screen is drawn from, and the screen is the only thing that knows how to read it
 * again.
 */
data class ActivityActionResult(
    /**
     * A resource id, for the same reason every other label here is one: this is produced in a
     * repository with no `Context`, and read by a screen in another module that does have one.
     */
    @StringRes val message: Int,
    val reload: Boolean = true,
)
