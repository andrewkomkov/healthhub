package dev.healthhub

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/**
 * Build-specific values, provided here so the core modules stay free of BuildConfig and can
 * be compiled and tested without an application around them.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Named("configuredBaseUrl")
    fun baseUrl(): String = BuildConfig.BASE_URL

    @Provides
    @Named("appVersion")
    fun appVersion(): String = BuildConfig.VERSION_NAME

    /** Where `feature:updates` looks for a newer APK. A fork points it at its own releases. */
    @Provides
    @Named("releasesRepo")
    fun releasesRepo(): String = BuildConfig.RELEASES_REPO
}
