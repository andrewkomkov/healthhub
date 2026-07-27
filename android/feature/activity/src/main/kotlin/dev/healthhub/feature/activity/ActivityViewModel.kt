package dev.healthhub.feature.activity

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.healthhub.core.model.RoutePoint
import dev.healthhub.core.model.UnitSystem
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.network.HealthHubApi
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

data class ActivityUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val activity: ActivityDetailDto? = null,
    val telemetry: TelemetryChannels? = null,
    val resolution: Resolution = Resolution.NONE,
    val units: UnitSystem = UnitSystem.METRIC,
    val route: RouteImportState = RouteImportState.Hidden,
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val activityId: String =
        savedStateHandle.get<String>(Destination.ActivityDetail.ARG).orEmpty()

    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = ActivityUiState(loading = true)

        viewModelScope.launch {
            // The athlete's unit system decides every label on the screen. A failure here is not
            // worth an error state — metric is the stored unit and the honest default.
            launch {
                runCatching { api.me() }.onSuccess { user ->
                    val units = if (user.unitSystem.equals("imperial", ignoreCase = true)) {
                        UnitSystem.IMPERIAL
                    } else {
                        UnitSystem.METRIC
                    }
                    _state.value = _state.value.copy(units = units)
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
}
