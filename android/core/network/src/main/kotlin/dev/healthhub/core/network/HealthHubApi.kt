package dev.healthhub.core.network

import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A failure the API reported, carrying the documented error code. */
class ApiException(val status: Int, val code: String, override val message: String) :
    IOException(message)

/**
 * The client for the Worker contract.
 *
 * Deliberately hand-written over OkHttp rather than generated: the surface is a dozen calls,
 * and one of them streams a multi-megabyte file from disk, which is exactly the case
 * generated clients handle badly.
 */
@Singleton
class HealthHubApi @Inject constructor(
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
    private val binaryMedia = "application/octet-stream".toMediaType()

    /* ------------------------------------------------------------------ auth */

    /**
     * The password is pre-hashed here rather than in `feature:auth`, so that every way into
     * the app — the screen, and the debug `register` ADB command — sends the same thing.
     */
    suspend fun register(email: String, password: String, displayName: String): UserDto {
        val proof = PasswordProofs.proof(email, password)
        return post(
            "/api/auth/register",
            RegisterRequest(email, displayName, listOf(proof)),
            authenticated = false,
        ).decode<UserEnvelope>().user
    }

    /**
     * The raw password rides along only so an account created before the pre-hash amendment
     * can be verified and migrated on the way through; the Worker ignores it for every account
     * that already carries a client-scheme record.
     */
    suspend fun login(email: String, password: String): UserDto {
        val proof = PasswordProofs.proof(email, password)
        return post(
            "/api/auth/login",
            LoginRequest(email, password, listOf(proof)),
            authenticated = false,
        ).decode<UserEnvelope>().user
    }

    suspend fun me(): UserDto = get("/api/auth/me").decode<UserEnvelope>().user

    /**
     * Exchanges the current session for a long-lived device token.
     *
     * The token comes back exactly once — only its hash is stored server-side — so it is
     * persisted immediately rather than returned to the caller to look after.
     */
    suspend fun registerDevice(name: String, appVersion: String): DeviceDto {
        val response = post(
            "/api/devices",
            DeviceRegistrationRequest(name = name, appVersion = appVersion),
        ).decode<DeviceRegistrationResponse>()
        tokens.saveDeviceToken(response.token, response.device.id)
        return response.device
    }

    /* -------------------------------------------------------------- activities */

    suspend fun uploadActivity(request: ActivityUploadRequest): UploadedActivity =
        post("/api/activities", request).decode<ActivityUploadResponse>().activity

    /**
     * Streams a `.hht` file straight from disk.
     *
     * Never read into memory first: a million-sample activity is tens of megabytes, and
     * buffering it is precisely how the app gets killed on a mid-range phone (SC-003).
     */
    suspend fun uploadTelemetry(activityId: String, file: File, variant: String, gzipped: Boolean) {
        val request = Request.Builder()
            .url("$baseUrl/api/activities/$activityId/telemetry?variant=$variant")
            .put(file.asRequestBody(binaryMedia))
            .apply { if (gzipped) header("content-encoding", "gzip") }
            .authorize()
            .build()
        execute(request).close()
    }

    suspend fun feed(cursor: String? = null, limit: Int = 30): FeedResponse {
        val url = "$baseUrl/api/activities".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
            .build()
        return execute(Request.Builder().url(url).get().authorize().build())
            .decode<FeedResponse>()
    }

    /* -------------------------------------------------------------------- sync */

    suspend fun cursors(): List<CursorDto> = get("/api/sync/cursors").decode<CursorsEnvelope>().cursors

    /**
     * Advances the sync cursors.
     *
     * Only ever called after the corresponding uploads are confirmed — that ordering is the
     * entire resumability story, and reversing it would lose data on an interrupted sync.
     */
    suspend fun putCursors(cursors: List<CursorDto>) {
        put("/api/sync/cursors", CursorsEnvelope(cursors)).close()
    }

    suspend fun postReport(report: SyncReportRequest) {
        post("/api/sync/reports", report).close()
    }

    /* ----------------------------------------------------------------- sources */

    /** The athlete's trust order for the apps writing into Health Connect. */
    suspend fun sources(): List<SourceDto> = get("/api/sources").decode<SourcesEnvelope>().sources

    /** Reports the packages this sync saw, so they can be ordered in the app. */
    suspend fun reportSources(packages: List<SeenSource>) {
        post("/api/sources/seen", SeenSourcesRequest(packages)).close()
    }

    /* ------------------------------------------------------------------- theme */

    /** Uploads this phone's Material You palette so the web client can wear it too. */
    suspend fun putTheme(light: Map<String, String>, dark: Map<String, String>) {
        put("/api/theme", ThemeUploadRequest(light, dark)).close()
    }

    /* --------------------------------------------------------------- internals */

    private suspend inline fun <reified T> post(
        path: String,
        body: T,
        authenticated: Boolean = true,
    ): Response = execute(
        Request.Builder()
            .url("$baseUrl$path")
            .post(json.encodeToString(body).toRequestBody(jsonMedia))
            .let { if (authenticated) it.authorize() else it }
            .build(),
    )

    private suspend inline fun <reified T> put(path: String, body: T): Response = execute(
        Request.Builder()
            .url("$baseUrl$path")
            .put(json.encodeToString(body).toRequestBody(jsonMedia))
            .authorize()
            .build(),
    )

    private suspend fun get(path: String): Response =
        execute(Request.Builder().url("$baseUrl$path").get().authorize().build())

    private fun Request.Builder.authorize(): Request.Builder = apply {
        tokens.deviceToken()?.let { header("authorization", "Bearer $it") }
        tokens.sessionCookie()?.let { header("cookie", it) }
    }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            captureSessionCookie(response)
            return@withContext response
        }

        val body = response.body?.string().orEmpty()
        response.close()

        // A revoked device must stop syncing immediately rather than retrying forever.
        if (response.code == 401) tokens.clearDeviceToken()

        val detail = runCatching { json.decodeFromString<ApiErrorBody>(body).error }.getOrNull()
        throw ApiException(
            status = response.code,
            code = detail?.code ?: "internal",
            message = detail?.message ?: "Request failed with ${response.code}",
        )
    }

    /** The sign-in call returns a session cookie; the device registration call needs it. */
    private fun captureSessionCookie(response: Response) {
        val cookie = response.headers("set-cookie")
            .firstOrNull { it.startsWith("hh_session=") }
            ?.substringBefore(';')
            ?: return
        if (!cookie.endsWith("=")) tokens.saveSessionCookie(cookie)
    }

    private inline fun <reified T> Response.decode(): T = use {
        val text = body?.string().orEmpty()
        json.decodeFromString<T>(text)
    }
}
