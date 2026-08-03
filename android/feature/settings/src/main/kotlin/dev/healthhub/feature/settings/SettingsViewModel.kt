package dev.healthhub.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.healthhub.core.model.UnitSystem
import dev.healthhub.core.network.ApiException
import dev.healthhub.core.network.HealthHubApi
import dev.healthhub.core.preferences.AppPreferences
import dev.healthhub.core.preferences.ThemeMode
import dev.healthhub.core.sync.AccountSession
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val units: UnitSystem = UnitSystem.METRIC,
    val email: String? = null,
    val displayName: String? = null,
    /**
     * False once the server has said this installation holds no usable credential.
     *
     * Distinct from "the address has not arrived yet", which is what a null [email] means, and
     * the distinction is the whole point: the card used to read "Signed in" over an em dash and
     * offer Sign out to somebody who was not signed in at all. Found on a Pixel.
     */
    val signedIn: Boolean = true,
    /** True while the athlete is being signed out; the screen must not offer it twice. */
    val signingOut: Boolean = false,
    /** True once the token is gone, which is the screen's cue to send them to sign-in. */
    val signedOut: Boolean = false,
    /**
     * Something the athlete asked for did not happen, in the failure's own words.
     *
     * Not a resource id: there is no catalogue of every message a network stack can produce,
     * and one generic sentence for all of them would hide the detail that makes a failure
     * diagnosable. The sentence *around* it is translated; the cause is quoted.
     */
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val api: HealthHubApi,
    private val session: AccountSession,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                preferences.themeMode,
                preferences.dynamicColor,
                preferences.unitSystem,
            ) { mode, dynamic, units -> Triple(mode, dynamic, units) }
                .collect { (mode, dynamic, units) ->
                    _state.value = _state.value.copy(
                        themeMode = mode,
                        dynamicColor = dynamic,
                        units = units,
                    )
                }
        }

        loadAccount()
    }

    /**
     * Who is signed in.
     *
     * Failing quietly is right here and only here: the appearance settings above work with no
     * connection at all, and refusing to draw the screen because one card cannot be filled in
     * would take the theme switch away with it.
     */
    private fun loadAccount() {
        viewModelScope.launch {
            runCatching { api.me() }.onFailure { failure ->
                // 401 is the only answer that means "not signed in". Anything else — no
                // connection, a 500 — leaves the question open, and claiming the athlete is
                // signed out because their train went into a tunnel is its own small lie.
                if (failure is ApiException && failure.status == 401) {
                    _state.value = _state.value.copy(signedIn = false)
                }
            }.onSuccess { user ->
                _state.value = _state.value.copy(
                    email = user.email,
                    displayName = user.displayName,
                    signedIn = true,
                )
                // Whatever the account says wins over the mirror. This is the same write the
                // detail screen makes; whichever screen the athlete opens first, the phone
                // converges on the account's answer.
                preferences.setUnitSystem(
                    if (user.unitSystem.equals("imperial", ignoreCase = true)) {
                        UnitSystem.IMPERIAL
                    } else {
                        UnitSystem.METRIC
                    },
                )
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferences.setDynamicColor(enabled) }
    }

    /**
     * Change the unit system, on the account rather than on the phone.
     *
     * Written locally first so the switch moves under the finger, then sent. A failed write is
     * rolled back rather than left on screen: a switch that says imperial over an account that
     * says metric is a browser rendering the same ride differently, which is the disagreement
     * this preference is account-level to prevent.
     */
    fun setUnits(units: UnitSystem) {
        val previous = _state.value.units
        if (units == previous) return

        viewModelScope.launch {
            preferences.setUnitSystem(units)
            runCatching {
                api.patchMe(unitSystem = if (units == UnitSystem.IMPERIAL) "imperial" else "metric")
            }.onFailure { failure ->
                preferences.setUnitSystem(previous)
                _state.value = _state.value.copy(
                    message = "Could not save your units: ${failure.message ?: "no connection"}",
                )
            }
        }
    }

    fun signOut() {
        if (_state.value.signingOut) return
        _state.value = _state.value.copy(signingOut = true, message = null)

        viewModelScope.launch {
            session.signOut()
            // `signOut` does not throw: the server half is best-effort and the local half
            // always happens, so by here the credential is gone whatever the network did.
            _state.value = _state.value.copy(signingOut = false, signedOut = true)
        }
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }
}
