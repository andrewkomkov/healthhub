package dev.healthhub.core.sync

import dev.healthhub.core.network.TokenStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The three `/api/health-records` calls the daily-grain sync makes.
 *
 * Kept beside the sync that uses them rather than pushed into `core:network`, the same call
 * `SourcesRepository` makes: the shared [OkHttpClient] and [TokenStore] are reused, so timeouts
 * and credentials still have exactly one definition, but nothing outside `core:sync` has to
 * learn about a route that only the sync path uses.
 *
 * Every number in these bodies was computed on the phone. The contract in
 * `specs/001-workout-sync-feed/contracts/api.md` is explicit that the edge stores them verbatim.
 */
@Singleton
class HealthRecordUploader @Inject constructor(
    private val client: OkHttpClient,
    private val tokens: TokenStore,
    @Named("baseUrl") private val baseUrl: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val jsonMedia = "application/json".toMediaType()

    /**
     * Uploads scalar measurements, idempotent on `sourceUid`.
     *
     * Chunked well below the contract's 2000 ceiling: a month of readings from a watch that
     * samples HRV hourly is a few hundred rows, and a request that has to be retried whole is
     * cheaper to retry small.
     */
    suspend fun putMeasurements(measurements: List<MeasurementDto>): Int =
        withContext(Dispatchers.IO) {
            var accepted = 0
            for (chunk in measurements.chunked(BATCH_SIZE)) {
                val request = Request.Builder()
                    .url("$baseUrl/api/health-records/measurements")
                    .post(json.encodeToString(MeasurementBatch(chunk)).toRequestBody(jsonMedia))
                    .authorize()
                    .build()

                client.newCall(request).execute().use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) throw IOException("Measurement upload failed with ${it.code}")
                    accepted += runCatching { json.decodeFromString<AcceptedDto>(body).accepted }
                        .getOrDefault(chunk.size)
                }
            }
            accepted
        }

    /** One night's summary. Returns the server id the hypnogram is then filed under. */
    suspend fun putSleep(night: SleepUploadDto): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/health-records/sleep")
            .post(json.encodeToString(night).toRequestBody(jsonMedia))
            .authorize()
            .build()

        client.newCall(request).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("Sleep upload failed with ${it.code}")
            json.decodeFromString<SleepEnvelopeDto>(body).sleep.id
        }
    }

    /**
     * The night's stage intervals, gzipped, straight into R2.
     *
     * Built in memory unlike telemetry, and deliberately: a hypnogram is tens to hundreds of
     * intervals — single-digit kilobytes — where a `.hht` is megabytes. Staging this to disk
     * would be ceremony without a reason.
     */
    suspend fun putHypnogram(sleepId: String, stages: List<StageDto>) = withContext(Dispatchers.IO) {
        val payload = ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).use { it.write(json.encodeToString(stages).toByteArray()) }
        }.toByteArray()

        val request = Request.Builder()
            .url("$baseUrl/api/health-records/sleep/$sleepId/stages")
            .put(payload.toRequestBody(jsonMedia))
            .header("content-encoding", "gzip")
            .authorize()
            .build()

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("Hypnogram upload failed with ${it.code}")
        }
    }

    private fun Request.Builder.authorize(): Request.Builder = apply {
        tokens.deviceToken()?.let { header("authorization", "Bearer $it") }
        tokens.sessionCookie()?.let { header("cookie", it) }
    }

    private companion object {
        const val BATCH_SIZE = 500
    }
}

/**
 * One scalar reading.
 *
 * `value` and `secondaryValue` are two numbers and a unit, which is enough for every scalar
 * type Health Connect exposes; only blood pressure uses the second one (systolic, diastolic),
 * and the edge does not know that.
 */
@Serializable
data class MeasurementDto(
    val sourceUid: String,
    val sourcePackage: String? = null,
    val kind: String,
    val measuredAt: Long,
    val tzOffsetMinutes: Int = 0,
    val value: Double,
    val secondaryValue: Double? = null,
    val unit: String,
)

@Serializable
data class MeasurementBatch(val measurements: List<MeasurementDto>)

@Serializable
data class AcceptedDto(val accepted: Int = 0)

@Serializable
data class SleepUploadDto(
    val sourceUid: String,
    val sourcePackage: String? = null,
    val title: String? = null,
    val startTime: Long,
    val endTime: Long,
    val tzOffsetMinutes: Int = 0,
    val totalSeconds: Long,
    val timeInBedSeconds: Long? = null,
    /** Only the stages this source reported. An absent key means "never reported". */
    val stages: Map<String, Long> = emptyMap(),
    val stageCount: Int = 0,
)

@Serializable
data class SleepRefDto(val id: String, val stagesUploadPath: String? = null)

@Serializable
data class SleepEnvelopeDto(val sleep: SleepRefDto)

@Serializable
data class StageDto(val stage: String, val startTime: Long, val endTime: Long)
