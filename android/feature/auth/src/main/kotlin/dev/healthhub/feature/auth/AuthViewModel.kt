package dev.healthhub.feature.auth

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.healthhub.core.network.ApiException
import dev.healthhub.core.network.HealthHubApi
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val mode: Mode = Mode.SIGN_IN,
    val busy: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
) {
    enum class Mode { SIGN_IN, SIGN_UP }
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val api: HealthHubApi,
    @Named("appVersion") private val appVersion: String,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun toggleMode() {
        _state.value = _state.value.copy(
            mode = if (_state.value.mode == AuthUiState.Mode.SIGN_IN) {
                AuthUiState.Mode.SIGN_UP
            } else {
                AuthUiState.Mode.SIGN_IN
            },
            error = null,
        )
    }

    fun submit(email: String, password: String, displayName: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching {
                if (_state.value.mode == AuthUiState.Mode.SIGN_UP) {
                    api.register(email, password, displayName)
                } else {
                    api.login(email, password)
                }
                // Sign-in yields a session; the app then exchanges it for a long-lived device
                // token, which is what background sync authenticates with.
                api.registerDevice(name = Build.MODEL ?: "Android", appVersion = appVersion)
            }
                .onSuccess { _state.value = _state.value.copy(busy = false, signedIn = true) }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        busy = false,
                        error = when (error) {
                            is ApiException -> error.message
                            else -> "Could not reach HealthHub. Check your connection."
                        },
                    )
                }
        }
    }

    /** Called after the Auth0 deep link delivers a device token directly. */
    fun onExternalSignIn() {
        _state.value = _state.value.copy(signedIn = true, busy = false, error = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
