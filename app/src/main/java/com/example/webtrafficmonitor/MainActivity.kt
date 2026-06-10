package com.example.webtrafficmonitor

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.webtrafficmonitor.data.MonitorDatabase
import com.example.webtrafficmonitor.monitor.PageMonitorAccessibilityService
import com.example.webtrafficmonitor.monitor.ScreenCaptureService
import com.example.webtrafficmonitor.ui.MonitorAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private val database by lazy { MonitorDatabase.get(this) }
    private val adapter = MonitorAdapter()

    private lateinit var statusAccessibility: TextView
    private lateinit var statusCapture: TextView
    private lateinit var buttonAccessibility: Button
    private lateinit var buttonCapture: Button
    private lateinit var emptyList: TextView

    // Asks for the one-time screen-capture consent, then starts the service.
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
        }
        refreshStatus()
    }

    // Notifications are required to show the "monitoring active" banner on newer Android.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        requestScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusAccessibility = findViewById(R.id.status_accessibility)
        statusCapture = findViewById(R.id.status_capture)
        buttonAccessibility = findViewById(R.id.btn_accessibility)
        buttonCapture = findViewById(R.id.btn_capture)
        emptyList = findViewById(R.id.empty_list)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        buttonAccessibility.setOnClickListener {
            startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        buttonCapture.setOnClickListener {
            if (ScreenCaptureService.isRunning) {
                ScreenCaptureService.stop(this)
                buttonCapture.postDelayed({ refreshStatus() }, 300)
            } else {
                ensureNotificationsThenCapture()
            }
        }

        findViewById<Button>(R.id.btn_clear).setOnClickListener { clearAll() }

        observeEntries()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun observeEntries() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                database.dao().observeAll().collect { entries ->
                    adapter.submitList(entries)
                    emptyList.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun ensureNotificationsThenCapture() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun clearAll() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = database.dao()
            dao.allScreenshotPaths().forEach { File(it).delete() }
            dao.clear()
        }
    }

    private fun refreshStatus() {
        val accessibilityOn = isAccessibilityEnabled()
        statusAccessibility.text =
            getString(R.string.page_monitoring) + ":  " + onOff(accessibilityOn)

        val captureOn = ScreenCaptureService.isRunning
        statusCapture.text = getString(R.string.screen_capture) + ":  " + onOff(captureOn)
        buttonCapture.text = getString(if (captureOn) R.string.stop else R.string.start)
    }

    private fun onOff(on: Boolean): String =
        getString(if (on) R.string.status_on else R.string.status_off)

    private fun isAccessibilityEnabled(): Boolean {
        val expected =
            ComponentName(this, PageMonitorAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
