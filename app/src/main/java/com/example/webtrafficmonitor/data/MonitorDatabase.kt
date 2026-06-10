package com.example.webtrafficmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MonitorEntry::class], version = 1, exportSchema = false)
abstract class MonitorDatabase : RoomDatabase() {

    abstract fun dao(): MonitorDao

    companion object {
        @Volatile
        private var instance: MonitorDatabase? = null

        fun get(context: Context): MonitorDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MonitorDatabase::class.java,
                    "monitor.db",
                ).build().also { instance = it }
            }
    }
}
