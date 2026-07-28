package dev.healthhub.feature.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * What the system says about the install session.
 *
 * The install is not this app's to perform: the session comes back asking for the athlete's
 * confirmation, and every outcome after that — success, refusal, a failure the platform names
 * — arrives here. Without this receiver the progress bar would run forever, because nothing
 * else knows the session ended.
 */
@AndroidEntryPoint
class InstallResultReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: UpdateRepository

    companion object {
        /** Names the broadcast in a log line; the intent itself is explicit. */
        const val ACTION = "dev.healthhub.INSTALL_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The dialog is started while the app is on screen — the athlete pressed the
                // button a moment ago — so the background-activity-start restriction does not
                // apply here.
                val confirm = confirmationIntent(intent)
                if (confirm == null) {
                    repository.fail("the system did not offer a confirmation dialog")
                    return
                }
                runCatching {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { repository.fail(it.message ?: "the installer could not be opened") }
            }

            PackageInstaller.STATUS_SUCCESS -> repository.onInstalled()

            // The athlete said no. That is an answer, not an error, and it leaves the offer
            // standing for next time.
            PackageInstaller.STATUS_FAILURE_ABORTED -> repository.onInstallAbandoned()

            else -> repository.fail(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "the install failed ($status)",
            )
        }
    }

    /**
     * The typed overload landed in API 33 and this app supports 28, where the only way to
     * read the extra is the deprecated one.
     */
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
