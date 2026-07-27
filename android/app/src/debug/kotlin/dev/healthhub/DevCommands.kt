package dev.healthhub

import android.content.Context
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.designsystem.DynamicColors
import dev.healthhub.core.devcontrol.DevCommand
import dev.healthhub.core.database.StagingDao
import dev.healthhub.core.devcontrol.DevReporter
import dev.healthhub.core.healthconnect.HealthConnectSource
import dev.healthhub.core.network.HealthHubApi
import dev.healthhub.core.network.TokenStore
import dev.healthhub.core.sync.SyncEngine
import javax.inject.Inject
import javax.inject.Named

/**
 * The commands that make the app fully drivable over ADB (Constitution Principle VIII).
 *
 * Debug builds only. Every user action has one here, and `state` dumps what the athlete can
 * see, so an automated run can assert on it rather than scraping the screen.
 *
 * ```
 * adb shell am broadcast -a dev.healthhub.DEV_COMMAND --es cmd help
 * adb shell am broadcast -a dev.healthhub.DEV_COMMAND --es cmd register \
 *     --es email a@example.com --es password correct-horse-battery --es name Andrew
 * adb shell am broadcast -a dev.healthhub.DEV_COMMAND --es cmd sync
 * adb shell am broadcast -a dev.healthhub.DEV_COMMAND --es cmd state
 * adb logcat -s HealthHubDev:I HealthHubState:I
 * ```
 */

class RegisterCommand @Inject constructor(
    private val api: HealthHubApi,
    @Named("appVersion") private val appVersion: String,
) : DevCommand {
    override val name = "register"
    override val usage = "--es email <e> --es password <p> --es name <n> — create an account and register this device"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val email = requireNotNull(args["email"]) { "email is required" }
        val password = requireNotNull(args["password"]) { "password is required" }
        val user = api.register(email, password, args["name"] ?: "Athlete")
        val device = api.registerDevice(Build.MODEL ?: "Android", appVersion)
        return mapOf("userId" to user.id, "email" to user.email, "deviceId" to device.id)
    }
}

class LoginCommand @Inject constructor(
    private val api: HealthHubApi,
    @Named("appVersion") private val appVersion: String,
) : DevCommand {
    override val name = "login"
    override val usage = "--es email <e> --es password <p> — sign in and register this device"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val user = api.login(
            requireNotNull(args["email"]) { "email is required" },
            requireNotNull(args["password"]) { "password is required" },
        )
        val device = api.registerDevice(Build.MODEL ?: "Android", appVersion)
        return mapOf("userId" to user.id, "deviceId" to device.id)
    }
}

class LogoutCommand @Inject constructor(
    private val tokens: TokenStore,
    private val staging: StagingDao,
) : DevCommand {
    override val name = "logout"
    override val usage = "— forget the device token, cached feed and sync position"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        tokens.clearAll()
        // The sync cursor belongs to the account that was signed in. Leaving it behind makes
        // the next account's first sync read from "now" and find nothing — which looks
        // exactly like a broken sync button.
        staging.clearState()
        staging.clearCache()
        return mapOf("cleared" to "true")
    }
}

class SyncCommand @Inject constructor(private val engine: SyncEngine) : DevCommand {
    override val name = "sync"
    override val usage =
        "[--extra days:s:<n>] — sync now; days re-reads that far back instead of resuming"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        // Re-reading a window is safe at any time: uploads are idempotent on the Health
        // Connect record id, so a repeat produces updates rather than duplicates.
        val since = args["days"]?.toLongOrNull()?.let {
            java.time.Instant.now().minusSeconds(it * 24 * 60 * 60)
        }
        val report = engine.sync(since)
        return mapOf(
            "status" to report.status.name.lowercase(),
            "sessions" to report.sessionsSynced.toString(),
            "samples" to report.samplesSynced.toString(),
            "failures" to report.failures.size.toString(),
            "unhandled" to report.unhandledTypes.joinToString(","),
            "message" to (report.message ?: ""),
        )
    }
}

class FeedCommand @Inject constructor(private val api: HealthHubApi) : DevCommand {
    override val name = "feed"
    override val usage = "— fetch the first page of the feed and report what came back"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val page = api.feed(limit = args["limit"]?.toIntOrNull() ?: 10)
        return mapOf(
            "count" to page.activities.size.toString(),
            "hasMore" to (page.nextCursor != null).toString(),
            "first" to (page.activities.firstOrNull()?.title ?: ""),
            "firstId" to (page.activities.firstOrNull()?.id ?: ""),
        )
    }
}

/** Pushes this phone's Material You palette so the web client wears the same colours. */
class ThemeCommand @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: HealthHubApi,
) : DevCommand {
    override val name = "theme"
    override val usage = "— upload this device's Material You palette"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val extracted = DynamicColors.extract(context)
            ?: return mapOf("uploaded" to "false", "reason" to "dynamic colour unsupported")
        val (light, dark) = extracted
        api.putTheme(light, dark)
        return mapOf(
            "uploaded" to "true",
            "primary" to (light["primary"] ?: ""),
            "roles" to light.size.toString(),
        )
    }
}

/** The observable-state dump an automated run asserts against. */
class StateCommand @Inject constructor(
    private val tokens: TokenStore,
    private val healthConnect: HealthConnectSource,
    private val reporter: DevReporter,
) : DevCommand {
    override val name = "state"
    override val usage = "— dump auth, permission and Health Connect state as JSON"

    override suspend fun run(args: Map<String, String>): Map<String, String> {
        val missing = runCatching { healthConnect.missingPermissions() }.getOrDefault(emptySet())
        val payload = mapOf(
            "registered" to (tokens.deviceToken() != null).toString(),
            "deviceId" to (tokens.deviceId() ?: ""),
            "healthConnect" to healthConnect.availability.name,
            "missingPermissions" to missing.size.toString(),
            "missing" to missing.joinToString(",") { it.substringAfterLast('.') },
            "dynamicColor" to DynamicColors.isSupported.toString(),
        )
        reporter.state(payload)
        return payload
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DevCommandsModule {
    @Binds @IntoSet abstract fun register(command: RegisterCommand): DevCommand
    @Binds @IntoSet abstract fun login(command: LoginCommand): DevCommand
    @Binds @IntoSet abstract fun logout(command: LogoutCommand): DevCommand
    @Binds @IntoSet abstract fun sync(command: SyncCommand): DevCommand
    @Binds @IntoSet abstract fun feed(command: FeedCommand): DevCommand
    @Binds @IntoSet abstract fun theme(command: ThemeCommand): DevCommand
    @Binds @IntoSet abstract fun state(command: StateCommand): DevCommand
}
