package dev.healthhub.feature.activity

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.healthhub.core.model.RoutePoint
import dev.healthhub.core.model.UnitSystem
import dev.healthhub.core.navigation.ActivityAction
import dev.healthhub.core.navigation.ActivityActionProvider
import dev.healthhub.core.navigation.ActivityActionResult
import dev.healthhub.core.navigation.ActivityActionTarget
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.network.HealthHubApi
import dev.healthhub.core.preferences.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which resolution of telemetry is currently on screen. */
enum class Resolution { NONE, PREVIEW, FULL }

/**
 * Something to say to the athlete, from one of two very different places.
 *
 * A contributed action reports success as a **resource id** — it runs in a repository with no
 * `Context` and its words belong to the module that owns the action. A failure reports whatever
 * the failure itself said, which is already a string and is not translatable: there is no
 * catalogue of every message a network stack can produce, and inventing one generic sentence
 * for all of them would hide the one detail that makes a failure diagnosable.
 */
sealed interface ActivityMessage {
    @JvmInline
    value class Resource(@androidx.annotation.StringRes val id: Int) : ActivityMessage

    @JvmInline
    value class Text(val value: String) : ActivityMessage
}

data class ActivityUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val activity: ActivityDetailDto? = null,
    val telemetry: TelemetryChannels? = null,
    val resolution: Resolution = Resolution.NONE,
    val units: UnitSystem = UnitSystem.METRIC,
    val route: RouteImportState = RouteImportState.Hidden,
    /** What other feature modules offer for this activity. Empty until the row has loaded. */
    val actions: List<ActivityAction> = emptyList(),
    /** The id of an action in flight, so the screen cannot start it twice. */
    val runningAction: String? = null,
    /** What the last action did. Shown once, then cleared. */
    val message: ActivityMessage? = null,
)

/**
 * Loads one activity: the summary first, then telemetry at whatever resolution arrives first.
 *
 * The preview is roughly two thousand samples and lands in a single small response, so the
 * charts and the route are on screen while the full object is still transferring; when it
 * arrives it is swapped in underneath. That ordering is the whole reason the phone writes two
 * objects, and it is what makes SC-005 achievable on a slow connection rather than merely on a
 * fast one. A late preview is dropped rather than allowed to overwrite the full resolution —
 * on a fast link the two responses can land in either order.
 */
@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val repository: ActivityRepository,
    private val api: HealthHubApi,
    private val routes: RouteImporter,
    private val preferences: AppPreferences,
    /**
     * Whatever other feature modules contribute to this screen. Empty is a normal answer —
     * nothing here knows or cares which modules are in the build.
     */
    private val actionProviders: Set<@JvmSuppressWildcards ActivityActionProvider>,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val activityId: String =
        savedStateHandle.get<String>(Destination.ActivityDetail.ARG).orEmpty()

    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    init {
        load()

        // The locally mirrored answer, which is on screen before the network has said anything.
        // The screen used to open in metric and then flick to imperial when `me()` came back.
        viewModelScope.launch {
            preferences.unitSystem.collect { units ->
                _state.value = _state.value.copy(units = units)
            }
        }
    }

    fun load() {
        // The units survive the reset. The collector above only speaks when the *preference*
        // changes, so a state rebuilt from scratch here would sit in metric until the athlete
        // went and changed it — which is a retry silently altering every figure on the screen.
        _state.value = ActivityUiState(loading = true, units = _state.value.units)

        viewModelScope.launch {
            // The athlete's unit system decides every label on the screen. A failure here is not
            // worth an error state — the mirrored preference is already on screen, and metric is
            // the stored unit and the honest default behind that.
            //
            // The answer is written back rather than kept: this screen is not the only one that
            // renders a distance, and a preference read here and nowhere else is how the feed
            // came to draw kilometres under a detail screen drawing miles.
            launch {
                runCatching { api.me() }.onSuccess { user ->
                    val units = if (user.unitSystem.equals("imperial", ignoreCase = true)) {
                        UnitSystem.IMPERIAL
                    } else {
                        UnitSystem.METRIC
                    }
                    preferences.setUnitSystem(units)
                }
            }

            val detail = runCatching { repository.detail(activityId) }.getOrElse { error ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = error.message ?: "Could not load this activity.",
                )
                return@launch
            }

            // Set here rather than inside the coroutine that does the looking: Hidden's copy
            // says the track is on its way with the telemetry, which is true for a workout that
            // has one and a lie for a workout that does not. A frame of the wrong answer is
            // still the wrong answer, and this screen's whole job is not to give it.
            _state.value = _state.value.copy(
                loading = false,
                activity = detail,
                route = if (detail.hasGps) RouteImportState.Hidden else RouteImportState.Checking,
            )
            refreshActions(detail)

            if (detail.telemetry.preview) launch { fetch(detail, "preview", Resolution.PREVIEW) }
            if (detail.telemetry.full) launch { fetch(detail, "full", Resolution.FULL) }

            // Only for a workout with no track on it. Asking Health Connect costs a read, and a
            // workout that already draws its route has nothing to ask about.
            if (!detail.hasGps) launch { inspectRoute(detail) }
        }
    }

    private suspend fun inspectRoute(detail: ActivityDetailDto) {
        val state = withContext(Dispatchers.IO) { routes.inspect(detail) }
        _state.value = _state.value.copy(route = state)
    }

    /**
     * Called with whatever the platform's route-consent screen returned.
     *
     * A null list is a dismissal, not an absence: this is only ever reached from an
     * [RouteImportState.Offered], which already established that the session holds a track.
     * Saying "no route was recorded" here would be a lie the athlete has no way to check.
     */
    fun onRouteGranted(sessionId: String, points: List<RoutePoint>?) {
        if (points == null) {
            _state.value = _state.value.copy(route = RouteImportState.Declined(sessionId))
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(route = RouteImportState.Importing)
            val result = withContext(Dispatchers.IO) { routes.import(sessionId, points) }
            _state.value = _state.value.copy(route = result)
            // The workout on the server is a different workout now: new polyline, new bounds,
            // possibly a new distance and splits, and a rewritten .hht. Reloading is how the
            // screen shows what was actually stored rather than what it hoped was stored.
            if (result is RouteImportState.Imported) reload(result)
        }
    }

    /** Reloads after an import, keeping the message that explains what the import decided. */
    private suspend fun reload(outcome: RouteImportState.Imported) {
        val detail = runCatching { repository.detail(activityId) }.getOrNull() ?: return
        _state.value = _state.value.copy(activity = detail, route = outcome)
        refreshActions(detail)
        coroutineScope {
            if (detail.telemetry.preview) launch { fetch(detail, "preview", Resolution.PREVIEW) }
            if (detail.telemetry.full) launch { fetch(detail, "full", Resolution.FULL) }
        }
    }

    private suspend fun fetch(
        detail: ActivityDetailDto,
        variant: String,
        resolution: Resolution,
    ) {
        val bytes = runCatching { repository.telemetry(detail.id, variant) }.getOrNull() ?: return
        val decoded = runCatching {
            // Decoding walks every sample of every channel; doing it on the main thread is how a
            // hundred-thousand-sample activity turns into a dropped second of frames.
            withContext(Dispatchers.Default) {
                TelemetryChannels.decode(bytes, detail.distanceM)
            }
        }.getOrNull() ?: return

        accept(decoded, resolution)
    }

    private fun accept(channels: TelemetryChannels, resolution: Resolution) {
        val current = _state.value
        if (current.resolution == Resolution.FULL && resolution == Resolution.PREVIEW) return
        _state.value = current.copy(telemetry = channels, resolution = resolution)
    }

    /* ------------------------------------------------------- actions other modules contribute */

    /**
     * What the modules that are not this one offer for the activity on screen.
     *
     * Recomputed from the loaded row, so an action's own label follows the state it changed —
     * "Set aside" becomes "Restore" the moment the row comes back archived, with nothing in
     * this file knowing either word.
     */
    private fun targetOf(detail: ActivityDetailDto) = ActivityActionTarget(
        activityId = detail.id,
        title = detail.title,
        archived = detail.visibility == "archived",
        visibilityLocked = detail.visibilityLocked,
        duplicateOf = detail.duplicateOf,
        sourceCount = detail.sourceCount,
    )

    private fun refreshActions(detail: ActivityDetailDto?) {
        val actions = if (detail == null) {
            emptyList()
        } else {
            val target = targetOf(detail)
            actionProviders
                .flatMap { provider -> provider.actionsFor(target) }
                .sortedBy { it.order }
        }
        _state.value = _state.value.copy(actions = actions)
    }

    /**
     * Runs a contributed action and says what happened.
     *
     * A provider reports failure by throwing, so the message the athlete reads comes from one
     * place rather than from each contributor's idea of how to phrase a failed request. The row
     * is re-read afterwards because the action is very likely to have changed it — that is what
     * an action *is* — and reading it back is the only way this screen shows what was stored
     * rather than what it hoped was stored.
     */
    fun perform(action: ActivityAction) {
        val detail = _state.value.activity ?: return
        if (_state.value.runningAction != null) return

        _state.value = _state.value.copy(runningAction = action.id)
        viewModelScope.launch {
            val result = runCatching { performAction(action, targetOf(detail)) }
            _state.value = _state.value.copy(
                runningAction = null,
                message = result.fold(
                    onSuccess = { ActivityMessage.Resource(it.message) },
                    onFailure = { failure ->
                        failure.message
                            ?.let(ActivityMessage::Text)
                            ?: ActivityMessage.Resource(R.string.action_failed)
                    },
                ),
            )
            if (result.getOrNull()?.reload == true) reloadDetail()
        }
    }

    private suspend fun performAction(
        action: ActivityAction,
        target: ActivityActionTarget,
    ): ActivityActionResult {
        // Whichever provider claims the id. Ids are unique across providers by contract, so the
        // first match is the only match; an unclaimed id means a provider offered an action and
        // then refused it, which is a defect in that provider rather than something to swallow.
        for (provider in actionProviders) {
            if (provider.actionsFor(target).none { it.id == action.id }) continue
            return provider.perform(action.id, target)
        }
        error("No module offers the action \"${action.id}\".")
    }

    /** Re-reads the row after an action changed it, keeping everything already on screen. */
    private suspend fun reloadDetail() {
        val detail = runCatching { repository.detail(activityId) }.getOrNull() ?: return
        _state.value = _state.value.copy(activity = detail)
        refreshActions(detail)
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }
}
