package familytv.launcher.playback

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads (and taps) the streaming apps' screens so the launcher can verify a page before pressing Play.
 * Limited to the streaming packages in res/xml/screen_reader.xml. Only Disney+ exposes a readable
 * tree; Netflix and Prime Video draw their own UI and show up empty.
 */
class ScreenReaderService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            val cls = event.className?.toString() ?: return
            if (cls.contains("Activity") || pkg.contains("dream")) foreground = pkg
            // Only activities, not dialogs/toasts/views inside the app.
            if (cls.contains("Activity")) lastActivity[pkg] = cls
        }
    }

    override fun onInterrupt() = Unit

    private fun root(packageName: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.takeIf { it.packageName == packageName && packageName in STREAMING }

    /** Closes Google's screensaver with one Back press (a screensaver always eats the key). */
    fun dismissScreensaver(): Boolean =
        foreground?.contains("dream") == true && performGlobalAction(GLOBAL_ACTION_BACK)

    /** Every content description and text on [packageName]'s screen, top to bottom. */
    fun labels(packageName: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            (n.contentDescription ?: n.text)?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root(packageName))
        return out
    }

    /** Clicks the first node whose description/text equals [label]. */
    fun click(packageName: String, label: String): Boolean {
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            n ?: return null
            if ((n.contentDescription ?: n.text)?.toString() == label) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        return clickable(find(root(packageName)))
    }

    /** Clicks whatever currently has focus (e.g. the Play/Resume button a show page lands on). */
    fun clickFocused(packageName: String): Boolean =
        clickable(root(packageName)?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))

    /**
     * Clicks the first (leftmost) button in the row that holds the node labelled [anchorPrefix].
     * Disney+'s Play/Resume button has no label, but it always leads the row with "add to watchlist".
     */
    fun clickFirstInRowOf(packageName: String, anchorPrefix: String): Boolean {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            nodes += n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root(packageName))
        val anchor = nodes.firstOrNull { (it.contentDescription ?: it.text)?.startsWith(anchorPrefix) == true } ?: return false
        val row = android.graphics.Rect().also(anchor::getBoundsInScreen)
        val first = nodes
            .filter { it.isClickable }
            .map { n -> n to android.graphics.Rect().also(n::getBoundsInScreen) }
            .filter { (_, r) -> kotlin.math.abs(r.top - row.top) < 8 && r.left < row.left }
            .minByOrNull { (_, r) -> r.left }
            ?.first
        return first?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun clickable(node: AccessibilityNodeInfo?): Boolean {
        var n = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    /** Puts the TV to sleep without the remote (fallback for bedtime). */
    fun sleepScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        @Volatile
        var instance: ScreenReaderService? = null
            private set

        /** Package of whatever is in front, from window changes (all apps). */
        @Volatile
        var foreground: String? = null
            private set

        /** The only apps whose screens are ever read or tapped. */
        private val STREAMING = familytv.launcher.Service.entries.map { it.packageName }.toSet()

        /** Last activity each streaming app showed (e.g. Disney+'s PlaybackActivity vs MainActivity). */
        val lastActivity = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
