package com.example.webtrafficmonitor.monitor

/**
 * Shared, read-only link between the two services: the accessibility service
 * publishes the current foreground app here, and the screen-capture service
 * reads it so each screenshot can be tagged with the app it came from.
 */
object ForegroundApp {
    @Volatile
    var packageName: String? = null
}
