package com.example.webtrafficmonitor.monitor

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.webtrafficmonitor.data.MonitorEntry
import com.example.webtrafficmonitor.data.MonitorStore

/**
 * Reads what is on screen: the foreground app, the website domain, a rough page
 * title, and a sample of the visible text.
 *
 * It is event-driven and throttled, so it does almost no work while the screen is
 * not changing.
 *
 * Domain detection is deliberately browser-agnostic: instead of looking up a
 * specific browser's address-bar view (which changes between browsers and app
 * versions), it searches the on-screen text and content descriptions for a
 * hostname. That keeps working across browsers and across app redesigns.
 */
class PageMonitorAccessibilityService : AccessibilityService() {

    private var lastSignature: String? = null
    private var lastProcessedAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return // ignore our own app

        // Keep the latest foreground app available for the screen-capture service.
        ForegroundApp.packageName = packageName

        val now = System.currentTimeMillis()
        if (now - lastProcessedAt < MIN_INTERVAL_MS) return
        lastProcessedAt = now

        val root = rootInActiveWindow ?: return

        val text = sampleVisibleText(root)
        val firstLine = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        val eventTitle = event.text
            .joinToString(" ") { it.toString() }
            .trim()
            .takeIf { it.isNotBlank() }
        val title = eventTitle ?: firstLine?.take(MAX_TITLE_CHARS)
        val domain = findDomain()

        // Re-record when the domain or page (first line) changes, but not on every
        // tiny content tick for the same page.
        val signature = "$packageName|$domain|${firstLine?.take(40)}"
        if (signature == lastSignature) return
        lastSignature = signature

        MonitorStore.record(
            this,
            MonitorEntry(
                timestamp = now,
                kind = MonitorEntry.KIND_PAGE,
                packageName = packageName,
                title = title,
                domain = domain,
                text = text,
            ),
        )
    }

    override fun onInterrupt() {
        // Nothing to clean up.
    }

    /**
     * Finds the website domain by scanning visible nodes for a hostname. The
     * address bar can be in a separate window from the page content, so we look
     * across all visible windows.
     */
    private fun findDomain(): String? {
        rootInActiveWindow?.let { findHost(it, depth = 0)?.let { host -> return host } }
        for (window in windows) {
            window.root?.let { findHost(it, depth = 0)?.let { host -> return host } }
        }
        return null
    }

    private fun findHost(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > MAX_DEPTH) return null

        hostInText(node.text?.toString())?.let { return it }
        hostInText(node.contentDescription?.toString())?.let { return it }

        for (i in 0 until node.childCount) {
            findHost(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
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

    /**
     * Finds the first hostname inside a piece of text. Handles a bare host
     * ("en.wikipedia.org"), a full URL ("https://example.com/page"), and a host
     * embedded in a phrase (the address bar reads "en.wikipedia.org/... Search or
     * enter address"). Returns null if there is no hostname.
     */
    private fun hostInText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = HOST_PATTERN.find(raw) ?: return null
        return match.groupValues[1].lowercase()
    }

    companion object {
        private const val MIN_INTERVAL_MS = 700L
        private const val MAX_TEXT_CHARS = 1000
        private const val MAX_TITLE_CHARS = 120
        private const val MAX_DEPTH = 40

        // Matches a hostname (label.label.tld), optionally with scheme and path,
        // anywhere inside a larger string. Group 1 is the host.
        private val HOST_PATTERN =
            Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})(?:[/?#]\S*)?""", RegexOption.IGNORE_CASE)
    }
}
