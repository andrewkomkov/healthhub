package dev.healthhub.feature.activity

import dev.healthhub.core.network.TokenStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reading one activity and its telemetry.
 *
 * Two calls that the shared API client does not carry, kept here rather than pushed into
 * `core:network` so that attaching this screen touched no module outside `feature:activity`
 * (Constitution Principle VII, SC-012). It reuses the shared [OkHttpClient] and [TokenStore],
 * so timeouts and credentials still have exactly one definition.
 */
@Singleton
class ActivityRepository @Inject constructor(
    private val client: OkHttpClient,
    private val tokens: TokenStore,
    @Named("baseUrl") private val baseUrl: String,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun detail(id: String): ActivityDetailDto = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/activities/$id")
            .get()
            .authorize()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Activity request failed with ${response.code}")
            }
            json.decodeFromString<ActivityDetailEnvelope>(body).activity
        }
    }

    /**
     * The raw `.hht` for one variant, or null when it has not been uploaded yet.
     *
     * A 404 is "still syncing", not an error: the phone uploads the summary before the
     * telemetry, so a freshly synced activity is briefly summary-only.
     */
    suspend fun telemetry(id: String, variant: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/activities/$id/telemetry?variant=$variant")
            .get()
            .authorize()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) {
                throw IOException("Telemetry request failed with ${response.code}")
            }
            gunzipIfNeeded(response.body?.bytes() ?: ByteArray(0))
        }
    }

    private fun Request.Builder.authorize(): Request.Builder = apply {
        tokens.deviceToken()?.let { header("authorization", "Bearer $it") }
        tokens.sessionCookie()?.let { header("cookie", it) }
    }

    /**
     * Telemetry is stored gzipped in R2 and served back with the `Content-Encoding` header R2
     * recorded. Cloudflare compresses the response again on the way out, OkHttp transparently
     * unwraps that one layer, and what is left in hand is still the gzipped stored object.
     * Nothing anywhere logs a word about it; the symptom is the `.hht` reader complaining about
     * magic `\x1f\x8b`. The web client sniffs for exactly this and so does the phone.
     */
    private fun gunzipIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != GZIP_MAGIC_0 || bytes[1] != GZIP_MAGIC_1) return bytes
        return GZIPInputStream(bytes.inputStream()).use { input ->
            val out = ByteArrayOutputStream(bytes.size * 4)
            input.copyTo(out, DEFAULT_BUFFER_SIZE)
            out.toByteArray()
        }
    }

    private companion object {
        val GZIP_MAGIC_0 = 0x1f.toByte()
        val GZIP_MAGIC_1 = 0x8b.toByte()
    }
}
