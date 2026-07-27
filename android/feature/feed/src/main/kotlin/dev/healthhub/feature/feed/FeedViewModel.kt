package dev.healthhub.feature.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.healthhub.core.database.CachedActivity
import dev.healthhub.core.database.StagingDao
import dev.healthhub.core.network.FeedActivityDto
import dev.healthhub.core.network.HealthHubApi
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class FeedUiState(
    val activities: List<FeedActivityDto> = emptyList(),
    val loading: Boolean = true,
    val offline: Boolean = false,
    val exhausted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val api: HealthHubApi,
    private val staging: StagingDao,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private var cursor: String? = null

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        cursor = null
        _state.value = FeedUiState(loading = true)
        loadMore()
    }

    fun loadMore() {
        if (_state.value.exhausted) return

        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            runCatching { api.feed(cursor = cursor) }
                .onSuccess { page ->
                    cursor = page.nextCursor
                    val combined = _state.value.activities + page.activities
                    _state.value = FeedUiState(
                        activities = combined,
                        loading = false,
                        exhausted = page.nextCursor == null,
                    )
                    cache(page.activities)
                }
                .onFailure {
                    // Falling back to the cache is what makes the feed browsable with no
                    // connection (FR-014) rather than an error screen.
                    val cached = loadCached()
                    _state.value = FeedUiState(
                        activities = cached,
                        loading = false,
                        offline = true,
                        exhausted = true,
                        error = if (cached.isEmpty()) "Could not load your activities." else null,
                    )
                }
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
