package com.example.webtrafficmonitor

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import coil.load
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

// NOTE: This whole module is intentionally kept in ONE file.
// These classes would normally live in separate files / sub-packages;
// they are consolidated here on purpose to make development easier.
// Major sections (// ===) mark what used to be sub-folders;
// subsections (// ---) mark what used to be separate files.
// Regenerate with merge_kt.py -- do not re-split by hand.

// =====================================================================================
// APP
// =====================================================================================


// --------------------------------------------------------------
// MainActivity
// --------------------------------------------------------------


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
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener { clearLog() }

        observeEntries()
        refreshBlockRules()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        AppBlocklist.refresh(this)
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

    /**
     * Tapping a row blocks that specific page by its title (so other pages on the
     * same site stay allowed). Falls back to the domain or app if there is no title.
     * To block a whole site instead, type its domain into the box.
     */
    private fun blockEntry(entry: MonitorEntry) {
        val rule = entry.title?.takeIf { it.isNotBlank() }
            ?: entry.domain
            ?: entry.packageName
            ?: return
        BlockRules.add(this, rule)
        refreshBlockRules()
        Toast.makeText(this, getString(R.string.toast_blocking, rule), Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = database.dao()
            dao.allScreenshotPaths().forEach { File(it).delete() }
            dao.clear()
        }
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

// =====================================================================================
// DATA
// =====================================================================================


// --------------------------------------------------------------
// MonitorDao
// --------------------------------------------------------------


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

// --------------------------------------------------------------
// MonitorDatabase
// --------------------------------------------------------------


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

// --------------------------------------------------------------
// MonitorEntry
// --------------------------------------------------------------


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

// --------------------------------------------------------------
// MonitorStore
// --------------------------------------------------------------


/**
 * Small helper so the monitoring services can save an entry with one call,
 * always off the main thread.
 *
 * In testing builds it also trims old data so the list and the saved screenshots
 * do not pile up while developing.
 */
object MonitorStore {

    private const val RETENTION_MS = 10 * 60 * 1000L // keep 10 minutes in testing builds
    private const val CLEANUP_INTERVAL_MS = 30 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCleanupAt = 0L

    fun record(context: Context, entry: MonitorEntry) {
        val dao = MonitorDatabase.get(context).dao()
        scope.launch {
            dao.insert(entry)
            maybeTrimOldData(dao, entry.timestamp)
        }
    }

    private suspend fun maybeTrimOldData(dao: MonitorDao, now: Long) {
        if (!BuildConfig.IS_TESTING) return
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) return
        lastCleanupAt = now

        val cutoff = now - RETENTION_MS
        dao.screenshotPathsBefore(cutoff).forEach { File(it).delete() }
        dao.deleteBefore(cutoff)
    }
}

// =====================================================================================
// MONITOR
// =====================================================================================


// --------------------------------------------------------------
// ForegroundApp
// --------------------------------------------------------------


/**
 * Shared, read-only link between the two services: the accessibility service
 * publishes the current foreground app here, and the screen-capture service
 * reads it so each screenshot can be tagged with the app it came from.
 */
object ForegroundApp {
    @Volatile
    var packageName: String? = null
}

// --------------------------------------------------------------
// PageMonitorAccessibilityService
// --------------------------------------------------------------


/**
 * Reads what is on screen: the foreground app, the website domain (from the
 * address bar), a rough page title, and a sample of the visible text. It also
 * decides whether to block.
 *
 * Key design points:
 *  - The domain comes only from the browser address bar, read while it is NOT
 *    being edited. This avoids treating autocomplete suggestions or embedded
 *    resources (which merely appear somewhere on screen) as the current page.
 *  - The current page's domain is remembered until the app changes or a new
 *    address-bar value is read, so the block does not flicker when the toolbar
 *    scrolls out of view.
 *  - Blocking is re-checked on every (throttled) event, so a block stays up the
 *    whole time the page is shown.
 *  - It is event-driven and throttled, so it stays cheap.
 */
class PageMonitorAccessibilityService : AccessibilityService() {

    private var overlay: OverlayController? = null
    private var lastProcessedAt = 0L
    private var lastLogSignature: String? = null
    private var lastGoBackAt = 0L

    private var lastPackage: String? = null
    private var lastHost: String? = null

    // App-level block state. While true, the cover is OWNED by the recheck loop
    // below: it is kept up / taken down based on what is actually in the
    // foreground, never by individual events (events flicker; window state doesn't).
    private var appBlockActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var keyboardPackages: Set<String> = emptySet()

    /**
     * Runs every RECHECK_MS while an app block is up. Looks at the real window
     * state: still in a blocked app -> keep the cover; an allowed app is genuinely
     * in front -> drop it; can't tell (mid-animation) -> keep it and try again.
     */
    private val recheck = object : Runnable {
        override fun run() {
            if (!appBlockActive) return
            val pkg = currentForegroundPackage()
            val blocked = AppBlocklist.blockedReason(pkg)
            when {
                blocked != null -> showAppBlock(blocked, pkg!!) // keeps cover + reposts
                pkg != null -> {
                    appBlockActive = false
                    overlay?.hide()
                }
                else -> {
                    mainHandler.removeCallbacks(this)
                    mainHandler.postDelayed(this, RECHECK_MS)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        BlockRules.load(this)
        AppBlocklist.refresh(this)
        loadKeyboardPackages()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return
        if (packageName in IGNORED_PACKAGES) return
        // Keyboards pop their own window over the app and fire events under their
        // own package; treating that as "the foreground app changed" is what made
        // the cover flicker. Skip them completely.
        if (packageName.lowercase() in keyboardPackages || isKeyboardWindow(event)) return

        ForegroundApp.packageName = packageName

        // ---- App-level block: FIRST, on every event, before any throttling. ----
        // A plain set lookup is effectively free, and running it on the very first
        // window event of an app launch is what makes the cover appear instantly
        // (no waiting for rootInActiveWindow, no 700ms throttle).
        val blockedApp = AppBlocklist.blockedReason(packageName)
        if (blockedApp != null) {
            showAppBlock(blockedApp, packageName)
            return // No point reading or logging pages inside a blocked app.
        }

        // An allowed app fired a real window change while an app block is up
        // (e.g. user pressed Home): verify against actual window state right away.
        if (appBlockActive && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            mainHandler.removeCallbacks(recheck)
            mainHandler.post(recheck)
        }

        val now = System.currentTimeMillis()
        if (now - lastProcessedAt < MIN_INTERVAL_MS) return
        lastProcessedAt = now

        val root = rootInActiveWindow ?: return

        // The host is read fresh each event. It is non-null only when an address bar
        // is actually on screen (i.e. a web page is being viewed) — NOT in the tab
        // switcher, on the home screen, or in a non-browser app.
        val host = readAddressBarHost()

        if (packageName != lastPackage) {
            lastPackage = packageName
            lastHost = null
        }
        if (host != null) lastHost = host

        val text = sampleVisibleText(root)
        val firstLine = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        val eventTitle = event.text
            .joinToString(" ") { it.toString() }
            .trim()
            .takeIf { it.isNotBlank() }
        val title = eventTitle ?: firstLine?.take(MAX_TITLE_CHARS)

        // Re-check the page-level block every event so the cover persists while
        // the page shows.
        evaluateBlock(host, title)

        // Logging: skip noise apps, and don't record the same page repeatedly.
        if (packageName in NOT_LOGGED_PACKAGES) return
        val signature = "$packageName|$lastHost|${firstLine?.take(40)}"
        if (signature == lastLogSignature) return
        lastLogSignature = signature

        MonitorStore.record(
            this,
            MonitorEntry(
                timestamp = now,
                kind = MonitorEntry.KIND_PAGE,
                packageName = packageName,
                title = title,
                domain = lastHost,
                text = text,
            ),
        )
    }

    /** Shows (or keeps) the sticky cover for a blocked app and (re)arms the loop. */
    private fun showAppBlock(reason: String, blockedPackage: String) {
        val controller = overlay ?: return
        appBlockActive = true
        controller.show(
            reason = reason,
            // Don't hide on Back: the recheck loop drops the cover only once an
            // allowed app is actually in front. Debounced as before.
            onGoBack = {
                val tapAt = System.currentTimeMillis()
                if (tapAt - lastGoBackAt >= GO_BACK_DEBOUNCE_MS) {
                    lastGoBackAt = tapAt
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            },
            // Don't hide on Home either; the loop hides it the moment the launcher
            // is genuinely in front (so a failed Home press can't unblock).
            onLeave = { performGlobalAction(GLOBAL_ACTION_HOME) },
            onReport = {
                AppBlocklist.allowForSession(blockedPackage)
                appBlockActive = false
                mainHandler.removeCallbacks(recheck)
                controller.hide()
            },
        )
        mainHandler.removeCallbacks(recheck)
        mainHandler.postDelayed(recheck, RECHECK_MS)
    }

    /** Page-level (domain/keyword) blocking — unchanged behaviour. */
    private fun evaluateBlock(host: String?, title: String?) {
        val controller = overlay ?: return

        val matchedRule = if (host != null) BlockRules.matchedRule(host, title) else null

        if (matchedRule != null) {
            controller.show(
                reason = matchedRule,
                onGoBack = {
                    val tapAt = System.currentTimeMillis()
                    if (tapAt - lastGoBackAt >= GO_BACK_DEBOUNCE_MS) {
                        lastGoBackAt = tapAt
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                },
                onLeave = {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    controller.hide()
                },
                onReport = {
                    BlockRules.allowForSession(host)
                    controller.hide()
                },
            )
        } else {
            // Never hide an app-level block from here; the recheck loop owns it.
            if (!appBlockActive) controller.hide()
        }
    }

    /** The package of the application window that is actually in front, or null. */
    private fun currentForegroundPackage(): String? {
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (!window.isActive && !window.isFocused) continue
                val pkg = window.root?.packageName?.toString() ?: continue
                if (isNoise(pkg)) continue
                return pkg
            }
        } catch (_: Throwable) {
            // fall through to the fallback below
        }
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return null
        return if (isNoise(pkg)) null else pkg
    }

    private fun isNoise(pkg: String): Boolean =
        pkg == packageName || pkg in IGNORED_PACKAGES || pkg.lowercase() in keyboardPackages

    private fun loadKeyboardPackages() {
        keyboardPackages = try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.inputMethodList.map { it.packageName.lowercase() }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun isKeyboardWindow(event: AccessibilityEvent): Boolean {
        val id = event.windowId
        return try {
            windows.firstOrNull { it.id == id }?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Reads the host shown in the browser address bar, but only when the bar is
     * not being edited. Returns null if no address bar is visible.
     */
    private fun readAddressBarHost(): String? {
        rootInActiveWindow?.let { findAddressBarHost(it, depth = 0)?.let { host -> return host } }
        for (window in windows) {
            window.root?.let { findAddressBarHost(it, depth = 0)?.let { host -> return host } }
        }
        return null
    }

    private fun findAddressBarHost(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > ADDRESS_BAR_DEPTH) return null

        if (isAddressBar(node) && !node.isFocused) {
            hostInText(node.text?.toString())?.let { return it }
            hostInText(node.contentDescription?.toString())?.let { return it }
        }

        for (i in 0 until node.childCount) {
            findAddressBarHost(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun isAddressBar(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable || node.className == "android.widget.EditText") return true
        val description = node.contentDescription?.toString()?.lowercase() ?: return false
        return ADDRESS_BAR_HINTS.any { it in description }
    }

    private fun sampleVisibleText(root: AccessibilityNodeInfo): String? {
        val builder = StringBuilder()
        collectText(root, builder, depth = 0)
        return builder.toString().trim().take(MAX_TEXT_CHARS).takeIf { it.isNotBlank() }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.length >= MAX_TEXT_CHARS) return

        val nodeText = node.text?.toString()?.trim()
        if (!nodeText.isNullOrEmpty()) {
            out.append(nodeText).append('\n')
        }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, depth + 1)
        }
    }

    private fun hostInText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = HOST_PATTERN.find(raw) ?: return null
        return match.groupValues[1].lowercase()
    }

    override fun onInterrupt() {
        // Nothing to clean up.
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(recheck)
        overlay?.hide()
        super.onDestroy()
    }

    companion object {
        private const val MIN_INTERVAL_MS = 700L
        private const val RECHECK_MS = 400L
        private const val MAX_TEXT_CHARS = 1000
        private const val MAX_TITLE_CHARS = 120
        private const val MAX_DEPTH = 40
        private const val ADDRESS_BAR_DEPTH = 25
        private const val GO_BACK_DEBOUNCE_MS = 800L

        private val IGNORED_PACKAGES = setOf("com.android.systemui")

        private val NOT_LOGGED_PACKAGES = setOf(
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.microsoft.launcher",
        )

        private val ADDRESS_BAR_HINTS = listOf(
            "search or enter",
            "search or type",
            "address bar",
            "enter address",
            "search address",
            "edit url",
        )

        private val HOST_PATTERN =
            Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})(?:[/?#]\S*)?""", RegexOption.IGNORE_CASE)
    }
}

// --------------------------------------------------------------
// ScreenCaptureService
// --------------------------------------------------------------


/**
 * Captures periodic, downscaled screenshots of the whole screen using
 * MediaProjection. Frames are drained continuously (cheap) but only saved once
 * every [CAPTURE_INTERVAL_MS], to keep battery, CPU and storage use sane.
 *
 * The user must grant the one-time "Start recording?" consent; we receive the
 * resulting permission token in the start intent.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val captureThread = HandlerThread("screen-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastSavedAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, RESULT_INVALID) ?: RESULT_INVALID
        val data: Intent? =
            if (intent == null) null
            else IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (resultCode == RESULT_INVALID || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // On Android 10+ the foreground service must be running before we get the projection.
        startForeground()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = manager.getMediaProjection(resultCode, data)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(projectionCallback, captureHandler)

        startCapturing(mp)
        isRunning = true
        return START_STICKY
    }

    private fun startCapturing(mp: MediaProjection) {
        val (screenWidth, screenHeight, densityDpi) = screenMetrics()
        val (width, height) = targetSize(screenWidth, screenHeight)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_BUFFERED_IMAGES)
        reader.setOnImageAvailableListener({ onFrame(it) }, captureHandler)
        imageReader = reader

        virtualDisplay = mp.createVirtualDisplay(
            "monitor-capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler,
        )
    }

    /** Runs on the capture thread for every screen frame. Cheap unless it is time to save. */
    private fun onFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastSavedAt < CAPTURE_INTERVAL_MS) return
            lastSavedAt = now

            val bitmap = image.toBitmap()
            val appPackage = ForegroundApp.packageName
            saveScope.launch { saveCapture(bitmap, now, appPackage) }
        } finally {
            image.close()
        }
    }

    private fun saveCapture(bitmap: Bitmap, timestamp: Long, appPackage: String?) {
        val dir = File(filesDir, CAPTURE_DIR).apply { mkdirs() }
        val file = File(dir, "$timestamp.jpg")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } finally {
            bitmap.recycle()
        }

        MonitorStore.record(
            this,
            MonitorEntry(
                timestamp = timestamp,
                kind = MonitorEntry.KIND_SCREEN,
                packageName = appPackage,
                screenshotPath = file.absolutePath,
            ),
        )
    }

    private fun startForeground() {
        val channelId = "screen_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun screenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), resources.configuration.densityDpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    /** Scales the longest side down to [MAX_DIMEN], keeping the aspect ratio. */
    private fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= MAX_DIMEN) return evenPair(width, height)
        val scale = MAX_DIMEN.toFloat() / longest
        return evenPair((width * scale).toInt(), (height * scale).toInt())
    }

    private fun evenPair(width: Int, height: Int): Pair<Int, Int> =
        Pair(width - (width % 2), height - (height % 2))

    override fun onDestroy() {
        isRunning = false
        virtualDisplay?.release()
        imageReader?.close()
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        captureThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var isRunning = false
            private set

        private const val NOTIF_ID = 1001
        private const val RESULT_INVALID = 0
        private const val CAPTURE_DIR = "captures"
        private const val CAPTURE_INTERVAL_MS = 3000L
        private const val MAX_DIMEN = 720
        private const val MAX_BUFFERED_IMAGES = 2
        private const val JPEG_QUALITY = 60

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "stop"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

/** Copies an RGBA screen frame into a Bitmap, handling row padding. */
private fun Image.toBitmap(): Bitmap {
    val plane = planes[0]
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width

    val bitmap = Bitmap.createBitmap(
        width + rowPadding / pixelStride,
        height,
        Bitmap.Config.ARGB_8888,
    )
    bitmap.copyPixelsFromBuffer(plane.buffer)

    return if (rowPadding == 0) {
        bitmap
    } else {
        // Crop the padding columns off the right edge.
        Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
    }
}

// =====================================================================================
// BLOCK
// =====================================================================================


// --------------------------------------------------------------
// BlockRules
// --------------------------------------------------------------


/**
 * The list of things to block, plus a per-session allow list for "report
 * incorrect block".
 *
 * A rule is matched against only the current page's domain and title — never the
 * full on-screen text, so an autocomplete suggestion or an embedded resource
 * mentioning a domain does not trigger a block. Blocking applies to web pages
 * only (where we can read an address bar); it does not block apps.
 *
 *  - A rule containing a dot is a DOMAIN rule: "redgifs.com" blocks redgifs.com
 *    and its subdomains; "i.reddit.com" blocks only that exact subdomain.
 *  - A rule without a dot is a KEYWORD rule, matched against the page title:
 *    "wolf" blocks pages titled like "Wolf - Wikipedia".
 *
 * This is the temporary stand-in for the real content classifier: it lets us
 * (and the maintainer) trigger and test blocking by hand.
 */
object BlockRules {

    private const val PREFS = "block_rules"
    private const val KEY = "rules"

    private val rules = linkedSetOf<String>()
    private val sessionAllow = mutableSetOf<String>()

    fun load(context: Context) {
        val saved = prefs(context).getStringSet(KEY, emptySet()) ?: emptySet()
        rules.clear()
        rules.addAll(saved)
    }

    fun all(): List<String> = rules.toList()

    fun add(context: Context, rule: String) {
        val cleaned = rule.trim().lowercase()
        if (cleaned.isEmpty()) return
        rules.add(cleaned)
        persist(context)
    }

    fun clear(context: Context) {
        rules.clear()
        persist(context)
    }

    /** Lets the current page through until the app process restarts. */
    fun allowForSession(key: String?) {
        if (!key.isNullOrBlank()) sessionAllow.add(key.lowercase())
    }

    /**
     * Returns the rule that blocks this page, or null if it is allowed. Matching
     * uses only the page domain and title, so it is shown only for actual web
     * pages (the caller passes a non-null domain only when an address bar is
     * visible).
     */
    fun matchedRule(domain: String?, title: String?): String? {
        if (rules.isEmpty()) return null

        val host = domain?.lowercase()
        if (host != null && host in sessionAllow) return null

        val titleText = title?.lowercase()

        return rules.firstOrNull { rule ->
            if ('.' in rule) {
                // Domain rule: exact host or a subdomain of it.
                host != null && (host == rule || host.endsWith(".$rule"))
            } else {
                // Keyword rule: match the page title.
                titleText?.contains(rule) == true
            }
        }
    }

    private fun persist(context: Context) {
        // Store a copy; SharedPreferences must not be handed its own live set.
        prefs(context).edit().putStringSet(KEY, HashSet(rules)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// --------------------------------------------------------------
// AppBlocklist
// --------------------------------------------------------------


/**
 * Apps blocked outright by package name, regardless of what is on screen. Used to
 * block web browsers so they can't be used to get around the page-level rules in
 * [BlockRules].
 *
 * HOW TO UPDATE THIS LIST (manual):
 *  - Each entry is an Android package name (the app's applicationId), e.g.
 *    "org.mozilla.firefox". This is EXACTLY the value shown in the log rows in the
 *    app: the "·  <package>  ·" part of a page entry's bottom (meta) line, and the
 *    top line of a screenshot entry.
 *  - To block a new browser: open it once with monitoring on, find its row in the
 *    list, copy the package name, and add a line to BLOCKED_BROWSERS below.
 *  - To allow a browser: delete (or comment out) its line.
 *  - DuckDuckGo (com.duckduckgo.mobile.android) is intentionally NOT listed, so it
 *    stays allowed.
 *  - Casing doesn't matter: matching is case-insensitive, so keep entries lowercase.
 */
object AppBlocklist {

    private val sessionAllow = mutableSetOf<String>()

    // NEW: browsers detected on THIS device at runtime. Starts empty, so if
    // detection never runs or fails, only the static list below is used.
    @Volatile
    private var dynamicBrowsers: Set<String> = emptySet()

    @Volatile
    private var refreshing = false

    /**
     * Returns the package name (used as the cover's reason text) if [packageName]
     * is a blocked browser, or null if it is allowed.
     */
    fun blockedReason(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        val pkg = packageName.lowercase()
        if (pkg in sessionAllow) return null
        if (pkg in ALLOWED_BROWSERS) return null         // NEW: e.g. DuckDuckGo, never block
        if (pkg in BLOCKED_BROWSERS) return packageName   // static list
        if (pkg in dynamicBrowsers) return packageName    // NEW: detected at runtime
        return null
    }

    /** Lets a blocked app through until the app process restarts ("report" button). */
    fun allowForSession(packageName: String?) {
        if (!packageName.isNullOrBlank()) sessionAllow.add(packageName.lowercase())
    }

    /**
     * NEW. Asks Android which installed apps can open web links and remembers them
     * as extra browsers to block. Completely optional and self-contained:
     *  - Runs on a background thread, so it can never freeze the UI or the service.
     *  - Wrapped in try/catch: if anything goes wrong it leaves the detected set
     *    empty and the static list keeps working.
     *  - Skips the allow-list (DuckDuckGo) and our own app.
     * Safe to call repeatedly; overlapping calls are ignored.
     */
    fun refresh(context: Context) {
        if (refreshing) return
        refreshing = true
        val appContext = context.applicationContext
        Thread {
            try {
                val found = detectBrowsers(appContext)
                dynamicBrowsers = found
                // Visible diagnostic: one row in the app's list showing what was found.
                MonitorStore.record(
                    appContext,
                    MonitorEntry(
                        timestamp = System.currentTimeMillis(),
                        kind = MonitorEntry.KIND_PAGE,
                        packageName = appContext.packageName,
                        title = "Browser detection: found ${found.size}",
                        text = found.sorted().joinToString("\n"),
                    ),
                )
            } catch (_: Throwable) {
                // Leave dynamicBrowsers as-is. The static list still works.
            } finally {
                refreshing = false
            }
        }.apply { isDaemon = true }.start()
    }

    private fun detectBrowsers(context: Context): Set<String> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)

        // MATCH_ALL is the crucial flag: without it, once a default browser is set,
        // Android returns ONLY that default and hides every other installed browser.
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)

        val ownPackage = context.packageName.lowercase()
        return resolved
            .mapNotNull { it.activityInfo?.packageName?.lowercase() }
            .filter { it != ownPackage && it !in ALLOWED_BROWSERS }
            .toSet()
    }

    // NEW: browsers that must stay allowed even if detected at runtime.
    // Add a package name here to whitelist a browser.
    private val ALLOWED_BROWSERS = setOf(
        "com.duckduckgo.mobile.android",   // DuckDuckGo — intentionally allowed
    )

    // ================================================================
    // EDIT BELOW — the browser package names to block. All lowercase.
    // DuckDuckGo is in ALLOWED_BROWSERS above, so it stays allowed even
    // if dynamic detection finds it.
    // ================================================================
    private val BLOCKED_BROWSERS = setOf(
        // --- Chrome ---
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",

        // --- Firefox / Gecko family ---
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.fennec_fdroid",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "org.mozilla.rocket",
        "org.mozilla.reference.browser",
        "io.github.forkmaintainers.iceraven",
        "us.spotco.fennec_dos",

        // --- Edge / Opera / Samsung / Vivaldi / Yandex ---
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.opera.touch",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "com.yandex.browser",
        "com.yandex.browser.beta",

        // --- Brave ---
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",

        // --- AOSP / stock ---
        "com.android.browser",
        "com.google.android.browser",

        // --- OEM built-ins ---
        "com.miui.browser",
        "com.mi.globalbrowser",
        "com.mi.globalbrowser.mini",
        "com.heytap.browser",
        "com.coloros.browser",
        "com.oppo.browser",
        "com.vivo.browser",
        "com.huawei.browser",

        // --- Chromium forks / FOSS ---
        "org.bromite.bromite",
        "org.cromite.cromite",
        "com.kiwibrowser.browser",
        "com.stoutner.privacybrowser.standard",
        "com.stoutner.privacybrowser.free",
        "acr.browser.lightning",
        "acr.browser.barebones",
        "jp.hazuki.yuzubrowser",
        "foundation.e.browser",
        "org.adblockplus.browser",
        "org.torproject.torbrowser",

        // --- Other popular third-party ---
        "com.ucmobile.intl",
        "com.uc.browser.en",
        "com.tencent.mtt",
        "com.qihoo.browser",
        "com.cloudmosa.puffinfree",
        "com.cloudmosa.puffin",
        "mark.via.gp",
        "mark.via",
        "com.aloha.browser",
        "com.naver.whale",
        "com.phoenix.browser",
        "com.apusapps.browser",
        "com.ksmobile.cb",
        "mobi.mgeek.tunnybrowser",

        // --- Added after testing on real devices ---
        "net.quetta.browser",      // Quetta
        "com.qwant.liberty",       // Qwant
        // "org.triple.banana",       // Banana Browser
    )
}


// --------------------------------------------------------------
// OverlayController
// --------------------------------------------------------------


/**
 * Draws and removes the full-screen "blocked" cover over whatever app is in
 * front. The cover is opaque, so the content underneath is hidden, but it is not
 * focusable, so the system Back action still reaches the app underneath (that is
 * how the "Go back" button navigates the browser).
 */
class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    fun show(reason: String, onGoBack: () -> Unit, onLeave: () -> Unit, onReport: () -> Unit) {
        view?.let { existing ->
            existing.findViewById<TextView>(R.id.block_reason).text = reason
            return
        }

        val overlay = LayoutInflater.from(context).inflate(R.layout.overlay_block, null)
        overlay.findViewById<TextView>(R.id.block_reason).text = reason
        overlay.findViewById<Button>(R.id.btn_go_back).setOnClickListener { onGoBack() }
        overlay.findViewById<Button>(R.id.btn_leave).setOnClickListener { onLeave() }
        overlay.findViewById<Button>(R.id.btn_report).setOnClickListener { onReport() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        )

        windowManager.addView(overlay, params)
        view = overlay
    }

    fun hide() {
        view?.let {
            windowManager.removeView(it)
            view = null
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}

// =====================================================================================
// UI
// =====================================================================================


// --------------------------------------------------------------
// MonitorAdapter
// --------------------------------------------------------------


/** Shows the monitored entries in the scrollable list. Tapping a row blocks it. */
class MonitorAdapter(
    private val onEntryClick: (MonitorEntry) -> Unit,
) : ListAdapter<MonitorEntry, MonitorAdapter.ViewHolder>(DIFF) {

    private val timeFormat = SimpleDateFormat("MMM d  HH:mm:ss", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
        val primary: TextView = view.findViewById(R.id.primary)
        val secondary: TextView = view.findViewById(R.id.secondary)
        val meta: TextView = view.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val time = timeFormat.format(Date(entry.timestamp))

        holder.itemView.setOnClickListener { onEntryClick(entry) }

        if (entry.kind == MonitorEntry.KIND_SCREEN) {
            holder.thumbnail.visibility = View.VISIBLE
            entry.screenshotPath?.let { holder.thumbnail.load(File(it)) }
            holder.primary.text = entry.packageName ?: "Screen"
            holder.secondary.text = "Screenshot"
            holder.meta.text = "$time  ·  screen"
        } else {
            holder.thumbnail.visibility = View.GONE
            holder.thumbnail.setImageDrawable(null)
            holder.primary.text = entry.domain ?: entry.packageName ?: "Page"
            holder.secondary.text = entry.title.orEmpty()
            val snippet = entry.text?.replace('\n', ' ')?.take(80).orEmpty()
            holder.meta.text = "$time  ·  ${entry.packageName.orEmpty()}  ·  $snippet"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MonitorEntry>() {
            override fun areItemsTheSame(old: MonitorEntry, new: MonitorEntry) = old.id == new.id
            override fun areContentsTheSame(old: MonitorEntry, new: MonitorEntry) = old == new
        }
    }
}
