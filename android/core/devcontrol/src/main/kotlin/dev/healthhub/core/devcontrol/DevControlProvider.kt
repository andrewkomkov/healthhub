package dev.healthhub.core.devcontrol

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/**
 * The ADB command surface (Constitution Principle VIII), as a ContentProvider.
 *
 * A broadcast receiver would be the obvious choice, and it was the first attempt — but a
 * receiver cannot tell who sent the broadcast. Guarding it with a signature-level permission
 * locks out `adb shell` too (shell is not signed with the app key), and dropping the guard
 * would leave an exported entry point that any app on the device could use to trigger
 * sign-in or read health state.
 *
 * A provider can see its caller. `Binder.getCallingUid()` is checked against the shell and
 * root UIDs, so **only ADB can drive this** — stricter than a signature permission, which
 * would still admit any co-signed app.
 *
 * ```
 * adb shell content call --uri content://dev.healthhub.debug.devcontrol --method help
 * adb shell content call --uri content://dev.healthhub.debug.devcontrol --method sync
 * adb shell content call --uri content://dev.healthhub.debug.devcontrol --method login \
 *     --extra email:s:a@example.com --extra password:s:correct-horse-battery
 * ```
 *
 * The result comes back on stdout as a Bundle, and is also written to logcat under
 * `HealthHubDev` so a long-running command can be followed.
 */
class DevControlProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DevControlEntryPoint {
        fun registry(): DevCommandRegistry
        fun reporter(): DevReporter
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = Binder.getCallingUid()
        if (caller != Process.SHELL_UID && caller != Process.ROOT_UID) {
            // Not ADB. Refuse without hinting at what is here.
            return Bundle().apply { putString("status", "error"); putString("reason", "forbidden") }
        }

        val context = context ?: return errorBundle("no context")
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DevControlEntryPoint::class.java,
        )

        val args = buildMap {
            extras?.keySet()?.forEach { key ->
                extras.getString(key)?.let { put(key, it) }
            }
            arg?.let { put("arg", it) }
        }

        // Blocking is correct here: `adb shell content call` waits for the result, and a
        // scripted run needs the command to have finished before it asserts on state.
        return runBlocking { entryPoint.registry().call(method, args) }
    }

    private fun errorBundle(reason: String) = Bundle().apply {
        putString("status", "error")
        putString("reason", reason)
    }

    /* ContentProvider's table-shaped API is unused; this provider exists only for call(). */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
