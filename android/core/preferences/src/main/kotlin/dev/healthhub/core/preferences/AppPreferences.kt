package dev.healthhub.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.healthhub.core.model.UnitSystem
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * How the athlete has asked the app to look and to count.
 *
 * Three settings, and they are not all the same kind of thing:
 *
 * - [themeMode] and [dynamicColor] are about *this phone*. A second device belonging to the
 *   same athlete may reasonably be set differently, and neither has any meaning on the server,
 *   so they live here and nowhere else.
 * - [unitSystem] belongs to the *account* — the browser has to render the same ride in the same
 *   units or SC-008 fails on the pair. The server owns it; this is a local mirror so that a
 *   screen has a figure to draw before `/api/auth/me` answers, and so that a feed opened with no
 *   connection is not silently metric for someone who reads in miles.
 *
 * That last point is why this exists at all rather than every screen calling `me()`: the feed
 * used to hard-code kilometres while the detail screen it opens asked the server, so an athlete
 * on imperial units read "6.44 km" on a card and "4.00 mi" one tap later. Two numbers for one
 * ride is exactly what SC-008 forbids, and the cause was that only one of the two screens knew
 * the preference existed.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val store: DataStore<Preferences> = context.appPreferencesStore

    /**
     * A read failure is a default, never a crash.
     *
     * DataStore surfaces a corrupt or unreadable file as an `IOException` on the flow, and an
     * unreadable preferences file is not a reason to refuse to draw the app — the athlete would
     * have no way back in to fix it. Anything else is a real defect and is left to propagate.
     */
    private val preferences: Flow<Preferences> = store.data.catch { failure ->
        if (failure is IOException) emit(emptyPreferences()) else throw failure
    }

    val themeMode: Flow<ThemeMode> = preferences.map { values ->
        ThemeMode.fromKey(values[KEY_THEME_MODE])
    }

    val dynamicColor: Flow<Boolean> = preferences.map { values ->
        // On by default. The athlete's wallpaper palette is a better fit for their device than
        // ours, and the same scheme is uploaded so the web client can wear it too.
        values[KEY_DYNAMIC_COLOR] ?: true
    }

    val unitSystem: Flow<UnitSystem> = preferences.map { values ->
        if (values[KEY_UNIT_SYSTEM].equals("imperial", ignoreCase = true)) {
            UnitSystem.IMPERIAL
        } else {
            UnitSystem.METRIC
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[KEY_THEME_MODE] = mode.key }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    /**
     * Mirror what the account says.
     *
     * Called both by the settings screen, which has just told the server, and by any screen that
     * happened to read `/api/auth/me` — so the value on this phone converges on the account's
     * whether or not the athlete changed it here.
     */
    suspend fun setUnitSystem(units: UnitSystem) {
        store.edit { it[KEY_UNIT_SYSTEM] = if (units == UnitSystem.IMPERIAL) "imperial" else "metric" }
    }

    /**
     * Sign-out. The appearance survives; the account's units do not.
     *
     * Leaving imperial behind for the next person to sign in on this phone would render their
     * first feed in units they never chose, and there is nothing on screen to explain why.
     */
    suspend fun clearAccountScoped() {
        store.edit { it.remove(KEY_UNIT_SYSTEM) }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_UNIT_SYSTEM = stringPreferencesKey("unit_system")
    }
}

/**
 * Which appearance to draw in.
 *
 * [SYSTEM] rather than a boolean default, because "follow the phone" is a third state and not a
 * synonym for either of the other two — an athlete who has scheduled dark mode at sunset wants
 * this app to follow that schedule, not to be pinned to whichever side of it they last opened.
 */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Follow the system"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        private val byKey = entries.associateBy(ThemeMode::key)
        fun fromKey(key: String?): ThemeMode = byKey[key] ?: SYSTEM
    }
}

private val Context.appPreferencesStore by preferencesDataStore(name = "healthhub.settings")
