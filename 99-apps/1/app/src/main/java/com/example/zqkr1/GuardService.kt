package com.example.zqkr1

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class GuardService : AccessibilityService() {

    companion object {
        const val WATCHED_APP = "com.example.webtrafficmonitor"
        private val REDDIT_APPS = setOf("com.reddit.frontpage")
        private val BLOCKED_HOSTS = listOf("reddit.com", "photon-reddit.com")
        private val ALWAYS_ALLOWED = setOf(
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.incallui",
            "com.android.emergency",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.vending",
        )
        private const val RECHECK_MS = 500L
        private const val INSTALLED_CACHE_MS = 2_000L
        private const val NODE_BUDGET = 400
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var keyguard: KeyguardManager
    private var cover: View? = null
    private var coverText: TextView? = null
    private var allowed: Set<String> = emptySet()
    private var installedAt = 0L
    private var installed = true

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        allowed = buildAllowList()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) {
            hideCover()
            return
        }
        evaluate()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        hideCover()
        super.onDestroy()
    }

    private val recheck = Runnable { evaluate() }

    private fun evaluate() {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val pkg = root?.packageName?.toString()
        val reason = blockReason(pkg, root)
        if (reason != null) showCover(reason) else if (pkg != null) hideCover()
        main.removeCallbacks(recheck)
        if (cover != null) main.postDelayed(recheck, RECHECK_MS)
    }

    private fun blockReason(pkg: String?, root: AccessibilityNodeInfo?): String? {
        if (pkg == null || pkg == packageName) return null
        if (keyguard.isKeyguardLocked) return null
        if (!watchedAppInstalled() && pkg !in allowed) {
            return "Web Traffic Monitor has been removed.\n\nOnly the phone, home screen and Play Store work until it is reinstalled."
        }
        if (pkg in REDDIT_APPS) return "Reddit is blocked."
        if (root != null && redditUrlOnScreen(root)) return "Reddit is blocked."
        return null
    }

    private fun watchedAppInstalled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - installedAt < INSTALLED_CACHE_MS) return installed
        installed = try {
            packageManager.getPackageInfo(WATCHED_APP, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        installedAt = now
        return installed
    }

    private fun buildAllowList(): Set<String> {
        val set = ALWAYS_ALLOWED.toMutableSet()
        set += packageName
        set += WATCHED_APP
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).forEach { set += it.activityInfo.packageName }
        (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage?.let { set += it }
        return set
    }

    /** Looks for a reddit host in the browser's address bar (or any URL-ish text field). */
    private fun redditUrlOnScreen(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < NODE_BUDGET) {
            val node = queue.removeFirst()
            seen++
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            val urlish = node.isEditable || "url" in id || "address" in id || "location" in id
            if (urlish && isBlockedUrl(node.text?.toString())) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    private fun isBlockedUrl(text: String?): Boolean {
        if (text.isNullOrBlank() || ' ' in text.trim()) return false
        val host = text.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore('?').substringBefore(':')
        return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun showCover(reason: String) {
        coverText?.let { it.text = reason; return }
        val text = TextView(this).apply {
            this.text = reason
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val home = Button(this).apply {
            this.text = "Go to home screen"
            setOnClickListener {
                hideCover()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(20, 20, 24))
            setPadding(64, 64, 64, 64)
            addView(text)
            addView(home, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 48 })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        runCatching { wm.addView(layout, params) }.onSuccess {
            cover = layout
            coverText = text
        }
    }

    private fun hideCover() {
        main.removeCallbacks(recheck)
        cover?.let { runCatching { wm.removeViewImmediate(it) } }
        cover = null
        coverText = null
    }
}
