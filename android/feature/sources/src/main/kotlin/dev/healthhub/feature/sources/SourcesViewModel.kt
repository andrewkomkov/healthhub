package dev.healthhub.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.healthhub.core.network.HealthHubApi
import dev.healthhub.core.network.SourceDto
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Long enough that a run of move taps is one request, short enough to feel immediate. */
private const val SAVE_DELAY_MS = 400L

enum class SaveState { IDLE, SAVING, SAVED, FAILED }

data class SourcesUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val sources: List<SourceDto> = emptyList(),
    val save: SaveState = SaveState.IDLE,
    /** Spoken by the status line's live region; a list visually rearranging is not an event. */
    val announcement: String = "",
)

/**
 * Which apps the athlete trusts, most trusted first.
 *
 * The order stored here is what the phone applies on the next sync when it decides which of
 * several recordings represents a workout — this screen is the athlete's answer to "my head
 * unit measured the distance, my phone was in a pocket".
 *
 * A disabled source is still ingested. Its recordings go to the archive and stay restorable:
 * turning an app off is a statement about whose numbers to believe, not about what to keep.
 */
@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val repository: SourcesRepository,
    private val api: HealthHubApi,
) : ViewModel() {

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    /** The order the server last accepted. A failed write puts the screen back to this. */
    private var saved: List<SourceDto> = emptyList()
    private var saveJob: Job? = null

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, loadError = null)
        viewModelScope.launch {
            runCatching { api.sources() }
                .onSuccess { sources ->
                    saved = sources
                    _state.value = _state.value.copy(loading = false, sources = sources)
                }
                .onFailure {
                    _state.value =
                        _state.value.copy(loading = false, loadError = "Could not load your sources.")
                }
        }
    }

    /** Moves one source. Both the drag and the move buttons come through here. */
    fun move(from: Int, to: Int) {
        val current = _state.value.sources
        val next = current.movedItem(from, to)
        if (next === current) return
        val moved = current[from]
        _state.value = _state.value.copy(
            sources = next,
            announcement = "${Labels.sourceLabel(moved.packageName, moved.label)}, " +
                "position ${next.indexOf(moved) + 1} of ${next.size}.",
        )
        scheduleSave(next)
    }

    fun toggle(index: Int) {
        val current = _state.value.sources
        val source = current.getOrNull(index) ?: return
        val next = current.mapIndexed { i, entry ->
            if (i == index) entry.copy(enabled = !entry.enabled) else entry
        }
        val name = Labels.sourceLabel(source.packageName, source.label)
        _state.value = _state.value.copy(
            sources = next,
            announcement = if (source.enabled) {
                "$name turned off. Its recordings move to the archive and stay restorable."
            } else {
                "$name turned on."
            },
        )
        scheduleSave(next)
    }

    /** Called when a drag ends: the intermediate positions were never worth a request. */
    fun commit() {
        val next = _state.value.sources
        if (orderChanged(next, saved) { it.decisionKey() }) scheduleSave(next)
    }

    private fun scheduleSave(next: List<SourceDto>) {
        saveJob?.cancel()
        _state.value = _state.value.copy(save = SaveState.SAVING)
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            runCatching { repository.putOrder(next.asOrderEntries()) }
                .onSuccess {
                    saved = next
                    _state.value = _state.value.copy(save = SaveState.SAVED)
                }
                .onFailure {
                    // Put the list back to what the server actually holds. A screen showing an
                    // order that was never stored would send the athlete away believing a
                    // change they did not make.
                    _state.value = _state.value.copy(sources = saved, save = SaveState.FAILED)
                }
        }
    }

    /**
     * A pending write must not be lost to a back navigation mid-debounce, and this scope is
     * already cancelled by the time we get here — so the last decision is handed to the
     * repository, which outlives the screen.
     *
     * The question asked is "does the screen still differ from what the server accepted", not
     * "is a save job running": `viewModelScope` is cancelled *before* `onCleared`, so
     * `saveJob.isActive` is false here in exactly the case this exists to rescue. [saved] only
     * advances on a successful write, and a failed one already put the list back, so a
     * difference here means a decision the server has not been told about.
     */
    override fun onCleared() {
        val pending = _state.value.sources
        if (orderChanged(pending, saved) { it.decisionKey() }) {
            repository.putOrderInBackground(pending.asOrderEntries())
        }
        super.onCleared()
    }
}

/**
 * What a write actually stores about one source: where it sits and whether it is switched on.
 *
 * Comparing on the package order alone would miss a switch flipped inside the debounce window —
 * the athlete turns a source off, walks back to the feed within the delay, and the change is
 * dropped without a word.
 */
internal fun SourceDto.decisionKey(): String = "$packageName:$enabled"

/** The array order is the priority; it is sent explicitly so the two cannot disagree. */
internal fun List<SourceDto>.asOrderEntries(): List<SourceOrderEntry> =
    mapIndexed { index, source ->
        SourceOrderEntry(
            packageName = source.packageName,
            enabled = source.enabled,
            priority = index,
        )
    }
