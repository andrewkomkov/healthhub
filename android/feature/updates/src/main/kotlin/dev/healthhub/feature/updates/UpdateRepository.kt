package dev.healthhub.feature.updates

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the update is doing right now, for the bar on the card. */
data class UpdateProgress(
    val stage: Stage,
    val bytes: Long = 0,
    val total: Long = 0,
) {
    enum class Stage { DOWNLOAD, VERIFY, INSTALL }

    /** The fraction downloaded, or null while the length of the response is unknown. */
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Why the last check or install stopped, in a sentence the athlete can act on.
 *
 * `severe` separates "this went wrong" from "this stopped, and here is what to do next" — a
 * cancelled install is not an error, but saying nothing at all leaves a progress bar that
 * simply vanished.
 */
data class UpdateFailure(val message: String, val severe: Boolean = true)

/**
 * Whether a newer release exists, and everything the app knows about getting it installed.
 *
 * A singleton because two surfaces read the same answer — the banner over the navigation host
 * and the Updates screen — and because the install continues while the athlete moves between
 * them. State lives here rather than in the ViewModel for that reason.
 *
 * This is the only request the app makes to anything other than its own Worker: one
 * unauthenticated GET to `api.github.com`, carrying no account, no device id and no health
 * data. It can be switched off, and switching it off stops the app from talking to GitHub at
 * all rather than merely hiding the result.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val installer: UpdateInstaller,
    @Named("appVersion") val currentVersion: String,
    @Named("releasesRepo") private val repo: String,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _available = MutableStateFlow<AvailableUpdate?>(null)
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    private val _progress = MutableStateFlow<UpdateProgress?>(null)
    val progress: StateFlow<UpdateProgress?> = _progress.asStateFlow()

    private val _failure = MutableStateFlow<UpdateFailure?>(null)
    val failure: StateFlow<UpdateFailure?> = _failure.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _lastCheck = MutableStateFlow(prefs.getLong(KEY_LAST_CHECK, 0L))
    val lastCheck: StateFlow<Long> = _lastCheck.asStateFlow()

    private val _autoCheck = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CHECK, true))
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    /**
     * The version the athlete waved away, so the banner stops offering it.
     *
     * Deliberately not persisted: "later" means later, and a version dismissed a week ago
     * should be offered again rather than hidden forever. The Updates screen always shows it.
     */
    private val _dismissed = MutableStateFlow<String?>(null)
    val dismissed: StateFlow<String?> = _dismissed.asStateFlow()

    fun dismiss(version: String) {
        _dismissed.value = version
    }

    /** The releases page for this build, shown when there is no APK to install. */
    val releasesUrl: String = "https://github.com/$repo/releases/latest"

    /**
     * Whether an installed release would replace this build or sit beside it.
     *
     * A debug build's application id carries `.debug`, so the released APK is a different
     * package to the system: installing it would leave two apps on the phone rather than
     * update one. The offer is still made — checking works everywhere — but the button opens
     * the release page instead of the installer, which is the honest outcome.
     */
    val installsInPlace: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0

    fun setAutoCheck(enabled: Boolean) {
        _autoCheck.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    /**
     * The quiet check on app start: at most once every twelve hours, and only if the athlete
     * left it switched on.
     *
     * The timestamp is written before the request rather than after, so a GitHub outage costs
     * one attempt every twelve hours instead of one on every cold start.
     */
    suspend fun checkIfDue() {
        if (!_autoCheck.value) return
        if (System.currentTimeMillis() - _lastCheck.value < CHECK_INTERVAL_MS) return
        check(quiet = true)
    }

    /**
     * Asks GitHub for the latest release.
     *
     * A quiet check keeps its failure to itself: a phone with no signal on a cold start should
     * not open with an error about an update it was not asked to look for.
     */
    suspend fun check(quiet: Boolean = false): AvailableUpdate? {
        if (_checking.value) return _available.value
        _checking.value = true
        stampCheck()
        return try {
            val release = withContext(Dispatchers.IO) { fetchLatest() }
            val update = Releases.updateFrom(release, currentVersion)
            _available.value = update
            _failure.value = null
            update
        } catch (e: IOException) {
            if (!quiet) _failure.value = UpdateFailure(e.message ?: "could not reach GitHub")
            null
        } catch (e: IllegalArgumentException) {
            // A body that is not a release: a rate-limit page, or a repository slug that does
            // not exist. Both are configuration, not weather, so they are worth showing.
            if (!quiet) _failure.value = UpdateFailure("unexpected response from GitHub")
            null
        } finally {
            _checking.value = false
        }
    }

    /**
     * Downloads, verifies and hands the APK to the system installer.
     *
     * Returns when the system has taken over — the confirmation dialog and the install itself
     * are the platform's, and their outcome arrives at [InstallResultReceiver].
     */
    suspend fun install(update: AvailableUpdate) {
        if (_progress.value != null) return
        val apkUrl = update.apkUrl ?: return
        _failure.value = null
        try {
            val apk = installer.download(apkUrl, update.version, update.apkBytes) { done, total ->
                _progress.value = UpdateProgress(UpdateProgress.Stage.DOWNLOAD, done, total)
            }
            _progress.value = UpdateProgress(UpdateProgress.Stage.VERIFY)
            if (!installer.verify(apk, update.checksumUrl)) {
                apk.delete()
                fail("the download did not match the published checksum")
                return
            }
            _progress.value = UpdateProgress(UpdateProgress.Stage.INSTALL)
            installer.install(apk)
        } catch (e: IOException) {
            fail(e.message ?: "the download failed")
        }
    }

    /** The system took the update: nothing is pending any more. */
    fun onInstalled() {
        _progress.value = null
        _available.value = null
        installer.clearCache()
    }

    fun fail(message: String) {
        _progress.value = null
        _failure.value = UpdateFailure(message)
    }

    /**
     * The session ended without installing anything.
     *
     * Two things land here and they look identical from this side: the athlete pressed Cancel,
     * and Play Protect offered to scan an APK it has never seen. Choosing to scan **ends the
     * session** — the scan happens, nothing is installed, and the progress bar disappears with
     * no explanation. Verified on a Pixel 8: the second attempt then installs without a murmur.
     * So the offer stays, and the note says which button to press.
     */
    fun onInstallAbandoned() {
        _progress.value = null
        _failure.value = UpdateFailure(
            "The install stopped before it finished. If Play Protect offered to scan the " +
                "app, press Install again once it is done.",
            severe = false,
        )
    }

    fun clearFailure() {
        _failure.value = null
    }

    private fun stampCheck() {
        val now = System.currentTimeMillis()
        _lastCheck.value = now
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
    }

    private fun fetchLatest(): ReleaseDto {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "healthhub/$currentVersion")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("GitHub answered ${response.code}")
            }
            return Releases.parse(body)
        }
    }

    private companion object {
        const val PREFS = "healthhub_updates"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_AUTO_CHECK = "auto_check"
        const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
    }
}
