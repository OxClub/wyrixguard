package com.wyrix.guard.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "locations", indices = [Index(value = ["clientId"], unique = true), Index("synced", "recordedAt")])
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: String, val lat: Double, val lng: Double, val accuracy: Float, val recordedAt: Long,
    val battery: Int?, val charging: Boolean?, val connection: String, val mode: String, val synced: Boolean = false,
)

@Entity(tableName = "events", indices = [Index(value = ["clientId"], unique = true)])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: String, val type: String, val detailsJson: String, val occurredAt: Long, val synced: Boolean = false,
)

@Dao
interface LocationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: LocationEntity): Long
    @Query("SELECT * FROM locations WHERE synced = 0 ORDER BY recordedAt LIMIT :n") suspend fun unsynced(n: Int): List<LocationEntity>
    @Query("UPDATE locations SET synced = 1 WHERE id IN (:ids)") suspend fun markSynced(ids: List<Long>)
    @Query("SELECT * FROM locations ORDER BY recordedAt DESC LIMIT :n") fun recent(n: Int): Flow<List<LocationEntity>>
    @Query("SELECT * FROM locations ORDER BY recordedAt DESC LIMIT 1") suspend fun last(): LocationEntity?
    @Query("SELECT COUNT(*) FROM locations WHERE synced = 0") fun unsyncedCount(): Flow<Int>
    @Query("DELETE FROM locations WHERE synced = 1 AND recordedAt < :before") suspend fun pruneSynced(before: Long)
    @Query("DELETE FROM locations") suspend fun clear()
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: EventEntity): Long
    @Query("SELECT * FROM events WHERE synced = 0 ORDER BY occurredAt LIMIT :n") suspend fun unsynced(n: Int): List<EventEntity>
    @Query("UPDATE events SET synced = 1 WHERE id IN (:ids)") suspend fun markSynced(ids: List<Long>)
    @Query("SELECT * FROM events ORDER BY occurredAt DESC LIMIT :n") fun recent(n: Int): Flow<List<EventEntity>>
    @Query("DELETE FROM events") suspend fun clear()
}

/**
 * Not encrypted at the SQLite level (no SQLCipher dependency). It lives in app-private storage, is excluded
 * from backup, and holds only data the owner has consented to collect. Tokens/ids are in the Keystore-encrypted store.
 */
@Database(entities = [LocationEntity::class, EventEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locations(): LocationDao
    abstract fun events(): EventDao
}
