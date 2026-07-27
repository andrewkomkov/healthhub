package dev.healthhub.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // Telemetry uploads are large and mobile connections are slow; a short write timeout
        // would abort a legitimate upload of a long ride.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * The deployment this build talks to, injected from BuildConfig by the app module so
     * core:network stays free of build-specific knowledge.
     */
    @Provides
    @Named("baseUrl")
    fun baseUrl(@Named("configuredBaseUrl") configured: String): String = configured.trimEnd('/')
}
