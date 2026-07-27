package dev.healthhub.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.healthhub.core.healthconnect.HealthConnectSource
import dev.healthhub.core.healthconnect.HealthDomain
import dev.healthhub.core.healthconnect.HealthFeatures
import dev.healthhub.core.healthconnect.HealthRecordRegistry
import dev.healthhub.core.healthconnect.UnmodelledRecord
import dev.healthhub.core.sync.HealthRecordSync
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One switchable domain, as the screen shows it. */
internal data class DomainState(
    val domain: HealthDomain,
    val enabled: Boolean,
    /** Whether Health Connect has actually granted what this domain reads. */
    val granted: Boolean,
)

/** A single most-recent reading, for the kinds that are a number rather than a trend. */
internal data class LatestReading(
    val kind: String,
    val label: String,
    val value: Double,
    val secondaryValue: Double?,
    val unit: String,
    val measuredAt: Long,
)

internal data class HealthUiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val error: String? = null,
    val availability: HealthConnectSource.Availability =
        HealthConnectSource.Availability.UNAVAILABLE,
    val domains: List<DomainState> = emptyList(),
    val readiness: Readiness.Score? = null,
    /** Newest first, as the API returns them. */
    val nights: List<SleepDto> = emptyList(),
    /** Oldest first, one point per local day. */
    val hrv: List<DayValue> = emptyList(),
    val hrvBaseline: Double? = null,
    val restingHr: List<DayValue> = emptyList(),
    val restingHrBaseline: Double? = null,
    val latest: List<LatestReading> = emptyList(),
    val notIngested: List<UnmodelledRecord> = HealthRecordRegistry.notIngested,
) {
    val lastNight: SleepDto? get() = nights.firstOrNull()
}

@HiltViewModel
internal class HealthViewModel @Inject constructor(
    private val healthConnect: HealthConnectSource,
    private val features: HealthFeatures,
    private val repository: HealthRepository,
    private val healthRecords: HealthRecordSync,
) : ViewModel() {

    private val _state = MutableStateFlow(HealthUiState())
    val state: StateFlow<HealthUiState> = _state.asStateFlow()

    init {
        refreshDomains()
        load()
    }

    /**
     * What to request when the athlete turns a domain on.
     *
     * Only what that domain needs, plus the two that apply to every read — this is the request
     * Principle IV is about, made at the moment the reason for it is on screen rather than at
     * install time when it is not.
     */
    fun permissionsFor(domain: HealthDomain): Set<String> =
        healthConnect.permissionsFor(setOf(domain, HealthDomain.ALWAYS_ON))

    /**
     * The SDK's own request contract, reached through `core:healthconnect` so no screen has to
     * import a Health Connect type to ask for a permission.
     */
    fun permissionContract() = healthConnect.permissionRequestContract()

    fun setEnabled(domain: HealthDomain, on: Boolean) {
        features.setEnabled(domain, on)
        refreshDomains()
    }

    fun refreshDomains() {
        viewModelScope.launch {
            val availability = healthConnect.availability
            val enabled = features.enabled.value
            val domains = HealthDomain.entries.map { domain ->
                DomainState(
                    domain = domain,
                    enabled = domain in enabled,
                    granted = availability == HealthConnectSource.Availability.AVAILABLE &&
                        runCatching { healthConnect.isGranted(domain) }.getOrDefault(false),
                )
            }
            _state.value = _state.value.copy(availability = availability, domains = domains)
        }
    }

    /**
     * Pulls the trend windows and derives everything the screen shows.
     *
     * Each request is independent and each failure is survivable: an athlete with sleep and no
     * heart-rate variability should see their sleep, not an error page. Only a total failure is
     * reported as one.
     */
    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)

            val fetched = coroutineScope {
                val nights = async { runCatching { repository.nights(NIGHTS) } }
                val kinds = TRENDED + LATEST_KINDS
                val byKind = kinds.associateWith { kind ->
                    val limit = if (kind in TRENDED) TREND_DAYS else LATEST_ONLY
                    async { runCatching { repository.measurements(kind, limit) } }
                }
                Fetched(nights.await(), byKind.mapValues { it.value.await() })
            }

            val nights = fetched.nights.getOrDefault(emptyList())
            fun readings(kind: String) =
                fetched.measurements[kind]?.getOrDefault(emptyList()).orEmpty()

            val hrvDays = Trends.daily(readings(HealthRecordRegistry.Kind.HRV_RMSSD))
            val restingHrDays = Trends.daily(readings(HealthRecordRegistry.Kind.RESTING_HEART_RATE))

            val hrvBaseline = Trends.baseline(hrvDays, BASELINE_DAYS)
            val restingHrBaseline = Trends.baseline(restingHrDays, BASELINE_DAYS)

            val readiness = Readiness.of(
                Readiness.Input(
                    hrvToday = Trends.latest(hrvDays)?.value,
                    hrvBaseline = hrvBaseline,
                    restingHrToday = Trends.latest(restingHrDays)?.value,
                    restingHrBaseline = restingHrBaseline,
                    sleptSeconds = nights.firstOrNull()?.totalSeconds,
                ),
            )

            val latest = LATEST_KINDS.mapNotNull { kind ->
                readings(kind).latest(LATEST_LABELS.getValue(kind))
            }

            // Everything failing at once is a connection problem, and saying so is more use
            // than seven empty cards.
            val outcomes = listOf(fetched.nights) + fetched.measurements.values
            val allFailed = outcomes.all { it.isFailure }
            _state.value = _state.value.copy(
                loading = false,
                error = if (allFailed) {
                    outcomes.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                        ?: "Could not reach HealthHub."
                } else {
                    null
                },
                readiness = readiness,
                nights = nights,
                hrv = hrvDays,
                hrvBaseline = hrvBaseline,
                restingHr = restingHrDays,
                restingHrBaseline = restingHrBaseline,
                latest = latest,
            )
        }
    }

    /**
     * Reads the newly enabled domains out of Health Connect now.
     *
     * The daily-grain pass only, never a whole sync. A year of exercise sessions is three
     * hundred and sixty-five day-windows at eight reads each, which is how the provider's quota
     * was exhausted in the first place; the same year of sleep and measurements is twelve
     * month-windows. Turning on a switch must not cost the athlete their workout sync.
     *
     * A year back because a domain switched on today has never been read — its cursor is empty,
     * and the history behind it is the point of turning it on. Domains that already have a
     * cursor only read forward from it.
     */
    fun syncAndReload() {
        viewModelScope.launch {
            _state.value = _state.value.copy(syncing = true)
            val to = Instant.now()
            runCatching { healthRecords.sync(to.minusSeconds(BACKFILL_SECONDS), to) }
            _state.value = _state.value.copy(syncing = false)
            refreshDomains()
            load()
        }
    }

    private class Fetched(
        val nights: Result<List<SleepDto>>,
        val measurements: Map<String, Result<List<MeasurementDto>>>,
    )

    private fun List<MeasurementDto>.latest(label: String): LatestReading? =
        maxByOrNull { it.measuredAt }?.let {
            LatestReading(
                kind = it.kind,
                label = label,
                value = it.value,
                secondaryValue = it.secondaryValue,
                unit = it.unit,
                measuredAt = it.measuredAt,
            )
        }

    private companion object {
        const val NIGHTS = 30
        const val TREND_DAYS = 90
        const val LATEST_ONLY = 10
        const val BASELINE_DAYS = 60
        const val BACKFILL_SECONDS = 365L * 24 * 60 * 60

        /** Kinds the screen draws as a trend. The rest are shown as their most recent value. */
        val TRENDED = listOf(
            HealthRecordRegistry.Kind.HRV_RMSSD,
            HealthRecordRegistry.Kind.RESTING_HEART_RATE,
        )

        val LATEST_LABELS: Map<String, String> = mapOf(
            HealthRecordRegistry.Kind.OXYGEN_SATURATION to "Blood oxygen",
            HealthRecordRegistry.Kind.WEIGHT to "Weight",
            HealthRecordRegistry.Kind.BODY_FAT to "Body fat",
            HealthRecordRegistry.Kind.BLOOD_PRESSURE to "Blood pressure",
        )

        val LATEST_KINDS = LATEST_LABELS.keys.toList()
    }
}
