package dev.healthhub.feature.sources

import dev.healthhub.core.network.TokenStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The three calls the archive and the source order need that the shared client does not carry.
 *
 * Kept here rather than pushed into `core:network` for the same reason `ActivityRepository`
 * is: attaching this feature must touch no module outside `feature:sources` (SC-012). It
 * reuses the shared [OkHttpClient] and [TokenStore], so timeouts and credentials still have
 * exactly one definition.
 */
@Singleton
class SourcesRepository @Inject constructor(
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
     * Outlives the view models on purpose: a priority write is debounced, and an athlete who
     * reorders their sources and immediately walks back to the feed has still decided. The
     * screen hands its last pending order here on the way out.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One page of the recordings that are not currently representing their workout. */
    suspend fun archivePage(cursor: String? = null, limit: Int = PAGE_SIZE): ArchivePageDto =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/activities".toHttpUrl().newBuilder()
                .addQueryParameter("view", "archive")
                .addQueryParameter("limit", limit.toString())
                .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
                .build()

            client.newCall(Request.Builder().url(url).get().authorize().build()).execute().use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw IOException("Archive request failed with ${it.code}")
                json.decodeFromString<ArchivePageDto>(body)
            }
        }

    /**
     * Restores a recording to the feed, or sets one aside by hand.
     *
     * The Worker answers with the row as it now stands, and that row is what the screen shows —
     * including `visibilityLocked`, which is the only honest evidence that the decision will
     * survive the next sync rather than merely appearing to.
     */
    suspend fun setVisibility(activityId: String, visibility: String): ArchivedActivityDto =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/api/activities/$activityId")
                .patch(json.encodeToString(VisibilityPatch(visibility)).toRequestBody(jsonMedia))
                .authorize()
                .build()

            client.newCall(request).execute().use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw IOException("Visibility change failed with ${it.code}")
                json.decodeFromString<ActivityEnvelopeDto>(body).activity
            }
        }

    /**
     * Stores the whole trust order.
     *
     * The whole list every time, never a partial write: two sources claiming the same rank is
     * a state the phone cannot resolve on the next sync.
     */
    suspend fun putOrder(entries: List<SourceOrderEntry>) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/sources")
            .put(json.encodeToString(SourceOrderRequest(entries)).toRequestBody(jsonMedia))
            .authorize()
            .build()

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("Source order write failed with ${it.code}")
        }
    }

    /**
     * Last-gasp write for an order that was still inside its debounce when the screen went
     * away. Failures are swallowed: there is no longer a screen to tell, and the next visit
     * reads the stored order back from the server rather than trusting anything held here.
     */
    fun putOrderInBackground(entries: List<SourceOrderEntry>) {
        scope.launch { runCatching { putOrder(entries) } }
    }

    private fun Request.Builder.authorize(): Request.Builder = apply {
        tokens.deviceToken()?.let { header("authorization", "Bearer $it") }
        tokens.sessionCookie()?.let { header("cookie", it) }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
