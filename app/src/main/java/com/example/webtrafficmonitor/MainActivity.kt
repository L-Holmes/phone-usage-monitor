package com.example.webtrafficmonitor

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.webtrafficmonitor.block.BlockRules
import com.example.webtrafficmonitor.data.MonitorDatabase
import com.example.webtrafficmonitor.data.MonitorEntry
import com.example.webtrafficmonitor.monitor.PageMonitorAccessibilityService
import com.example.webtrafficmonitor.monitor.ScreenCaptureService
import com.example.webtrafficmonitor.ui.MonitorAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val database by lazy { MonitorDatabase.get(this) }
    private val adapter = MonitorAdapter(onEntryClick = ::blockEntry)

    private lateinit var statusAccessibility: TextView
    private lateinit var statusCapture: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var buttonCapture: Button
    private lateinit var blockRulesView: TextView
    private lateinit var blockInput: EditText
    private lateinit var emptyList: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
        }
        refreshStatus()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        requestScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        BlockRules.load(this)

        statusAccessibility = findViewById(R.id.status_accessibility)
        statusCapture = findViewById(R.id.status_capture)
        statusOverlay = findViewById(R.id.status_overlay)
        buttonCapture = findViewById(R.id.btn_capture)
        blockRulesView = findViewById(R.id.block_rules)
        blockInput = findViewById(R.id.input_block)
        emptyList = findViewById(R.id.empty_list)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        buttonCapture.setOnClickListener { toggleCapture() }
        findViewById<Button>(R.id.btn_overlay).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btn_block).setOnClickListener { addBlockFromInput() }
        findViewById<Button>(R.id.btn_clear_blocks).setOnClickListener {
            BlockRules.clear(this)
            refreshBlockRules()
        }

        observeEntries()
        refreshBlockRules()
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

    private fun toggleCapture() {
        if (ScreenCaptureService.isRunning) {
            ScreenCaptureService.stop(this)
            buttonCapture.postDelayed({ refreshStatus() }, 300)
        } else {
            ensureNotificationsThenCapture()
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

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun addBlockFromInput() {
        val rule = blockInput.text.toString().trim()
        if (rule.isEmpty()) return
        BlockRules.add(this, rule)
        blockInput.text.clear()
        refreshBlockRules()
        Toast.makeText(this, getString(R.string.toast_blocking, rule), Toast.LENGTH_SHORT).show()
    }

    /** Tapping a row blocks its domain (or its app, if there is no domain). */
    private fun blockEntry(entry: MonitorEntry) {
        val rule = entry.domain ?: entry.packageName ?: return
        BlockRules.add(this, rule)
        refreshBlockRules()
        Toast.makeText(this, getString(R.string.toast_blocking, rule), Toast.LENGTH_SHORT).show()
    }

    private fun refreshBlockRules() {
        val rules = BlockRules.all()
        val label = getString(R.string.blocking_label)
        val value = if (rules.isEmpty()) getString(R.string.blocking_none) else rules.joinToString(", ")
        blockRulesView.text = "$label  $value"
    }

    private fun refreshStatus() {
        statusAccessibility.text =
            getString(R.string.page_monitoring) + ":  " + onOff(isAccessibilityEnabled())

        val captureOn = ScreenCaptureService.isRunning
        statusCapture.text = getString(R.string.screen_capture) + ":  " + onOff(captureOn)
        buttonCapture.text = getString(if (captureOn) R.string.stop else R.string.start)

        statusOverlay.text =
            getString(R.string.overlay_permission) + ":  " + onOff(Settings.canDrawOverlays(this))
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
