package dev.healthhub.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.healthhub.core.model.UnitSystem
import dev.healthhub.core.network.HealthHubApi
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a single card is doing. Mirrors the web client's `CardState`. */
enum class CardState { IDLE, WORKING, RESTORED, FAILED }

data class ArchiveUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val activities: List<ArchivedActivityDto> = emptyList(),
    val exhausted: Boolean = false,
    val units: UnitSystem = UnitSystem.METRIC,
    /** Per-activity, keyed by id; absent means [CardState.IDLE]. */
    val cards: Map<String, CardState> = emptyMap(),
)

/**
 * Everything Health Connect handed over that is not currently representing its workout.
 *
 * Health Connect is a hub: one ride arrives from the bike computer's own app, from Strava and
 * from Google Fit, each with its own idea of the distance. The phone picks one using the
 * athlete's source order and sets the rest aside. This is where those live, and where the
 * athlete overrules that choice for one specific workout.
 *
 * There is no destructive action here and no route to one, because the model has none: a
 * recording is either representing its workout or waiting in this list.
 */
@HiltViewModel
class ArchiveViewModel @Inject constructor(
    private val repository: SourcesRepository,
    private val api: HealthHubApi,
) : ViewModel() {

    private val _state = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = _state.asStateFlow()

    private var cursor: String? = null

    /** Guards re-entry: two page loads in the same frame would append the same page twice. */
    private var loadingPage = false

    init {
        viewModelScope.launch {
            // The athlete's unit system decides every figure on the card. A failure here is not
            // worth an error state — metric is the stored unit and the honest default.
            runCatching { api.me() }.onSuccess { user ->
                _state.value = _state.value.copy(
                    units = if (user.unitSystem.equals("imperial", ignoreCase = true)) {
                        UnitSystem.IMPERIAL
                    } else {
                        UnitSystem.METRIC
                    },
                )
            }
        }
        loadMore()
    }

    fun loadMore() {
        if (loadingPage || _state.value.exhausted) return
        loadingPage = true
        // Cleared on the way in, so a retry reads as "loading" rather than as the failure it is
        // retrying.
        _state.value = _state.value.copy(loading = true, error = null)

        viewModelScope.launch {
            runCatching { repository.archivePage(cursor) }
                .onSuccess { page ->
                    cursor = page.nextCursor
                    _state.value = _state.value.copy(
                        loading = false,
                        error = null,
                        activities = _state.value.activities + page.activities,
                        exhausted = page.nextCursor == null,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "Could not load your archive.",
                    )
                }
            loadingPage = false
        }
    }

    /** Puts a recording back in the feed. */
    fun restore(id: String) = setVisibility(id, ArchivedActivityDto.ACTIVE)

    /** Undoes a restore. The recording goes back to this list, intact. */
    fun setAside(id: String) = setVisibility(id, ArchivedActivityDto.ARCHIVED)

    private fun setVisibility(id: String, visibility: String) {
        card(id, CardState.WORKING)
        viewModelScope.launch {
            runCatching { repository.setVisibility(id, visibility) }
                .onSuccess { updated ->
                    // The card stays exactly where it is, showing what happened and offering
                    // the way back. A row that vanished on restore would look like the thing
                    // the athlete was promised never happens.
                    _state.value = _state.value.copy(
                        activities = _state.value.activities.map {
                            if (it.id == updated.id) updated else it
                        },
                    )
                    card(
                        id,
                        if (visibility == ArchivedActivityDto.ACTIVE) {
                            CardState.RESTORED
                        } else {
                            CardState.IDLE
                        },
                    )
                }
                .onFailure { card(id, CardState.FAILED) }
        }
    }

    private fun card(id: String, state: CardState) {
        _state.value = _state.value.copy(cards = _state.value.cards + (id to state))
    }
}
