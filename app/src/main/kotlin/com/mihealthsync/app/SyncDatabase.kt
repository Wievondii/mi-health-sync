package com.mihealthsync.app

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "record_index")
internal data class RecordIndex(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val version: Long,
    val applied: Boolean,
    val everApplied: Boolean,
)

@Entity(tableName = "cursors", primaryKeys = ["account", "metric"])
internal data class SyncCursor(val account: String, val metric: String, val endEpochSeconds: Long)

@Entity(tableName = "sync_events")
internal data class SyncEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    val metric: String,
    val status: String,
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val rejected: Int = 0,
    val latestEpochSeconds: Long? = null,
    val pulledAtMillis: Long? = null,
    val writtenAtMillis: Long? = null,
)

@Dao internal interface SyncDao {
    @Query("SELECT * FROM record_index WHERE id IN (:ids)")
    suspend fun indices(ids: List<String>): List<RecordIndex>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun reserve(entries: List<RecordIndex>)

    @Query("UPDATE record_index SET applied = 1, everApplied = 1 WHERE id IN (:ids)")
    suspend fun markApplied(ids: List<String>)

    @Query("SELECT endEpochSeconds FROM cursors WHERE account = :account AND metric = :metric")
    suspend fun cursor(account: String, metric: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCursor(cursor: SyncCursor)

    @Query("DELETE FROM cursors")
    suspend fun clearCursors()

    @Insert suspend fun append(event: SyncEvent)

    @Query("DELETE FROM sync_events WHERE id NOT IN (SELECT id FROM sync_events ORDER BY id DESC LIMIT 150)")
    suspend fun pruneEvents()

    @Query("DELETE FROM sync_events")
    suspend fun clearEvents()

    @Query("SELECT * FROM sync_events ORDER BY id DESC LIMIT 150")
    fun events(): Flow<List<SyncEvent>>

    @Query("SELECT status FROM sync_events ORDER BY id DESC LIMIT 1")
    suspend fun latestStatus(): String?
}

@Database(entities = [RecordIndex::class, SyncCursor::class, SyncEvent::class], version = 2, exportSchema = false)
internal abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao

    companion object {
        val TIMES_MIGRATION = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_events ADD COLUMN pulledAtMillis INTEGER")
                db.execSQL("ALTER TABLE sync_events ADD COLUMN writtenAtMillis INTEGER")
                db.execSQL("UPDATE sync_events SET writtenAtMillis = timeMillis WHERE added > 0 OR updated > 0")
            }
        }
    }
}
