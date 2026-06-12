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
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.exp
import org.json.JSONObject

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
    private val adapter = MonitorAdapter(
        onEntryClick = ::blockEntry,
        onEntryLongClick = ::showEntryDetails,
    )

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

    /** Long-press a row to read the whole entry — including the full NODE DUMP. */
    private fun showEntryDetails(entry: MonitorEntry) {
        val details = buildString {
            append("kind: ").append(entry.kind).append("\n\n")
            append("package: ").append(entry.packageName).append("\n\n")
            append("url: ").append(entry.url ?: "(none)").append("\n\n")
            append("domain: ").append(entry.domain ?: "(none)").append("\n\n")
            append("title: ").append(entry.title ?: "(none)").append("\n\n")
            append("content / dump:\n").append(entry.text ?: "(none)")
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val textView = TextView(this).apply {
            text = details
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this)
            .setTitle("Entry details")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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


@Database(entities = [MonitorEntry::class], version = 3, exportSchema = false)
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
                )
                    // Dev build: a schema change just wipes old rows (they expire in
                    // 10 min anyway). If your Room is 2.6+, you may get a deprecation
                    // warning — swap for .fallbackToDestructiveMigration(dropAllTables = true)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
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
    val url: String? = null,
    val text: String? = null,
    val screenshotPath: String? = null,
    /**
     * Calibrated NSFW confidence in [0,1] for a screenshot (0.5 == exactly on the
     * classifier's threshold; higher == more likely disallowed). Null for page
     * entries, or for screens not (yet) scored / where the model was unavailable.
     */
    val nsfwScore: Float? = null,
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
// CaptureWhitelist
// --------------------------------------------------------------


/**
 * Foreground apps we deliberately do NOT screenshot or score: system surfaces,
 * launchers, the dialer, the system search/Assistant, and the app's own UI
 * (checked separately by the capture service). Skipping them saves CPU/battery
 * and keeps benign system screens out of the log.
 *
 * This is the list to extend when you find an app you don't want captured.
 */
object CaptureWhitelist {

    private val PACKAGES = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.googlequicksearchbox",  // Google app / Assistant / search bar
        // launchers
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.android.launcher",
        "com.android.launcher3",
        "com.microsoft.launcher",
        "com.teslacoilsw.launcher",
        // phone / dialer / in-call
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.phone",
        "com.android.incallui",
        // installers / permission + share dialogs
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.intentresolver",
    )

    fun contains(packageName: String?): Boolean =
        packageName != null && packageName in PACKAGES
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
    private var lastUrl: String? = null
    private var lastFullUrl: String? = null

    // App-level block state. While true, the cover is OWNED by the recheck loop
    // below: it is kept up / taken down based on what is actually in the
    // foreground, never by individual events (events flicker; window state doesn't).
    private var appBlockActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var keyboardPackages: Set<String> = emptySet()

    private var lastDumpAt = 0L

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

    /**
     * Built-in guards for in-app screens we never want reachable. Currently:
     * Firefox Focus's privacy settings, where the "stealth" option blocks
     * screenshots and would blind the screen capture.
     *
     * Only ever called off the web (host == null), so a web page that merely
     * mentions the keyword can't trip it. To add another guarded screen, copy the
     * if-block and change the package / keywords.
     */
    private fun appScreenBlock(packageName: String, title: String?, content: String?): String? {
        if (packageName == "org.mozilla.focus") {
            val t = title?.lowercase().orEmpty()
            val c = content?.lowercase().orEmpty()
            if ("privacy" in t || "stealth" in c) {
                return "Firefox Focus stealth/privacy settings are blocked"
            }
        }
        return null
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

        if (DEBUG_DUMP_NODES && packageName in BROWSER_DEBUG_PACKAGES &&
            now - lastDumpAt > DUMP_INTERVAL_MS
        ) {
            lastDumpAt = now
            dumpBrowserNodes(root, packageName)
        }

        // The bar text is the full address (URL or search), as a screen reader sees
        // it. The host is derived from it purely for blocking.
        val barText = readAddressBarText()
        val host = barText?.let { hostInText(it) }

        if (packageName != lastPackage) {
            lastPackage = packageName
            lastHost = null
            lastUrl = null
            lastFullUrl = null
        }
        // A host change makes any captured full URL stale.
        if (host != null && host != lastHost) lastFullUrl = null
        if (host != null) lastHost = host
        if (barText != null) lastUrl = barText
        readFocusedFullUrl(host)?.let { lastFullUrl = it }   // fills in path if user taps the bar

        // Content = the web page itself (WebView subtree), falling back to the whole
        // screen for non-browser apps.
        val text = readWebViewText() ?: sampleVisibleText(root)
        val firstLine = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        val rawTitle = event.text
            .joinToString(" ") { it.toString() }
            .trim()
            .takeIf { it.isNotBlank() }
            ?: firstLine?.take(MAX_TITLE_CHARS)
        val title = cleanTitle(rawTitle)   // logged/displayed: "Dog"

        // Block on the RAW title so keyword rules (e.g. "wikipedia") still match.
        evaluateBlock(packageName, host, rawTitle, text)

        // Logging: skip noise apps, and don't record the same page repeatedly.
        if (packageName in NOT_LOGGED_PACKAGES) return
        val signature = "$packageName|${lastUrl ?: lastHost}|${firstLine?.take(40)}"
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
                url = lastFullUrl ?: lastUrl,   
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
    private fun evaluateBlock(packageName: String, host: String?, title: String?, content: String?) {
        val controller = overlay ?: return

        val matchedRule = if (host == null) {
            // Off the web (settings screens, etc.): first the built-in guards, then
            // keyword rules against the screen title — so tapping a non-web log row
            // to block it actually works. Skip launchers so a stray keyword can't
            // lock you out of your home screen.
            appScreenBlock(packageName, title, content)
                ?: if (packageName !in NOT_LOGGED_PACKAGES) BlockRules.matchedRule(null, title) else null
        } else {
            // Web pages: domain or keyword rules, as before.
            BlockRules.matchedRule(host, title)
        }

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
                    // Web blocks can be let through for the session; the in-app
                    // guard can't (host is null, so this is a no-op for guards).
                    BlockRules.allowForSession(host)
                    controller.hide()
                },
            )
        } else {
            // Never hide an app-level (whole-browser) block from here.
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
     * Reads the address bar, preferring the FULL url. DuckDuckGo's unfocused
     * omnibar has several matching nodes — typically a chip/label showing only the
     * host ("en.wikipedia.org") AND the real input field holding the full URL
     * ("https://en.wikipedia.org/wiki/Dog"). A plain depth-first walk hits the
     * host-only one first, which is why we were logging just the domain. So gather
     * ALL candidates and keep the richest.
     */
    private fun readAddressBarText(): String? {
        val candidates = mutableListOf<String>()
        rootInActiveWindow?.let { collectAddressCandidates(it, depth = 0, out = candidates) }
        for (window in windows) {
            window.root?.let { collectAddressCandidates(it, depth = 0, out = candidates) }
        }
        return candidates.distinct().maxByOrNull { urlRichness(it) }?.take(MAX_URL_CHARS)
    }

    private fun collectAddressCandidates(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<String>,
    ) {
        if (node == null || depth > ADDRESS_BAR_DEPTH) return
        if (isAddressBar(node) && !node.isFocused) {
            addressTextOf(node)?.let { out.add(it) }
        }
        for (i in 0 until node.childCount) {
            collectAddressCandidates(node.getChild(i), depth + 1, out)
        }
    }

    /** Higher = more like a real, full URL. A path is the strongest signal. */
    private fun urlRichness(value: String): Int {
        val afterScheme = value.substringAfter("://", value)
        var score = 0
        if (value.startsWith("http", ignoreCase = true)) score += 2
        if (afterScheme.contains('/')) score += 5     // has a path -> richest
        if (afterScheme.contains('?')) score += 1
        score += minOf(value.length, 250) / 50
        return score
    }

    /** Pulls the address text off a bar node, skipping empty/hint placeholders. */
    private fun addressTextOf(node: AccessibilityNodeInfo): String? {
        val raw = node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        // Don't capture hint text like "Search or type URL" as if it were the page.
        val lower = raw.lowercase()
        if (ADDRESS_BAR_HINTS.any { it in lower }) return null
        return raw.take(MAX_URL_CHARS)
    }

    private fun isAddressBar(node: AccessibilityNodeInfo): Boolean {
        // Most reliable: the browser's own view id for its URL bar (see ADDRESS_BAR_IDS).
        val viewId = node.viewIdResourceName?.lowercase()
        if (viewId != null && ADDRESS_BAR_IDS.any { viewId.endsWith(it) }) return true
        // Fallback: an editable field, or a toolbar with an address-bar hint.
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


    /** "Dog - Wikipedia" -> "Dog". Strips a trailing " - Site" style suffix. */
    private fun cleanTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim()
        for (sep in listOf(" — ", " – ", " - ", " | ", " · ", " :: ")) {
            val idx = t.indexOf(sep)
            if (idx > 0) { t = t.substring(0, idx).trim(); break }
        }
        return t.take(MAX_TITLE_CHARS).takeIf { it.isNotBlank() }
    }

    /**
     * Collects text from INSIDE the WebView only — i.e. the actual web page,
     * skipping the browser's own chrome (toolbar, tabs, menus). This is what makes
     * "page content" the page, not the address bar.
     */
    private fun readWebViewText(): String? {
        val out = StringBuilder()
        rootInActiveWindow?.let { collectWebViewText(it, depth = 0, out = out, insideWeb = false) }
        return out.toString().trim().take(MAX_TEXT_CHARS).takeIf { it.isNotBlank() }
    }

    // private fun collectWebViewText(
        // node: AccessibilityNodeInfo?,
        // depth: Int,
        // out: StringBuilder,
        // insideWeb: Boolean,
    // ) {
        // if (node == null || depth > MAX_DEPTH || out.length >= MAX_TEXT_CHARS) return
        // val nowInside = insideWeb || node.className == "android.webkit.WebView"
        // if (nowInside) {
            // val t = node.text?.toString()?.trim()
            // if (!t.isNullOrEmpty()) out.append(t).append('\n')
        // }
        // for (i in 0 until node.childCount) {
            // collectWebViewText(node.getChild(i), depth + 1, out, nowInside)
        // }
    // }


    private fun collectWebViewText(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: StringBuilder,
        insideWeb: Boolean,
    ) {
        if (node == null || depth > MAX_DEPTH || out.length >= MAX_TEXT_CHARS) return
        val nowInside = insideWeb || node.className == "android.webkit.WebView"
        if (nowInside) {
            val t = node.text?.toString()?.trim()
            val d = node.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.append(t).append('\n')
            if (!d.isNullOrEmpty() && d != t) out.append(d).append('\n')  // page content also hides here
        }
        for (i in 0 until node.childCount) {
            collectWebViewText(node.getChild(i), depth + 1, out, nowInside)
        }
    }

    /**
     * DDG only exposes the full path while the omnibar is focused (tapped). Grab it
     * then, but only if the host matches the current page and there's a real path,
     * so a half-typed search isn't mistaken for the URL. Link taps don't focus the
     * bar, so those navigations stay host-only — that's a DDG limit, not a bug.
     */
    private fun readFocusedFullUrl(currentHost: String?): String? {
        if (currentHost == null) return null
        var found: String? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > ADDRESS_BAR_DEPTH || found != null) return
            if (isAddressBar(node) && node.isFocused) {
                val t = node.text?.toString()?.trim()
                if (!t.isNullOrBlank() &&
                    hostInText(t) == currentHost &&
                    t.substringAfter("://", t).contains('/')
                ) {
                    found = t.take(MAX_URL_CHARS)
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        rootInActiveWindow?.let { walk(it, 0) }
        return found
    }

    override fun onInterrupt() {
        // Nothing to clean up.
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(recheck)
        overlay?.hide()
        super.onDestroy()
    }


    /**
     * DEBUG ONLY. Logs every text-bearing / editable node in the current browser
     * window with its view-id, class, editable/focused flags, text and
     * description. Open DuckDuckGo on a known page (e.g. the Dog article), then
     * read the "NODE DUMP" row in the list to see EXACTLY which node holds the full
     * URL on your version. Add that node's id suffix to ADDRESS_BAR_IDS, then set
     * DEBUG_DUMP_NODES = false.
     */
     private fun dumpBrowserNodes(root: AccessibilityNodeInfo, packageName: String) {
        val flagged = StringBuilder()
        val all = StringBuilder()
        dumpNode(root, depth = 0, all = all, flagged = flagged)
        val out = buildString {
            append("=== LIKELY URL / INPUT NODES (look here first) ===\n")
            append(if (flagged.isBlank()) "(none found)\n" else flagged.toString())
            append("\n=== ALL TEXT NODES ===\n")
            append(all)
        }
        MonitorStore.record(
            this,
            MonitorEntry(
                timestamp = System.currentTimeMillis(),
                kind = MonitorEntry.KIND_PAGE,
                packageName = packageName,
                title = "NODE DUMP",
                text = out.take(8000),
            ),
        )
    }

    private fun dumpNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        all: StringBuilder,
        flagged: StringBuilder,
    ) {
        if (node == null || depth > 30) return
        val id = node.viewIdResourceName
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val idLower = id?.lowercase()
        val urlish = idLower != null &&
            ("url" in idLower || "omni" in idLower || "address" in idLower || "location" in idLower)
        val line = "id=$id cls=${node.className} edit=${node.isEditable} " +
            "foc=${node.isFocused} text=$text desc=$desc\n"
        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || node.isEditable || urlish) {
            all.append(line)
        }
        if (urlish || node.isEditable) flagged.append("★ ").append(line)
        for (i in 0 until node.childCount) {
            dumpNode(node.getChild(i), depth + 1, all, flagged)
        }
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

        private val HOST_PATTERN = Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})(?:[/?#]\S*)?""", RegexOption.IGNORE_CASE)

        private const val MAX_URL_CHARS = 2048

        // Known address-bar view IDs, matched by suffix (the package prefix varies).
        // This is the list to extend if a browser's URL isn't being captured.
        // Find a browser's real id: open it, and if the URL column stays blank,
        // its bar id isn't here yet — see the README for how to discover it.
        private val ADDRESS_BAR_IDS = listOf(
            ":id/url_bar",                        // Chrome, Edge, Brave, most Chromium
            ":id/url_field",                      // Opera
            ":id/mozac_browser_toolbar_url_view", // Firefox / Fenix / Focus
            ":id/location_bar_edit_text",         // Samsung Internet
            ":id/omnibartextinput",               // DuckDuckGo (classic omnibar)
            ":id/omnibartextinput",               // DuckDuckGo (casing variant)
        )

        // Diagnostics: true logs a "NODE DUMP" row for the browsers below. Turn OFF
        // once you've found the URL node.
        private const val DEBUG_DUMP_NODES = false
        private const val DUMP_INTERVAL_MS = 1500L
        private val BROWSER_DEBUG_PACKAGES = setOf("com.duckduckgo.mobile.android")

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

    // Latest-frame-wins scoring pipeline. Scoring is far slower than capturing, so
    // rather than queue every frame (which makes the log fall further and further
    // behind), we keep only the most recent unprocessed frame and a single worker
    // scores it, then picks up whatever is newest. Frames captured while the worker
    // is busy are dropped — the log always reflects the most recent screen.
    private val frameLock = Any()
    private var pendingFrame: Frame? = null
    private var workerRunning = false
    private var droppedSinceProcessed = 0

    private class Frame(val bitmap: Bitmap, val timestamp: Long, val appPackage: String?)

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

        // Warm up the classifier so the first scored frame doesn't pay the
        // (one-time, multi-second) model staging + load cost.
        NsfwClassifier.warmUp(applicationContext)
    }

    /** Runs on the capture thread for every screen frame. Cheap unless it is time to save. */
    private fun onFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastSavedAt < CAPTURE_INTERVAL_MS) return

            val appPackage = ForegroundApp.packageName
            // Skip our own UI and whitelisted system/launcher surfaces entirely:
            // no screenshot, no scoring. Advance the clock so we re-check at the
            // normal interval rather than on every single frame.
            if (appPackage == packageName || CaptureWhitelist.contains(appPackage)) {
                lastSavedAt = now
                return
            }
            lastSavedAt = now

            submitFrame(Frame(image.toBitmap(), now, appPackage))
        } finally {
            image.close()
        }
    }

    /** Hand the newest frame to the worker, dropping (and freeing) any unprocessed one. */
    private fun submitFrame(frame: Frame) {
        val dropped: Frame?
        synchronized(frameLock) {
            dropped = pendingFrame
            if (dropped != null) droppedSinceProcessed++
            pendingFrame = frame
        }
        dropped?.bitmap?.recycle()
        ensureWorker()
    }

    /** Start the single scoring worker if it isn't already running. */
    private fun ensureWorker() {
        synchronized(frameLock) {
            if (workerRunning) return
            workerRunning = true
        }
        saveScope.launch {
            while (true) {
                val next = synchronized(frameLock) {
                    val f = pendingFrame
                    pendingFrame = null
                    if (f == null) {
                        workerRunning = false
                        null
                    } else {
                        val dropped = droppedSinceProcessed
                        droppedSinceProcessed = 0
                        Pair(f, dropped)
                    }
                } ?: break
                processFrame(next.first, next.second)
            }
        }
    }

    /** Save the screenshot, score it, and record the row. Runs on the worker only. */
    private fun processFrame(frame: Frame, droppedWhileBusy: Int) {
        val startedAt = System.currentTimeMillis()
        val dir = File(filesDir, CAPTURE_DIR).apply { mkdirs() }
        val file = File(dir, "${frame.timestamp}.jpg")
        var nsfwScore: Float?
        try {
            FileOutputStream(file).use { out ->
                frame.bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            // Score the frame while the bitmap is still alive (cheaper than
            // re-decoding the JPEG). Best-effort: null if the model isn't ready.
            nsfwScore = NsfwClassifier.score(applicationContext, frame.bitmap)
        } finally {
            frame.bitmap.recycle()
        }

        MonitorStore.record(
            this,
            MonitorEntry(
                timestamp = frame.timestamp,
                kind = MonitorEntry.KIND_SCREEN,
                packageName = frame.appPackage,
                screenshotPath = file.absolutePath,
                nsfwScore = nsfwScore,
            ),
        )

        val totalMs = System.currentTimeMillis() - startedAt
        android.util.Log.i(
            CAPTURE_TAG,
            "frame done in ${totalMs}ms (dropped $droppedWhileBusy while busy) " +
                "pkg=${frame.appPackage} score=${nsfwScore?.let { "%.2f".format(Locale.US, it) } ?: "n/a"}",
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

        private const val CAPTURE_TAG = "ScreenCapture"
        private const val NOTIF_ID = 1001
        private const val RESULT_INVALID = 0
        private const val CAPTURE_DIR = "captures"
        // Minimum gap between captures. Scoring (the slow step) paces real throughput;
        // this just bounds how often we sample. Raise it toward your measured
        // per-frame time (see the ScreenCapture log) if you see frames being dropped.
        private const val CAPTURE_INTERVAL_MS = 4000L
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

// --------------------------------------------------------------
// NsfwClassifier
// --------------------------------------------------------------


/**
 * On-device NSFW image scorer — a direct Kotlin port of the IMAGE_ANALYSER Rust
 * harness's `--file` mode. It uses the SAME model (adamcodd-vit-nsfw ViT,
 * Apache-2.0), the SAME ONNX Runtime version (1.22.0), and the SAME calibration
 * math, so the 0..1 score it returns here matches what the desktop harness prints.
 *
 * Pipeline (every tunable comes from the bundled preproc.json / thresholds.json
 * assets, with hardcoded fallbacks that mirror those files):
 *
 *   bitmap -> resize 384x384 (stretch) -> (px/255 - 0.5)/0.5 -> NCHW RGB float[1,3,384,384]
 *          -> ViT -> softmax -> raw = P(nsfw) -> calibrate(raw, threshold) -> 0..1
 *
 * The quantized INT8 model + sidecars ship as APK assets (copied once to filesDir,
 * mmap'd by ORT so they don't sit on the Java heap). The full-precision model is
 * NOT shipped on-device: it ran 3-6x slower here (couldn't keep up while a browser
 * was busy), and on the test set INT8 matched it (0 verdict disagreements, scores
 * within 0.06, skewing slightly toward catching) — so INT8 alone is the engine.
 *
 * Everything is best-effort: any failure (no model bundled, load error, bad frame)
 * disables scoring and returns null rather than crashing the capture pipeline.
 */
object NsfwClassifier {

    private const val TAG = "NsfwClassifier"
    private const val ASSET_DIR = "nsfw"
    private const val MODEL_NAME = "model.onnx"

    /** Calibration steepness — must match `calibrate::K` in the Rust harness. */
    private const val CALIBRATION_K = 1.8f

    // All ONNX work (session creation AND every inference) runs on this single
    // thread, pinned to foreground priority so the OS schedules it on the fast
    // "big" CPU cores instead of the throttled efficiency cores a background thread
    // gets. ORT's own worker threads, spawned when a session is built here, inherit
    // that scheduling — much of the gap between a ~1s run and a ~6s one. Single-
    // threaded, so no extra locking is needed.
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nsfw-infer")
    }

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    private var inputName: String = "pixel_values"
    private var triedInit = false          // worker-thread only
    @Volatile private var usingXnnpack = false

    // Preproc + threshold (from the bundled sidecars; defaults mirror those files
    // so scoring still works if a JSON read fails). Edit thresholds.json to retune.
    private var modelName = "adamcodd-vit-nsfw-int8"
    private var inputSize = 384
    private var mean = floatArrayOf(0.5f, 0.5f, 0.5f)
    private var std = floatArrayOf(0.5f, 0.5f, 0.5f)
    private var rescale = 1f / 255f
    private var applySoftmax = true
    private var nsfwIndex = 1
    private var threshold = 0.1f // THRESHOLD, DONT KNOW WHY ITS HERE SHOULD PROBABLY BE A CONSTANT AT THE TOP FOR EASE OF TUNING. PROBS THE SAME FOR MANY VARS TO BE HONEST...

    /** True once the model is ready. */
    val isReady: Boolean get() = session != null

    /**
     * Warm up the model on the inference thread (idempotent). Call when capture
     * starts so the first scored frame doesn't pay the load cost.
     */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        worker.execute { ensureSession(app) }
    }

    /**
     * Score one screen frame -> calibrated NSFW confidence in [0,1] (0.5 == on the
     * cutoff), or null if unavailable. Best-effort: never throws into the caller.
     * The bitmap is not modified or recycled.
     */
    fun score(context: Context, bitmap: Bitmap): Float? {
        val app = context.applicationContext
        return try {
            // Hop to the dedicated foreground-priority inference thread and wait.
            worker.submit(Callable { scoreOnWorker(app, bitmap) }).get()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "scoring failed", t)
            null
        }
    }

    /** Runs on the inference thread only. */
    private fun scoreOnWorker(context: Context, bitmap: Bitmap): Float? {
        val s = ensureSession(context) ?: return null
        val t0 = System.nanoTime()
        val calibrated = calibrate(runRaw(s, inputName, preprocess(bitmap)), threshold)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        android.util.Log.i(TAG, "inference %.0f ms -> %.3f".format(Locale.US, ms, calibrated))
        return calibrated
    }

    /**
     * Load the model + config once (idempotent). Runs on the inference thread so
     * ORT's spawned pool inherits its foreground scheduling. Returns the session,
     * or null if it couldn't be loaded (not retried).
     */
    private fun ensureSession(context: Context): OrtSession? {
        pinThread()
        session?.let { return it }
        if (triedInit) return null
        triedInit = true
        loadConfig(context)
        return try {
            val modelFile = stageModel(context, MODEL_NAME) ?: return null
            val created = buildSession(modelFile)
            inputName = created.inputNames.firstOrNull() ?: inputName
            session = created
            android.util.Log.i(
                TAG,
                "model ready: $modelName (${modelFile.name}) input=$inputName size=$inputSize " +
                    "threshold=$threshold xnnpack=$usingXnnpack",
            )
            created
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "model load failed; scoring disabled", t)
            null
        }
    }

    /** Build an ORT session for [modelFile] with tuned threads + XNNPACK. */
    private fun buildSession(modelFile: File): OrtSession {
        val environment = env ?: OrtEnvironment.getEnvironment().also { env = it }
        // Parallelism WITHIN one inference. Target the big cores only — clamped to
        // [2,4]; adding the slow "little" cores makes the run wait on the slowest.
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = (cores / 2).coerceIn(2, 4)
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
            // XNNPACK: optimized ARM kernels, same precision. Falls back to CPU.
            try {
                addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                usingXnnpack = true
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "XNNPACK unavailable; using default CPU backend", t)
            }
        }
        return environment.createSession(modelFile.absolutePath, opts)
    }

    /** Pin the inference thread to the fast cores (best-effort). */
    private fun pinThread() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
        } catch (_: Throwable) { /* best-effort */ }
    }

    /** bitmap -> normalized NCHW RGB float[1,3,n,n] (flat), matching preproc.json. */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val n = inputSize
        // ResizeMode::Stretch — straight resize to NxN, aspect ratio ignored.
        val resized = if (bitmap.width == n && bitmap.height == n) bitmap
                      else Bitmap.createScaledBitmap(bitmap, n, n, true)
        val pixels = IntArray(n * n)
        resized.getPixels(pixels, 0, n, 0, 0, n, n)
        if (resized !== bitmap) resized.recycle()

        // Normalize + lay out as NCHW, RGB channel order (matches preproc.json).
        val area = n * n
        val chw = FloatArray(3 * area)
        for (i in 0 until area) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            chw[i]            = (r * rescale - mean[0]) / std[0]   // R plane
            chw[area + i]     = (g * rescale - mean[1]) / std[1]   // G plane
            chw[2 * area + i] = (b * rescale - mean[2]) / std[2]   // B plane
        }
        return chw
    }

    /** Run one model on a preprocessed frame -> raw P(nsfw) in [0,1]. */
    private fun runRaw(session: OrtSession, inName: String, chw: FloatArray): Float {
        val n = inputSize
        val shape = longArrayOf(1, 3, n.toLong(), n.toLong())
        val environment = env ?: OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(chw), shape).use { tensor ->
            session.run(mapOf(inName to tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val logits = (results.get(0).value as Array<FloatArray>)[0]
                val probs = if (applySoftmax) softmax(logits) else logits
                return probs.getOrElse(nsfwIndex) { probs.lastOrNull() ?: 0f }
            }
        }
    }

    /**
     * Copy a bundled model asset into filesDir on first use. Re-copies if the
     * staged file's size doesn't match the asset (e.g. after a model update).
     * Returns the file, or null if the asset isn't bundled.
     */
    private fun stageModel(context: Context, fileName: String): File? {
        val assetPath = "$ASSET_DIR/$fileName"
        val outDir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val outFile = File(outDir, fileName)

        // openFd works because the asset is stored uncompressed (noCompress "onnx").
        val assetSize = try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (e: Exception) {
            -1L
        }
        if (assetSize < 0L) {
            val bundled = try { context.assets.open(assetPath).use { true } } catch (e: Exception) { false }
            if (!bundled) {
                android.util.Log.w(TAG, "no model bundled at assets/$assetPath")
                return null
            }
        }
        if (outFile.exists() && (assetSize < 0L || outFile.length() == assetSize)) return outFile

        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output, 1 shl 16) }
            }
            android.util.Log.i(TAG, "staged $fileName -> ${outFile.length()} bytes")
            outFile
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "failed to stage $fileName", t)
            outFile.delete()
            null
        }
    }

    /** Read tunables from the bundled sidecars; silently keep defaults on any error. */
    private fun loadConfig(context: Context) {
        try {
            val o = JSONObject(readAsset(context, "$ASSET_DIR/preproc.json"))
            modelName = o.optString("name", modelName)
            o.optJSONArray("input_size")?.let { if (it.length() > 0) inputSize = it.getInt(0) }
            o.optJSONArray("mean")?.let { mean = it.toFloat3(mean) }
            o.optJSONArray("std")?.let { std = it.toFloat3(std) }
            if (o.has("rescale")) rescale = o.getDouble("rescale").toFloat()
            if (o.has("apply_softmax")) applySoftmax = o.getBoolean("apply_softmax")
            o.optJSONArray("nsfw_label_indices")?.let { if (it.length() > 0) nsfwIndex = it.getInt(0) }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "preproc.json not read; using defaults", t)
        }
        try {
            val o = JSONObject(readAsset(context, "$ASSET_DIR/thresholds.json"))
            val level = o.optString("default_level", "strict")
            val models = o.optJSONObject("models")
            // Use the threshold for the model we loaded (by its preproc name).
            val m = models?.optJSONObject(modelName)
                ?: models?.keys()?.takeIf { it.hasNext() }?.let { models.optJSONObject(it.next()) }
            m?.let {
                threshold = when {
                    it.has(level) -> it.getDouble(level).toFloat()
                    it.has("moderate") -> it.getDouble("moderate").toFloat()
                    else -> threshold
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "thresholds.json not read; using default $threshold", t)
        }
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun org.json.JSONArray.toFloat3(fallback: FloatArray): FloatArray =
        if (length() >= 3) floatArrayOf(getDouble(0).toFloat(), getDouble(1).toFloat(), getDouble(2).toFloat())
        else fallback

    private fun softmax(x: FloatArray): FloatArray {
        if (x.isEmpty()) return x
        var m = x[0]
        for (v in x) if (v > m) m = v
        val exps = FloatArray(x.size) { exp(x[it] - m) }
        val sum = exps.sum()
        if (sum == 0f) return exps
        for (i in exps.indices) exps[i] /= sum
        return exps
    }

    private fun sigmoid(z: Float): Float = 1f / (1f + exp(-z))

    private fun half(x: Float, k: Float): Float {
        val s0 = 0.5f
        val sk = sigmoid(k)
        return (sigmoid(k * x) - s0) / (sk - s0)
    }

    /**
     * Calibrate a raw 0..1 score so `threshold` maps to exactly 0.5, steepest at
     * the boundary. Mirrors `calibrate::calibrate_k` in the Rust harness.
     */
    private fun calibrate(raw0: Float, threshold0: Float, k: Float = CALIBRATION_K): Float {
        val raw = raw0.coerceIn(0f, 1f)
        val t = threshold0.coerceIn(1e-6f, 1f - 1e-6f)
        val c = if (raw >= t) 0.5f + 0.5f * half((raw - t) / (1f - t), k)
                else 0.5f - 0.5f * half((t - raw) / t, k)
        return c.coerceIn(0f, 1f)
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
        "org.mozilla.focus",
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
        "org.mozilla.klar",
        "org.mozilla.rocket",
        "org.mozilla.reference.browser",
        "io.github.forkmaintainers.iceraven",
        "us.spotco.fennec_dos",

        // duckduckgo 
        "com.duckduckgo.mobile.android",

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
    private val onEntryLongClick: (MonitorEntry) -> Unit,
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
        holder.itemView.setOnLongClickListener { onEntryLongClick(entry); true }

        if (entry.kind == MonitorEntry.KIND_SCREEN) {
            holder.thumbnail.visibility = View.VISIBLE
            entry.screenshotPath?.let { holder.thumbnail.load(File(it)) }
            holder.primary.text = entry.packageName ?: "Screen"
            holder.secondary.text = entry.nsfwScore
                ?.let { score ->
                    val flag = if (score >= 0.5f) "  ⚠ flagged" else ""
                    "nsfw %.2f%s".format(Locale.US, score, flag)
                }
                ?: "Screenshot (not scored)"
            holder.meta.text = "$time  ·  screen"
        } else {
            holder.thumbnail.visibility = View.GONE
            holder.thumbnail.setImageDrawable(null)
            holder.primary.text = entry.title?.takeIf { it.isNotBlank() }
                ?: entry.url ?: entry.domain ?: entry.packageName ?: "Page"
            holder.secondary.text = entry.url ?: entry.domain ?: entry.packageName.orEmpty()
            val snippet = entry.text?.replace('\n', ' ')?.trim()?.take(40).orEmpty()
            holder.meta.text = snippet.ifBlank { "(none)" } + "   ·   $time"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MonitorEntry>() {
            override fun areItemsTheSame(old: MonitorEntry, new: MonitorEntry) = old.id == new.id
            override fun areContentsTheSame(old: MonitorEntry, new: MonitorEntry) = old == new
        }
    }
}
