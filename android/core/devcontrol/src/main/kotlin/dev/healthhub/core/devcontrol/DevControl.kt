package dev.healthhub.core.devcontrol

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The ADB control surface required by Constitution Principle VIII.
 *
 * Every user action must be invocable over ADB and every visible piece of state must be
 * observable over ADB, so that an automated run can drive the app end to end without
 * touching the screen.
 *
 * **Debug builds only.** This module is a `debugImplementation` dependency, and the entry
 * point is [DevControlProvider], which refuses any caller that is not ADB.
 *
 * Usage:
 * ```
 * adb shell content call --uri content://dev.healthhub.debug.devcontrol --method sync
 * adb shell content call --uri content://dev.healthhub.debug.devcontrol --method login \
 *     --extra email:s:a@example.com --extra password:s:secret
 * adb shell am start -a android.intent.action.VIEW -d "healthhub://feed"
 * ```
 * Results are printed by `content call` and also written to logcat under `HealthHubDev`.
 */

/** A command a feature contributes. Bound into a set, so a new module adds its own. */
interface DevCommand {
    /** The `cmd` value that selects this command. */
    val name: String

    /** One line of help, printed by the `help` command. */
    val usage: String

    /**
     * Runs the command. [args] holds the string extras from the broadcast. Returning a map
     * puts it in the result payload; throwing marks the command failed.
     */
    suspend fun run(args: Map<String, String>): Map<String, String>
}

/** Emits structured results so a script can assert on state instead of scraping the UI. */
@Singleton
class DevReporter @Inject constructor() {

    fun success(command: String, correlationId: String, payload: Map<String, String>) {
        emit("ok", command, correlationId, payload)
    }

    fun failure(command: String, correlationId: String, reason: String) {
        emit("error", command, correlationId, mapOf("reason" to reason))
    }

    /** The app's observable state, dumped as one JSON line. */
    fun state(payload: Map<String, String>) {
        Log.i(STATE_TAG, Json.encodeToString(JsonObject.serializer(), payload.toJson()))
    }

    private fun emit(
        status: String,
        command: String,
        correlationId: String,
        payload: Map<String, String>,
    ) {
        val body = JsonObject(
            mapOf(
                "status" to JsonPrimitive(status),
                "command" to JsonPrimitive(command),
                "id" to JsonPrimitive(correlationId),
            ) + payload.mapValues { JsonPrimitive(it.value) },
        )
        Log.i(TAG, Json.encodeToString(JsonObject.serializer(), body))
    }

    private fun Map<String, String>.toJson() =
        JsonObject(mapValues { JsonPrimitive(it.value) })

    companion object {
        const val TAG = "HealthHubDev"
        const val STATE_TAG = "HealthHubState"
    }
}

/**
 * Dispatches broadcasts to the registered commands.
 *
 * Commands are collected from a multibinding, so a feature module contributes its own
 * without this file — or any other existing file — being edited.
 */
@Singleton
class DevCommandRegistry @Inject constructor(
    private val commands: Set<@JvmSuppressWildcards DevCommand>,
    private val reporter: DevReporter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun dispatch(name: String, args: Map<String, String>) {
        scope.launch { call(name, args) }
    }

    /**
     * Runs a command and returns its result.
     *
     * Returning a Bundle is what lets `adb shell content call` print the outcome directly,
     * so a script can branch on it instead of tailing logcat and hoping.
     */
    suspend fun call(name: String, args: Map<String, String>): android.os.Bundle {
        val correlationId = args["id"] ?: java.util.UUID.randomUUID().toString().take(8)

        if (name == "help") {
            val help = commands.associate { it.name to it.usage } +
                ("count" to commands.size.toString())
            reporter.success("help", correlationId, help)
            return bundle("ok", correlationId, help)
        }

        val command = commands.firstOrNull { it.name == name }
        if (command == null) {
            val known = commands.joinToString(",") { it.name }
            reporter.failure(name, correlationId, "Unknown command. Known: $known")
            return bundle("error", correlationId, mapOf("reason" to "unknown command", "known" to known))
        }

        return runCatching { command.run(args) }
            .fold(
                onSuccess = { payload ->
                    reporter.success(name, correlationId, payload)
                    bundle("ok", correlationId, payload)
                },
                onFailure = { error ->
                    val reason = error.message ?: error::class.simpleName.orEmpty()
                    reporter.failure(name, correlationId, reason)
                    bundle("error", correlationId, mapOf("reason" to reason))
                },
            )
    }

    private fun bundle(
        status: String,
        correlationId: String,
        payload: Map<String, String>,
    ): android.os.Bundle = android.os.Bundle().apply {
        putString("status", status)
        putString("id", correlationId)
        payload.forEach { (key, value) -> putString(key, value) }
    }
}
