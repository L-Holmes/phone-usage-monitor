package com.example.webtrafficmonitor.monitor

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.webtrafficmonitor.block.BlockRules
import com.example.webtrafficmonitor.block.OverlayController
import com.example.webtrafficmonitor.data.MonitorEntry
import com.example.webtrafficmonitor.data.MonitorStore

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        BlockRules.load(this)
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
        // Ignore our own app (including our own overlay window, whose events would
        // otherwise make the cover dismiss itself).
        if (packageName == this.packageName) return
        // The status bar / notification shade fire events constantly and are not
        // foreground content; skipping them stops their churn from clearing a block.
        if (packageName in IGNORED_PACKAGES) return

        ForegroundApp.packageName = packageName

        val now = System.currentTimeMillis()
        if (now - lastProcessedAt < MIN_INTERVAL_MS) return
        lastProcessedAt = now

        val root = rootInActiveWindow ?: return

        // The host is read fresh each event. It is non-null only when an address bar
        // is actually on screen (i.e. a web page is being viewed) — NOT in the tab
        // switcher, on the home screen, or in a non-browser app. Blocking keys off
        // this, so a blocked page's tab thumbnail/title does not trigger the cover.
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

        // Re-check the block every event so the cover persists while the page shows.
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

    private fun evaluateBlock(host: String?, title: String?) {
        val controller = overlay ?: return

        // Only block when an address bar is visible (an actual web page), so the tab
        // switcher and home screen never get covered.
        val matchedRule = if (host != null) BlockRules.matchedRule(host, title) else null

        if (matchedRule != null) {
            controller.show(
                reason = matchedRule,
                // Fire Back, but don't hide here: if Back reaches allowed content the
                // detection loop hides the cover; if it can't go anywhere, the cover
                // stays and the content remains hidden. Debounced so one tap is one
                // page (rapid double-taps don't skip two pages back).
                onGoBack = {
                    val tapAt = System.currentTimeMillis()
                    if (tapAt - lastGoBackAt >= GO_BACK_DEBOUNCE_MS) {
                        lastGoBackAt = tapAt
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                },
                // Home always works as an escape hatch; hide right away since going
                // home reliably removes the blocked app from the foreground.
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
            controller.hide()
        }
    }

    /**
     * Reads the host shown in the browser address bar, but only when the bar is
     * not being edited (so a half-typed URL or an autocomplete suggestion is not
     * mistaken for the current page). Returns null if no address bar is visible.
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

    /** Looks like a browser address bar: an editable field, or a toolbar with an address hint. */
    private fun isAddressBar(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable || node.className == "android.widget.EditText") return true
        val description = node.contentDescription?.toString()?.lowercase() ?: return false
        return ADDRESS_BAR_HINTS.any { it in description }
    }

    /** Walks the screen's text, capped in depth and length so it stays cheap. */
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

    /** Finds the first hostname inside a piece of text, or null. */
    private fun hostInText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = HOST_PATTERN.find(raw) ?: return null
        return match.groupValues[1].lowercase()
    }

    override fun onInterrupt() {
        // Nothing to clean up.
    }

    override fun onDestroy() {
        overlay?.hide()
        super.onDestroy()
    }

    companion object {
        private const val MIN_INTERVAL_MS = 700L
        private const val MAX_TEXT_CHARS = 1000
        private const val MAX_TITLE_CHARS = 120
        private const val MAX_DEPTH = 40
        private const val ADDRESS_BAR_DEPTH = 25
        private const val GO_BACK_DEBOUNCE_MS = 800L

        // Status bar / notification shade: skip entirely.
        private val IGNORED_PACKAGES = setOf("com.android.systemui")

        // Home screens and similar: still checked for blocking (so a block clears
        // when you go home), but not worth recording in the list.
        private val NOT_LOGGED_PACKAGES = setOf(
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.microsoft.launcher",
        )

        // Content descriptions that mark a browser address bar across browsers.
        private val ADDRESS_BAR_HINTS = listOf(
            "search or enter",
            "search or type",
            "address bar",
            "enter address",
            "search address",
            "edit url",
        )

        // A hostname (label.label.tld), optionally with scheme and path. Group 1 is the host.
        private val HOST_PATTERN =
            Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})(?:[/?#]\S*)?""", RegexOption.IGNORE_CASE)
    }
}
