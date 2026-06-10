package com.example.webtrafficmonitor.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Small helper so the monitoring services can save an entry with one call,
 * always off the main thread.
 */
object MonitorStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun record(context: Context, entry: MonitorEntry) {
        val dao = MonitorDatabase.get(context).dao()
        scope.launch { dao.insert(entry) }
    }
}
