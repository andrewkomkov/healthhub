package dev.healthhub.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The phone's own store.
 *
 * Not a mirror of D1 — a work buffer. Its job is that an interrupted sync can be resumed and
 * that the feed is browsable with no connection (FR-004, FR-014). Telemetry files are
 * referenced by path, never held as blobs, so a million-sample activity stays on disk where
 * it belongs.
 */

@Entity(tableName = "pending_activities")
data class PendingActivity(
    @PrimaryKey val sourceUid: String,
    /** The JSON body to upload, computed at ingest and stored ready to send. */
    val payload: String,
    /** Absolute paths to the staged .hht files; deleted once the upload is confirmed. */
    val fullTelemetryPath: String?,
    val previewTelemetryPath: String?,
    val sampleCount: Int,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "cached_activities")
data class CachedActivity(
    @PrimaryKey val id: String,
    val payload: String,
    val startTime: Long,
    val cachedAt: Long,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val recordType: String,
    val changeToken: String?,
    val syncedUntil: Long?,
    val updatedAt: Long,
)

@Dao
interface StagingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun stage(activity: PendingActivity)

    @Query("SELECT * FROM pending_activities ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<PendingActivity>

    @Query("SELECT COUNT(*) FROM pending_activities")
    fun pendingCount(): Flow<Int>

    @Query("DELETE FROM pending_activities WHERE sourceUid = :sourceUid")
    suspend fun clearPending(sourceUid: String)

    @Query("UPDATE pending_activities SET attempts = attempts + 1, lastError = :error WHERE sourceUid = :sourceUid")
    suspend fun recordFailure(sourceUid: String, error: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cache(activities: List<CachedActivity>)

    @Query("SELECT * FROM cached_activities ORDER BY startTime DESC LIMIT :limit")
    suspend fun cached(limit: Int = 100): List<CachedActivity>

    @Query("DELETE FROM cached_activities")
    suspend fun clearCache()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state")
    suspend fun states(): List<SyncStateEntity>

    @Query("SELECT * FROM sync_state WHERE recordType = :recordType")
    suspend fun state(recordType: String): SyncStateEntity?

    @Query("DELETE FROM sync_state")
    suspend fun clearState()
}

@Database(
    entities = [PendingActivity::class, CachedActivity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HealthHubDatabase : RoomDatabase() {
    abstract fun stagingDao(): StagingDao
}
