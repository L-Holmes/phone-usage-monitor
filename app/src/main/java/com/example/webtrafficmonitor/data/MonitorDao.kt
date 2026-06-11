package com.example.webtrafficmonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitorDao {

    @Insert
    suspend fun insert(entry: MonitorEntry)

    /** Newest first. The UI observes this and updates automatically. */
    @Query("SELECT * FROM monitor_entries ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MonitorEntry>>

    @Query("SELECT screenshotPath FROM monitor_entries WHERE screenshotPath IS NOT NULL")
    suspend fun allScreenshotPaths(): List<String>

    @Query("SELECT screenshotPath FROM monitor_entries WHERE timestamp < :cutoff AND screenshotPath IS NOT NULL")
    suspend fun screenshotPathsBefore(cutoff: Long): List<String>

    @Query("DELETE FROM monitor_entries WHERE timestamp < :cutoff")
    suspend fun deleteBefore(cutoff: Long)

    @Query("DELETE FROM monitor_entries")
    suspend fun clear()
}
