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
     * v0.6.4: Returns a flat list of clickable elements currently
     * visible on the screen. Each entry is "text|className|bounds".
     *
     * Polls for up to 5 seconds for an active window to appear —
     * useful when read_screen is called immediately after open_app
     * and the target app is still loading.
     */
    fun readScreen(): String {
        var root = rootInActiveWindow
        // v0.6.4: if no active window yet, poll for up to 5 seconds.
        // This handles the case where read_screen is called right
        // after open_app and the target app is still coming up.
        var pollMs = 0
        while (root == null && pollMs < 5000) {
            try { Thread.sleep(200) } catch (e: InterruptedException) { break }
            pollMs += 200
            root = rootInActiveWindow
        }
        if (root == null) {
            return "ERROR: no active window after waiting ${pollMs}ms. " +
                "The accessibility service may not be running, or the " +
                "target app crashed. Call wait_seconds(2) and try again."
        }

        val pkg = root.packageName?.toString() ?: "(unknown)"
        val sb = StringBuilder()
        sb.appendLine("WINDOW: $pkg")
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
        val out = sb.toString()
        // If we found elements but they're suspiciously few (≤2) AND
        // the user just opened an app, the screen is probably still
        // loading. Tell the model to wait and retry.
        if (count <= 2) {
            return out + "\nHINT: only $count elements found — the screen " +
                "may still be loading. Call wait_seconds(2) and then " +
                "read_screen again."
        }
        return out.ifBlank { "ERROR: no interactive elements found" }
    }

    /**
     * v0.6.3: Taps an element matching [text] with smart disambiguation.
     *
     * Algorithm:
     *  1. Collect ALL matching nodes (exact + substring, case-insensitive).
     *  2. Rank: exact-match first, then shorter label (so "Search" beats
     *     "Voice search"), then clickable preferred.
     *  3. If [index] >= 0 → tap the i-th ranked match (0-based).
     *  4. Else if exactly 1 match → tap it.
     *  5. Else if the top-2 matches have DIFFERENT labels → tap #0
     *     (auto-disambiguate by label specificity).
     *  6. Else (top-2 have the same label) → return the list so the
     *     model can re-call with index= to pick.
     *
     * This fixes the v0.6.2 bug where tap_text "Search" on YouTube
     * matched "Voice search" first (BFS order) and opened the mic.
     */
    fun tapText(text: String, index: Int = -1): String {
        val root = rootInActiveWindow ?: return "ERROR: no active window"
        val matches = findAllMatchingNodes(root, text)
        if (matches.isEmpty()) {
            return "ERROR: no element with text '$text' found on screen. " +
                "Call read_screen to see what labels are available."
        }

        // Rank: exact > shorter label > clickable.
        val ranked = matches.sortedWith(
            compareBy(
                { if (it.exact) 0 else 1 },
                { it.label.length },
                { if (it.node.isClickable) 0 else 1 }
            )
        )

        val chosen: Match = when {
            index >= 0 && index < ranked.size -> ranked[index]
            ranked.size == 1 -> ranked[0]
            else -> {
                val top = ranked[0]
                val second = ranked[1]
                // If the top-2 have the same label AND same exact-ness,
                // we cannot safely auto-pick — return the list.
                if (top.label.equals(second.label, ignoreCase = true) &&
                    top.exact == second.exact
                ) {
                    return buildString {
                        appendLine("AMBIGUOUS: ${ranked.size} elements match '$text'.")
                        appendLine("Re-call tap_text with index= to pick one:")
                        ranked.take(6).forEachIndexed { i, m ->
                            val b = m.bounds
                            appendLine("  index=$i  label='${m.label}'  class=${m.cls}  at=(${b.left},${b.top})")
                        }
                        if (ranked.size > 6) appendLine("  ... and ${ranked.size - 6} more")
                    }.trimEnd()
                } else {
                    top
                }
            }
        }

        val node = chosen.node
        val b = android.graphics.Rect()
        node.getBoundsInScreen(b)
        var ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok) {
            var ancestor: AccessibilityNodeInfo? = node.parent
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
            "OK tapped '${chosen.label}' at (${b.exactCenterX().toInt()},${b.exactCenterY().toInt()})"
        } else {
            "ERROR: could not tap '${chosen.label}' (node found but click failed)"
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

    /**
     * v0.7.2: Submits a YouTube search.
     *
     * YouTube's search panel has NO submit button — only the keyboard's
     * enter key or tapping an autocomplete suggestion works. This method
     * tries multiple strategies:
     *
     *   1. ACTION_IME_ENTER on the focused EditText (the proper way,
     *      but fails if the IME isn't connected after SET_TEXT).
     *   2. Tap the first autocomplete suggestion (YouTube shows these
     *      immediately after typing — tapping one searches for it).
     *      This is the MOST RELIABLE strategy for YouTube.
     *   3. ACTION_CLICK on the EditText itself (last resort).
     *
     * NEVER taps anything with "voice" or "mic" in the label — that's
     * the v0.7.0/v0.7.1 bug where the mic got opened.
     */
    fun submitYouTubeSearch(): String {
        val root = rootInActiveWindow ?: return "ERROR: no active window"

        // Strategy 1: ACTION_IME_ENTER on the focused EditText
        val focus = findFocusedEditText(root) ?: findFirstEditText(root)
        if (focus != null) {
            focus.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            try { Thread.sleep(300) } catch (e: InterruptedException) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val imeEnterAction = 65553 // ACTION_IME_ENTER
                    if (focus.performAction(imeEnterAction)) {
                        // Verify: wait 1.5s and check if the screen changed.
                        // If autocomplete suggestions disappeared, the search worked.
                        Thread.sleep(1500)
                        val newRoot = rootInActiveWindow
                        if (newRoot != null && !hasAutocompleteSuggestions(newRoot)) {
                            return "OK submitted (ACTION_IME_ENTER)"
                        }
                        // If suggestions are still there, IME enter didn't work.
                        // Fall through to strategy 2.
                    }
                } catch (e: Exception) {
                    Timber.w(e, "ACTION_IME_ENTER failed")
                }
            }
        }

        // Strategy 2: Tap the first autocomplete suggestion.
        // YouTube shows suggestions immediately after typing. Each is
        // a tappable row with a magnifying glass icon. Tapping one
        // searches for that suggestion immediately.
        val suggestion = findFirstAutocompleteSuggestion(rootInActiveWindow ?: root)
        if (suggestion != null) {
            val b = android.graphics.Rect()
            suggestion.getBoundsInScreen(b)
            if (dispatchTap(b.exactCenterX(), b.exactCenterY())) {
                return "OK submitted (tapped first autocomplete suggestion at ${b.exactCenterX().toInt()},${b.exactCenterY().toInt()})"
            }
        }

        // Strategy 3: Click the EditText itself (some apps bind submit to click)
        if (focus != null) {
            if (focus.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "OK submitted (ACTION_CLICK on EditText)"
            }
        }

        return "ERROR: couldn't submit search. No IME enter, no autocomplete suggestions, " +
            "and EditText click failed. YouTube's UI may have changed."
    }

    /**
     * v0.7.2: Detects whether the current screen has YouTube autocomplete
     * suggestions (used to verify whether ACTION_IME_ENTER actually worked).
     */
    private fun hasAutocompleteSuggestions(root: AccessibilityNodeInfo): Boolean {
        // Autocomplete suggestions are tappable rows below the search bar
        // that contain text matching the query. They typically have a
        // magnifying glass icon (content description "Search" but NOT "Voice").
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && count < 50) {
            val node = queue.removeFirst()
            if (node.isClickable) {
                val texts = mutableListOf<String>()
                collectTexts(node, texts)
                if (texts.isNotEmpty() && texts.size <= 3) {
                    // A suggestion row has 1-3 text children (the suggestion text)
                    // and is below the search bar (bounds.top > 200)
                    val b = android.graphics.Rect()
                    node.getBoundsInScreen(b)
                    if (b.top > 200 && b.height() > 80 && b.height() < 300) {
                        count++
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return count >= 2  // 2+ clickable rows below the search bar = suggestions visible
    }

    /**
     * v0.7.2: Finds the first autocomplete suggestion on YouTube's
     * search panel. Suggestions are tappable rows below the search
     * bar, each containing text + a magnifying glass icon.
     *
     * Returns the AccessibilityNodeInfo of the suggestion row, or null.
     */
    private fun findFirstAutocompleteSuggestion(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isClickable) {
                val texts = mutableListOf<String>()
                collectTexts(node, texts)
                val b = android.graphics.Rect()
                node.getBoundsInScreen(b)
                // A suggestion row: below the search bar, reasonable height,
                // has text content, not the mic/voice button
                val isBelowSearchBar = b.top > 200
                val isReasonableHeight = b.height() in 80..300
                val hasText = texts.isNotEmpty()
                val isNotVoiceMic = texts.none {
                    it.lowercase().contains("voice") ||
                    it.lowercase().contains("mic") ||
                    it.lowercase().contains("speech")
                }
                if (isBelowSearchBar && isReasonableHeight && hasText && isNotVoiceMic) {
                    // Verify this is actually a suggestion by checking it's
                    // in the upper half of the screen (suggestions are right
                    // below the search bar)
                    if (b.top < resources.displayMetrics.heightPixels / 2) {
                        return node
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    /**
     * v0.7.1: Fallback for YouTube search submit. Looks for a search
     * icon in the top 25% of the screen (the top bar) and taps it.
     * On YouTube, the same magnifying glass icon that opens the
     * search panel also submits the search when tapped again.
     *
     * Excludes anything with "voice" or "mic" in the label.
     */
    fun tapSubmitIconInTopBar(): String {
        val root = rootInActiveWindow ?: return "ERROR: no active window"
        val screenHeight = resources.displayMetrics.heightPixels
        val topBarThreshold = screenHeight / 4  // top 25% of screen

        val matches = findAllMatchingNodes(root, "Search")
        val topBarClickable = matches.filter { m ->
            (m.node.isClickable || m.cls == "ImageButton" || m.cls == "ImageView") &&
            m.bounds.top < topBarThreshold &&
            m.bounds.width() < 300 &&  // icon-sized, not a full button
            m.bounds.height() < 300 &&
            !m.label.lowercase().contains("voice") &&
            !m.label.lowercase().contains("mic") &&
            !m.label.lowercase().contains("speech")
        }
        if (topBarClickable.isEmpty()) {
            return "ERROR: no search icon found in the top bar"
        }
        val chosen = topBarClickable.sortedWith(
            compareBy(
                { if (it.exact) 0 else 1 },
                { it.bounds.width() }  // smaller = more icon-like
            )
        ).first()
        val b = chosen.bounds
        val ok = dispatchTap(b.exactCenterX(), b.exactCenterY())
        return if (ok) "OK tapped search icon at top bar (${b.exactCenterX().toInt()},${b.exactCenterY().toInt()})"
        else "ERROR: tap gesture failed at top bar search icon"
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
    // v0.7: YouTube result parser
    // ----------------------------------------------------------------

    /**
     * v0.7: Parses the current screen into a list of YouTube search
     * results. Walks the accessibility tree looking for clickable
     * containers that look like video results (have ≥2 text children,
     * at least one containing "views" or "ago" or "subscribers").
     *
     * Returns up to 10 results. Each result includes the container's
     * screen bounds so youtube_play can tap by exact coordinates.
     *
     * Returns empty list if no results found (e.g. still loading,
     * YouTube redesigned, or not on search results page).
     */
    fun parseYouTubeResults(): List<com.kaiser.aiagent.scripts.YouTubeResult> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<com.kaiser.aiagent.scripts.YouTubeResult>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)

        while (queue.isNotEmpty() && results.size < 10) {
            val node = queue.removeFirst()

            // Collect all text descendants of this node.
            val texts = mutableListOf<String>()
            collectTexts(node, texts)

            // Heuristic: is this a search result container?
            val isClickable = node.isClickable
            val hasTexts = texts.size >= 2
            val hasMeta = texts.any { t ->
                t.contains("views", ignoreCase = true) ||
                t.contains("ago", ignoreCase = true) ||
                t.contains("subscribers", ignoreCase = true) ||
                t.contains("watched", ignoreCase = true)
            }
            // Filter out tiny nodes (icons, buttons, etc.)
            val b = android.graphics.Rect()
            node.getBoundsInScreen(b)
            val reasonableSize = b.width() > 300 && b.height() > 100

            if (isClickable && hasTexts && hasMeta && reasonableSize) {
                val result = parseResultContainer(node, texts, results.size, b)
                if (result != null) {
                    results.add(result)
                }
            }

            // Walk children regardless
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return results
    }

    /** Recursively collects all non-blank text from a node and its descendants. */
    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val t = node.text?.toString()?.takeIf { it.isNotBlank() }
        if (t != null) out.add(t)
        val d = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (d != null && d != t) out.add(d)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, out) }
        }
    }

    /**
     * Parses a single result container into a [YouTubeResult].
     * Heuristics:
     *  - Title = first text that doesn't contain meta keywords
     *  - Meta = text containing "views" or "ago"
     *  - Channel = remaining non-meta text
     */
    private fun parseResultContainer(
        node: AccessibilityNodeInfo,
        texts: List<String>,
        index: Int,
        bounds: android.graphics.Rect
    ): com.kaiser.aiagent.scripts.YouTubeResult? {
        val metaKeywords = listOf("views", "ago", "subscribers", "watched", "•")
        var title = ""
        var meta = ""
        var channel = ""

        for (t in texts) {
            val isMeta = metaKeywords.any { kw -> t.contains(kw, ignoreCase = true) }
            if (isMeta && meta.isEmpty()) {
                meta = t
            } else if (title.isEmpty()) {
                title = t
            } else if (channel.isEmpty()) {
                channel = t
            }
        }

        if (title.isEmpty()) return null

        // Parse views and uploaded from meta.
        // Typical: "Cocomelon • 80M views • 1 year ago"
        // or: "80M views • 1 year ago"  (channel separate)
        val views = extractPattern(meta, Regex("(\\d[\\d.]*[KMB]?\\s*views)", RegexOption.IGNORE_CASE)) ?: ""
        val uploaded = extractPattern(meta, Regex("(\\d+\\s+(?:second|minute|hour|day|week|month|year)s?\\s+ago)", RegexOption.IGNORE_CASE)) ?: ""

        // Channel: if meta starts with a name before •, extract it
        if (channel.isEmpty()) {
            val parts = meta.split("•").map { it.trim() }.filter { it.isNotEmpty() }
            // Filter out views/ago parts
            channel = parts.firstOrNull { p ->
                !p.contains("views", ignoreCase = true) &&
                !p.contains("ago", ignoreCase = true) &&
                !p.contains("subscribers", ignoreCase = true)
            } ?: ""
        }

        val isLive = title.contains("LIVE", ignoreCase = true) ||
            meta.contains("LIVE", ignoreCase = true) ||
            texts.any { it.contains("LIVE", ignoreCase = true) }
        val isPlaylist = texts.any {
            it.contains("playlist", ignoreCase = true) ||
            it.matches(Regex(".*\\d+\\s+videos.*", RegexOption.IGNORE_CASE))
        }

        return com.kaiser.aiagent.scripts.YouTubeResult(
            index = index,
            title = title.take(100),
            channel = channel.take(60),
            views = views.trim(),
            uploaded = uploaded.trim(),
            rawMeta = meta.take(120),
            bounds = bounds,
            isLive = isLive,
            isPlaylist = isPlaylist
        )
    }

    private fun extractPattern(text: String, regex: Regex): String? {
        val match = regex.find(text)
        return match?.value
    }

    // ----------------------------------------------------------------
    // v0.7: Screen polling (for slow internet)
    // ----------------------------------------------------------------

    /**
     * v0.7: Polls the screen until [condition] returns true, or until
     * [timeoutMs] elapses. Used by scripts to wait for network-dependent
     * UI changes (results loading, video page opening) instead of fixed
     * sleeps.
     *
     * Returns true if the condition was met, false if timed out.
     */
    fun pollForCondition(
        timeoutMs: Long,
        pollIntervalMs: Long = 500,
        condition: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            try { Thread.sleep(pollIntervalMs) } catch (e: InterruptedException) { return false }
        }
        return false
    }

    /** Returns the package name of the current active window, or null. */
    fun activeWindowPackage(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    /** Returns the count of interactive elements on screen (for detecting loaded content). */
    fun interactiveElementCount(): Int {
        val root = rootInActiveWindow ?: return 0
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && count < 500) {
            val node = queue.removeFirst()
            if (node.isClickable || node.isFocusable) count++
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return count
    }

    /**
     * v0.7: Public wrapper for dispatchGesture. The parent class's
     * dispatchGesture is protected, but scripts (YouTubePlayTool) need
     * to tap by exact coordinates. This exposes it safely.
     */
    fun dispatchGesturePublic(gesture: android.accessibilityservice.GestureDescription): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dispatchGesture(gesture, null, null)
        } else false
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    /**
     * v0.6.3: Walks the tree and collects EVERY node whose text or
     * content description matches [text] (case-insensitive substring,
     * plus a flag for exact equality). Used by tapText for smart
     * disambiguation.
     */
    private fun findAllMatchingNodes(root: AccessibilityNodeInfo, text: String): List<Match> {
        val lower = text.lowercase()
        val out = mutableListOf<Match>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val t = node.text?.toString()
            val d = node.contentDescription?.toString()
            val label = (t ?: d ?: "")
            if (label.isBlank()) {
                // No label on this node — still walk children.
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
                continue
            }
            val isExact = label.equals(text, ignoreCase = true)
            val isPartial = label.lowercase().contains(lower)
            if (isExact || isPartial) {
                val b = android.graphics.Rect()
                node.getBoundsInScreen(b)
                out.add(
                    Match(
                        node = node,
                        label = label,
                        cls = node.className?.toString()?.substringAfterLast('.') ?: "",
                        bounds = b,
                        exact = isExact
                    )
                )
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return out
    }

    /** v0.6.3 internal: a single matching node + metadata for ranking. */
    private data class Match(
        val node: AccessibilityNodeInfo,
        val label: String,
        val cls: String,
        val bounds: android.graphics.Rect,
        val exact: Boolean
    )

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
