package com.example.webtrafficmonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One observed thing. Either:
 *  - a "page": website/app info read from the screen (Accessibility), or
 *  - a "screen": a captured screenshot saved to disk (MediaProjection).
 */
@Entity(tableName = "monitor_entries")
data class MonitorEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val kind: String,
    val packageName: String?,
    val title: String? = null,
    val domain: String? = null,
    val text: String? = null,
    val screenshotPath: String? = null,
) {
    companion object {
        const val KIND_PAGE = "page"
        const val KIND_SCREEN = "screen"
    }
}
