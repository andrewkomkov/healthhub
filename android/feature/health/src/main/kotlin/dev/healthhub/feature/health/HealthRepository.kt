package dev.healthhub.feature.health

import dev.healthhub.core.network.TokenStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The two `/api/health-records` reads these screens need.
 *
 * The data is fetched from the server rather than from Health Connect directly, and that is the
 * point: a night recorded on the athlete's watch and synced from their other phone belongs on
 * this screen too. Health Connect is the *ingest* side of this feature; the server is what every
 * client reads back.
 *
 * Kept in the feature the way `SourcesRepository` is, reusing the shared [OkHttpClient] and
 * [TokenStore] so timeouts and credentials keep exactly one definition (SC-012).
 */
@Singleton
class HealthRepository @Inject constructor(
    private val client: OkHttpClient,
    private val tokens: TokenStore,
    @Named("baseUrl") private val baseUrl: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** One kind, newest first. One indexed read on the edge; the maths happens here. */
    suspend fun measurements(kind: String, limit: Int): List<MeasurementDto> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/health-records/measurements".toHttpUrl().newBuilder()
                .addQueryParameter("kind", kind)
                .addQueryParameter("limit", limit.toString())
                .build()

            client.newCall(Request.Builder().url(url).get().authorize().build()).execute().use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw IOException("Measurements request failed with ${it.code}")
                json.decodeFromString<MeasurementPageDto>(body).measurements
            }
        }

    /** Nights, newest first. Stage totals come back with each row and no R2 object is touched. */
    suspend fun nights(limit: Int): List<SleepDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/health-records/sleep".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .build()

        client.newCall(Request.Builder().url(url).get().authorize().build()).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("Sleep request failed with ${it.code}")
            json.decodeFromString<SleepPageDto>(body).sleeps
        }
    }

    private fun Request.Builder.authorize(): Request.Builder = apply {
        tokens.deviceToken()?.let { header("authorization", "Bearer $it") }
        tokens.sessionCookie()?.let { header("cookie", it) }
    }
}
