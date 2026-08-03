package dev.healthhub.core.sync

import android.util.Log
import dev.healthhub.core.database.StagingDao
import dev.healthhub.core.network.HealthHubApi
import dev.healthhub.core.network.TokenStore
import dev.healthhub.core.preferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signing out, in one place.
 *
 * It was in two: the debug `logout` command cleared the token and the staging tables, and
 * nothing on the phone's own screens could sign out at all. Two consequences, both real —
 * an athlete who had signed in on someone else's phone had no way back off it, and the rule
 * that makes sign-out correct (below) existed as a comment in a debug-only file where the
 * next person to write a sign-out would not find it.
 *
 * Four things go, and the third is the one that is easy to miss:
 *
 * 1. the device token, so nothing can be read with this installation's credential again;
 * 2. the cached feed, because it is the previous account's workouts;
 * 3. **the sync cursor**, because it belongs to the account that was signed in. Leave it and
 *    the next account's first sync resumes from "now", reads nothing, and reports success —
 *    which presents as a sync button that does nothing at all;
 * 4. the mirrored unit system, so the next athlete's first feed is not silently in miles.
 *
 * The appearance settings deliberately stay: they are about this phone and this person's eyes,
 * not about the account, and re-picking dark mode on every sign-in would be an odd thing to
 * make somebody do.
 */
@Singleton
class AccountSession @Inject constructor(
    private val api: HealthHubApi,
    private val tokens: TokenStore,
    private val staging: StagingDao,
    private val preferences: AppPreferences,
) {

    /**
     * Ends this installation's session.
     *
     * The server side is best-effort and the local side is not. An athlete who taps sign out on
     * a train has still asked to be signed out; refusing because the network is down would leave
     * the credential on the phone, which is the opposite of what they asked for. What survives a
     * failed revoke is a device row they can revoke from a browser — and the token is useless on
     * this phone either way, because it has been erased from it.
     */
    suspend fun signOut() {
        val deviceId = tokens.deviceId()

        runCatching {
            if (deviceId != null) api.revokeDevice(deviceId)
            api.logout()
        }.onFailure {
            Log.w(TAG, "Could not revoke this device server-side; clearing it locally anyway", it)
        }

        tokens.clearAll()
        staging.clearState()
        staging.clearCache()
        preferences.clearAccountScoped()
    }

    private companion object {
        const val TAG = "AccountSession"
    }
}
