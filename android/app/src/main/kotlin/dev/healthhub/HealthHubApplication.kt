package dev.healthhub

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HealthHubApplication : Application(), Configuration.Provider {

    /**
     * Supplied by Hilt so `@HiltWorker` workers can be constructed with their dependencies.
     * The manifest removes WorkManager's default initialiser for the same reason.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // feature:auth builds the Auth0 URL for the browser hand-off and cannot read :app's
        // BuildConfig, so the deployment address is handed to it once at startup.
        dev.healthhub.feature.auth.BuildConfigBridge.baseUrl = BuildConfig.BASE_URL
    }
}
