package dev.healthhub.core.healthconnect

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which health domains the athlete has switched on.
 *
 * This is what makes the permission request narrow (Constitution Principle IV). Everything
 * except workouts starts **off**, so the first grant dialogue an athlete ever sees asks for
 * exercise data and nothing else; sleep and recovery are requested at the moment they turn on
 * the screen that uses them, where the reason is on the same page as the switch.
 *
 * Turning a domain off does not delete anything already synced — nothing is ever deleted. It
 * stops the next sync reading that domain, and the sync report says so by name rather than
 * leaving the absence to be discovered.
 *
 * Plain SharedPreferences on purpose: this is a display preference, not a credential, and
 * adding a persistence dependency to the module that owns the record registry would be a poor
 * trade for four booleans.
 */
@Singleton
class HealthFeatures @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences("health_features", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(readStored())

    val enabled: StateFlow<Set<HealthDomain>> = _enabled.asStateFlow()

    fun isEnabled(domain: HealthDomain): Boolean = domain in _enabled.value

    /** Domains the athlete has not switched on. Named in the sync report so silence is impossible. */
    fun disabled(): List<HealthDomain> = HealthDomain.entries.filterNot { it in _enabled.value }

    fun setEnabled(domain: HealthDomain, on: Boolean) {
        // Workouts cannot be switched off: the feed, the archive and every metric in the app
        // are that domain. Offering a switch that empties the product is not a preference.
        if (domain == HealthDomain.ALWAYS_ON) return

        preferences.edit().putBoolean(domain.slug, on).apply()
        _enabled.value = readStored()
    }

    private fun readStored(): Set<HealthDomain> = HealthDomain.entries
        .filterTo(mutableSetOf()) { domain ->
            domain == HealthDomain.ALWAYS_ON || preferences.getBoolean(domain.slug, false)
        }
}
