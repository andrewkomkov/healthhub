package dev.healthhub.core.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the device token and session cookie live.
 *
 * Encrypted at rest: the device token is a long-lived credential that can read every workout
 * the athlete has ever recorded, so leaving it in plain SharedPreferences would put their
 * whole health history behind a rooted phone or a careless backup.
 */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val preferences by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _isRegistered = MutableStateFlow(false)

    /** Whether this installation currently holds a usable device token. */
    val isRegistered: StateFlow<Boolean> = _isRegistered

    init {
        _isRegistered.value = deviceToken() != null
    }

    fun deviceToken(): String? = preferences.getString(KEY_DEVICE_TOKEN, null)

    fun deviceId(): String? = preferences.getString(KEY_DEVICE_ID, null)

    fun sessionCookie(): String? = preferences.getString(KEY_SESSION_COOKIE, null)

    fun saveDeviceToken(token: String, deviceId: String) {
        preferences.edit()
            .putString(KEY_DEVICE_TOKEN, token)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
        _isRegistered.value = true
    }

    fun saveSessionCookie(cookie: String) {
        preferences.edit().putString(KEY_SESSION_COOKIE, cookie).apply()
    }

    /**
     * Called when the server rejects the token — the device was revoked from another client,
     * or the account was deleted. Syncing must stop rather than retry forever.
     */
    fun clearDeviceToken() {
        preferences.edit().remove(KEY_DEVICE_TOKEN).remove(KEY_DEVICE_ID).apply()
        _isRegistered.value = false
    }

    fun clearAll() {
        preferences.edit().clear().apply()
        _isRegistered.value = false
    }

    private companion object {
        const val FILE_NAME = "healthhub.credentials"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SESSION_COOKIE = "session_cookie"
    }
}
