package dev.healthhub.feature.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UpdatesUiState(
    val currentVersion: String = "",
    val available: AvailableUpdate? = null,
    val progress: UpdateProgress? = null,
    val failure: UpdateFailure? = null,
    val checking: Boolean = false,
    val lastCheck: Long = 0,
    val autoCheck: Boolean = true,
    val dismissed: String? = null,
    /** False on a debug build, whose package the release APK cannot replace. */
    val installsInPlace: Boolean = true,
) {
    /** The banner is for an update the athlete has neither seen through nor waved away. */
    val bannerUpdate: AvailableUpdate?
        get() = available?.takeIf { it.version != dismissed && progress == null }
}

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val repository: UpdateRepository,
) : ViewModel() {

    val releasesUrl: String get() = repository.releasesUrl

    val state: StateFlow<UpdatesUiState> = combine(
        repository.available,
        repository.progress,
        repository.failure,
        repository.checking,
        combine(repository.lastCheck, repository.autoCheck, repository.dismissed, ::Triple),
    ) { available, progress, failure, checking, prefs ->
        val (lastCheck, autoCheck, dismissed) = prefs
        UpdatesUiState(
            currentVersion = repository.currentVersion,
            available = available,
            progress = progress,
            failure = failure,
            checking = checking,
            lastCheck = lastCheck,
            autoCheck = autoCheck,
            dismissed = dismissed,
            installsInPlace = repository.installsInPlace,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UpdatesUiState(
            currentVersion = repository.currentVersion,
            installsInPlace = repository.installsInPlace,
        ),
    )

    /** The quiet check on app start. Does nothing if it ran within the last twelve hours. */
    fun checkIfDue() {
        viewModelScope.launch { repository.checkIfDue() }
    }

    fun checkNow() {
        viewModelScope.launch { repository.check() }
    }

    fun setAutoCheck(enabled: Boolean) = repository.setAutoCheck(enabled)

    fun dismiss() {
        state.value.available?.let { repository.dismiss(it.version) }
    }

    fun clearFailure() = repository.clearFailure()

    /**
     * Starts the install, or asks for the one thing the athlete has to grant by hand.
     *
     * "Install unknown apps" is granted per app in system settings and cannot be requested
     * from a dialog, so the only useful thing to do without it is open that settings page.
     * A build whose package the release cannot replace — a debug one — opens the release page
     * instead, which is the same as installing it by hand.
     */
    fun install(context: Context) {
        val update = state.value.available ?: return
        if (update.apkUrl == null || !repository.installsInPlace) {
            open(context, update.pageUrl)
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            repository.fail(
                "Android needs permission to install apps from HealthHub. Turn on " +
                    "\"Allow from this source\", then press Install again.",
            )
            val opened = runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (!opened) open(context, update.pageUrl)
            return
        }
        viewModelScope.launch { repository.install(update) }
    }

    fun openReleasePage(context: Context) {
        open(context, state.value.available?.pageUrl ?: releasesUrl)
    }

    private fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
