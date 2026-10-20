package com.example.zqkr1

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var enable: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        enable = Button(this).apply {
            text = "Turn on monitoring"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            addView(status)
            addView(enable)
        })
    }

    override fun onResume() {
        super.onResume()
        val on = serviceEnabled()
        status.text = if (on) {
            "Monitoring is on.\n\nReddit is blocked, and the phone locks down if Web Traffic Monitor is removed."
        } else {
            "Monitoring is off.\n\nTap below, then turn on \"${getString(R.string.app_name)}\" under Installed apps."
        }
        enable.visibility = if (on) Button.GONE else Button.VISIBLE
    }

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, GuardService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
}
