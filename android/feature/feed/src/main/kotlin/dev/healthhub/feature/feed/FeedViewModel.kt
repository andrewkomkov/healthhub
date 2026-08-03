package dev.healthhub.feature.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.healthhub.core.database.CachedActivity
import dev.healthhub.core.database.StagingDao
import dev.healthhub.core.model.UnitSystem
import dev.healthhub.core.network.FeedActivityDto
import dev.healthhub.core.network.HealthHubApi
import dev.healthhub.core.preferences.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * What the feed is doing, in terms the screen can draw.
 *
 * Three loading flags rather than one, because they are three different pictures. [loading] is
 * the cold start with nothing on screen and is drawn as skeleton cards; [refreshing] is the
 * athlete pulling on a list they are already reading and must not blank it; [loadingMore] is a
 * footer under the last card. One flag for all three is how a pull-to-refresh ends up wiping the
 * screen it was invoked from.
 */
data class FeedUiState(
    val activities: List<FeedActivityDto> = emptyList(),
    /** The account's, not this screen's. Every figure on a card is rendered in it. */
    val units: UnitSystem = UnitSystem.METRIC,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val offline: Boolean = false,
    val exhausted: Boolean = false,
    /** Nothing could be shown at all. */
    val error: String? = null,
    /** The list is fine; the next page is not. Reported under the last card, not over it. */
    val pageError: String? = null,
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val api: HealthHubApi,
    private val staging: StagingDao,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private var cursor: String? = null

    /**
     * The request in flight, so a pull halfway through a page cannot leave two of them racing to
     * write the same list. The loser would append its page *after* the refresh had already
     * replaced the list, and the feed would show page two of a list whose page one is gone.
     */
    private var job: Job? = null

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    init {
        refresh()

        // Collected for the life of the screen rather than read once: the athlete can change
        // this on the settings screen and come back, and a feed still in kilometres behind a
        // preference that says miles is the disagreement this preference exists to prevent.
        viewModelScope.launch {
            preferences.unitSystem.collect { units ->
                _state.value = _state.value.copy(units = units)
            }
        }
    }

    /**
     * Start again from the newest workout.
     *
     * [fromPull] is the athlete asking, with the list already in front of them — so the cards
     * stay where they are and the indicator does the talking. Without the distinction a pull
     * clears the screen and everything below the fold jumps.
     */
    fun refresh(fromPull: Boolean = false) {
        job?.cancel()
        cursor = null

        val keepWhatIsOnScreen = fromPull && _state.value.activities.isNotEmpty()
        _state.value = if (keepWhatIsOnScreen) {
            _state.value.copy(refreshing = true, error = null, pageError = null)
        } else {
            // The units survive every reset: they are the account's answer, not this request's.
            FeedUiState(units = _state.value.units, loading = true)
        }

        job = viewModelScope.launch { fetch(append = false) }
    }

    /** The next page, requested by the list approaching its end. */
    fun loadMore() {
        val current = _state.value
        if (current.exhausted || current.loading || current.refreshing || current.loadingMore) return
        if (current.pageError != null) return

        _state.value = current.copy(loadingMore = true)
        job = viewModelScope.launch { fetch(append = true) }
    }

    /** After a failed page, the athlete asking for it again. */
    fun retryPage() {
        _state.value = _state.value.copy(pageError = null)
        loadMore()
    }

    private suspend fun fetch(append: Boolean) {
        val before = _state.value.activities

        runCatching { api.feed(cursor = cursor) }
            .onSuccess { page ->
                cursor = page.nextCursor
                val combined = if (append) before + page.activities else page.activities
                _state.value = FeedUiState(
                    activities = combined,
                    units = _state.value.units,
                    loading = false,
                    refreshing = false,
                    loadingMore = false,
                    offline = false,
                    exhausted = page.nextCursor == null,
                )
                cache(page.activities)
            }
            .onFailure { failure ->
                val message = failure.message ?: "Could not reach HealthHub."

                if (append) {
                    // A page that failed halfway down the list must not replace the list. It
                    // used to: the cache fallback ran on every failure, so a blip on page three
                    // silently swapped a hundred workouts for whatever had been cached, in a
                    // different order, with no indication that anything had happened.
                    //
                    // It also has to stop the sentinel, not merely report itself — the footer
                    // sits inside the viewport, so a re-render would ask for the same page again
                    // as fast as the requests can fail. The web client learnt this one first.
                    _state.value = _state.value.copy(loadingMore = false, pageError = message)
                    return@onFailure
                }

                // Falling back to the cache is what makes the feed browsable with no connection
                // (FR-014) rather than an error screen.
                val cached = loadCached()
                _state.value = FeedUiState(
                    activities = cached,
                    units = _state.value.units,
                    loading = false,
                    refreshing = false,
                    loadingMore = false,
                    offline = true,
                    exhausted = true,
                    error = if (cached.isEmpty()) message else null,
                )
            }
    }

    private suspend fun cache(activities: List<FeedActivityDto>) {
        if (activities.isEmpty()) return
        staging.cache(
            activities.map { activity ->
                CachedActivity(
                    id = activity.id,
                    payload = json.encodeToString(activity),
                    startTime = activity.startTime,
                    cachedAt = System.currentTimeMillis(),
                )
            },
        )
    }

    private suspend fun loadCached(): List<FeedActivityDto> =
        staging.cached().mapNotNull { entry ->
            runCatching { json.decodeFromString<FeedActivityDto>(entry.payload) }.getOrNull()
        }
}
