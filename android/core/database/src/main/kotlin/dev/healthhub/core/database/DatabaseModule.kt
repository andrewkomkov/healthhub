package dev.healthhub.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): HealthHubDatabase =
        Room.databaseBuilder(context, HealthHubDatabase::class.java, "healthhub.db")
            // This database is a staging buffer, not a source of truth: if a future schema
            // change makes it unreadable, discarding it costs one re-sync, whereas carrying
            // migration code for a cache costs forever.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun stagingDao(database: HealthHubDatabase): StagingDao = database.stagingDao()
}
