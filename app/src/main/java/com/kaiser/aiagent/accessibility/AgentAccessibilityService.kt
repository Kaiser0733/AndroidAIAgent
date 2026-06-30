package com.kaiser.aiagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * v0.6.2: The accessibility service that powers UI automation.
 *
 * The agent's accessibility tools (read_screen, tap_text, type_text,
 * scroll, go_back, go_home) do not run inside the service itself —
 * they live in the tool layer with the rest of the agent. Instead,
 * each tool asks [AgentAccessibilityController] to dispatch an action
 * to the currently-running service instance.
 *
 * Lifecycle:
 *   - Android starts the service when the user grants accessibility
 *     permission in system settings.
 *   - onServiceConnected caches `instance` so tools can reach it.
 *   - onInterrupt / onUnbind clear `instance`.
 *
 * All public methods are best-effort and return a result string so
 * the tool layer can surface the outcome to the model.
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.i("AgentAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We do not need event-driven behavior — the agent polls the
        // screen on demand via read_screen.
    }

    override fun onInterrupt() {
        Timber.w("AgentAccessibilityService interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Returns a flat list of clickable elements currently visible on
     * the screen. Each entry is "text|className|bounds".
     */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "ERROR: no active window"
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var count = 0
        while (queue.isNotEmpty() && count < 200) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val label = text ?: desc ?: ""
            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            val b = android.graphics.Rect()
            node.getBoundsInScreen(b)
            val interactive = node.isClickable || node.isFocusable ||
                cls == "EditText" || cls == "Button" || cls == "ImageButton"
            if (label.isNotBlank() || interactive) {
                sb.append(label.replace('|', ' ').ifBlank { "—" })
                sb.append('|')
                sb.append(cls)
                sb.append('|')
                sb.append("${b.left},${b.top},${b.right},${b.bottom}")
                sb.append(if (node.isClickable) "|clickable" else "")
                sb.append(if (cls == "EditText") "|editable" else "")
                sb.append('\n')
                count++
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return sb.toString().ifBlank { "ERROR: no interactive elements found" }
    }

    /**
     * Taps the first node whose text or content description contains
     * [text] (case-insensitive substring match).
     */
    fun tapText(text: String): String {
        val root = rootInActiveWindow ?: return "ERROR: no active window"
        val target = findNodeByText(root, text)
            ?: return "ERROR: no element with text '$text' found on screen"
        val b = android.graphics.Rect()
        target.getBoundsInScreen(b)
        var ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok) {
            var ancestor: AccessibilityNodeInfo? = target.parent
            while (ancestor != null && !ancestor.isClickable) {
                ancestor = ancestor.parent
            }
            if (ancestor != null) {
                ok = ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        if (!ok) {
            ok = dispatchTap(b.exactCenterX(), b.exactCenterY())
        }
        return if (ok) {
            "OK tapped '$text' at (${b.exactCenterX().toInt()},${b.exactCenterY().toInt()})"
        } else {
            "ERROR: could not tap '$text' (node found but click failed)"
        }
    }

    /**
     * Types [text] into the currently-focused input field. If no
     * input is focused, tries to find an EditText on screen and
     * focus it first.
     */
    fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "ERROR: no active window"
        var focus = findFocusedEditText(root)
        if (focus == null) {
            focus = findFirstEditText(root)
            focus?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        if (focus == null) return "ERROR: no editable text field on screen"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) "OK typed '${text.take(40)}' into text field"
        else "ERROR: SET_TEXT action failed on the focused field"
    }

    /**
     * Scrolls the screen in [direction] = up|down|left|right.
     */
    fun scroll(direction: String): String {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val cx = w / 2f
        val cy = h / 2f
        val swipeLen = (h * 0.4f)
        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "up"    -> floatArrayOf(cx, cy + swipeLen, cx, cy - swipeLen)
            "down"  -> floatArrayOf(cx, cy - swipeLen, cx, cy + swipeLen)
            "left"  -> floatArrayOf(cx + swipeLen, cy, cx - swipeLen, cy)
            "right" -> floatArrayOf(cx - swipeLen, cy, cx + swipeLen, cy)
            else    -> return "ERROR: direction must be up|down|left|right"
        }
        val ok = dispatchSwipe(startX, startY, endX, endY, 300)
        return if (ok) "OK scrolled $direction"
        else "ERROR: swipe gesture dispatch failed"
    }

    /** Presses the BACK hardware key via the global action. */
    fun goBack(): String {
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        return if (ok) "OK back" else "ERROR: GLOBAL_ACTION_BACK failed"
    }

    /** Presses the HOME hardware key via the global action. */
    fun goHome(): String {
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        return if (ok) "OK home" else "ERROR: GLOBAL_ACTION_HOME failed"
    }

    /** Presses the RECENTS hardware key (opens the task switcher). */
    fun goRecents(): String {
        val ok = performGlobalAction(GLOBAL_ACTION_RECENTS)
        return if (ok) "OK recents" else "ERROR: GLOBAL_ACTION_RECENTS failed"
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val lower = text.lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var exactMatch: AccessibilityNodeInfo? = null
        var partialMatch: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val t = node.text?.toString()
            val d = node.contentDescription?.toString()
            if (t != null && t.equals(text, ignoreCase = true)) {
                exactMatch = node
                break
            }
            if (d != null && d.equals(text, ignoreCase = true)) {
                exactMatch = node
                break
            }
            if (partialMatch == null) {
                if ((t != null && t.lowercase().contains(lower)) ||
                    (d != null && d.lowercase().contains(lower))
                ) {
                    partialMatch = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return exactMatch ?: partialMatch
    }

    private fun findFocusedEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString() ?: ""
            if (cls.endsWith("EditText") && node.isFocused) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    private fun findFirstEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString() ?: ""
            if (cls.endsWith("EditText")) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 80)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } else false
    }

    private fun dispatchSwipe(
        startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } else false
    }

    private fun Rect.exactCenterX(): Float = (left + right) / 2f
    private fun Rect.exactCenterY(): Float = (top + bottom) / 2f

    companion object {
        @Volatile
        @JvmStatic
        var instance: AgentAccessibilityService? = null
    }
}
