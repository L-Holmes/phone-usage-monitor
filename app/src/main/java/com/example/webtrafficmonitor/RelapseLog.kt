package com.example.webtrafficmonitor

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

// =====================================================================================
// RELAPSE LOG  (private, on-device relapse reports - READ ONLY)
// =====================================================================================
//
// Lives in its own file (same package), like ContentFilter.kt and BlockEventLog.kt.
//
// HISTORY ONLY. The "Report relapse" pane and its step-by-step reporting flow have been
// REMOVED from the app, so nothing writes here any more. What remains is the reader:
// reports the user logged before the flow went away still feed "Relapse patterns" in
// Statistics and the posture/light breakdown in Context stats, so their history is not
// silently thrown away.
//
// Its own database (relapse.db) so these reports are NOT touched by the block-event
// 90-day compaction — relapse history is meant to last. No destructive migration: a
// schema change here must ship a real Migration.
//
// If a reporting flow is ever brought back, the entity/Dao below already model it.


// --------------------------------------------------------------
// RelapseReport
// --------------------------------------------------------------

/**
 * One relapse report. [timestamp]/[dayOfWeek]/[hourOfDay] are auto-captured and stored
 * the same way BlockEvent stores them, so the two can be grouped on the same time
 * buckets later. Everything else is the user's tap answers, plus an optional private note.
 */
@Entity(tableName = "relapse_reports")
data class RelapseReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val dayOfWeek: Int,            // Calendar.DAY_OF_WEEK: 1 = Sunday … 7 = Saturday (local)
    val hourOfDay: Int,           // 0..23 (local)
    val onThisDevice: Boolean,
    val atHome: Boolean? = null,
    val room: String? = null,     // only when atHome == true
    val alone: Boolean? = null,
    val activity: String? = null, // what they were doing just before
    val feeling: String? = null,
    val urge: String? = null,     // mild / strong / overwhelming (optional)
    val note: String? = null,     // PRIVATE — never shown back as judgement
    // Where you physically were, from SensorContext at the moment of reporting:
    // "lying"/"upright"/"unknown" and "DARK"/"DULL"/"NORMAL"/"BRIGHT"/"unknown".
    val posture: String? = null,
    val lightLevel: String? = null,
)


// --------------------------------------------------------------
// RelapseReportDao / RelapseDatabase
// --------------------------------------------------------------

@Dao
interface RelapseReportDao {

    @Insert suspend fun insert(report: RelapseReport)

    @Query("SELECT * FROM relapse_reports ORDER BY timestamp DESC")
    suspend fun all(): List<RelapseReport>

    @Query("SELECT COUNT(*) FROM relapse_reports")
    suspend fun count(): Int

    @Query("DELETE FROM relapse_reports") suspend fun clear()
}

@Database(entities = [RelapseReport::class], version = 2, exportSchema = false)
abstract class RelapseDatabase : RoomDatabase() {

    abstract fun dao(): RelapseReportDao

    companion object {
        @Volatile private var instance: RelapseDatabase? = null

        /** v2 adds where you physically were: posture + light band. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE relapse_reports ADD COLUMN posture TEXT")
                db.execSQL("ALTER TABLE relapse_reports ADD COLUMN lightLevel TEXT")
            }
        }

        fun get(context: Context): RelapseDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RelapseDatabase::class.java,
                    "relapse.db",
                )
                    // No destructive migration: relapse history must persist. A schema
                    // change here needs a proper Migration.
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}


// --------------------------------------------------------------
// RelapseLog  (the API)
// --------------------------------------------------------------

object RelapseLog {

    suspend fun all(context: Context): List<RelapseReport> =
        RelapseDatabase.get(context).dao().all()
}
