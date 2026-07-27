package dev.healthhub.feature.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.healthhub.core.healthconnect.HealthConnectSource
import dev.healthhub.core.model.SyncReport
import dev.healthhub.core.sync.SyncEngine
import dev.healthhub.core.sync.SyncWorker
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SyncUiState(
    val availability: HealthConnectSource.Availability =
        HealthConnectSource.Availability.UNAVAILABLE,
    val missingPermissions: Set<String> = emptySet(),
    val progress: SyncEngine.Progress = SyncEngine.Progress.Idle,
    val lastReport: SyncReport? = null,
    val unmeteredOnly: Boolean = true,
    val running: Boolean = false,
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthConnect: HealthConnectSource,
    private val engine: SyncEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    val permissions: Set<String> get() = healthConnect.permissions

    init {
        refreshPermissions()
        viewModelScope.launch {
            engine.progress.collect { progress ->
                _state.value = _state.value.copy(
                    progress = progress,
                    running = progress is SyncEngine.Progress.Running,
                    lastReport = (progress as? SyncEngine.Progress.Finished)?.report
                        ?: _state.value.lastReport,
                )
            }
        }
    }

    fun refreshPermissions() {
        viewModelScope.launch {
            val availability = healthConnect.availability
            _state.value = _state.value.copy(
                availability = availability,
                missingPermissions = if (availability == HealthConnectSource.Availability.AVAILABLE) {
                    runCatching { healthConnect.missingPermissions() }.getOrDefault(emptySet())
                } else {
                    healthConnect.permissions
                },
            )
        }
    }

    /**
     * Runs a sync in the caller's scope rather than through WorkManager.
     *
     * A sync the athlete explicitly asked for should start now and report progress on screen;
     * the scheduled pass is the one that goes through WorkManager.
     */
    fun syncNow() {
        viewModelScope.launch {
            _state.value = _state.value.copy(running = true)
            val report = engine.sync()
            _state.value = _state.value.copy(running = false, lastReport = report)
        }
    }

    fun setUnmeteredOnly(value: Boolean) {
        _state.value = _state.value.copy(unmeteredOnly = value)
        SyncWorker.schedule(context, unmeteredOnly = value)
    }

    fun scheduleBackground() {
        SyncWorker.schedule(context, unmeteredOnly = _state.value.unmeteredOnly)
    }
}
