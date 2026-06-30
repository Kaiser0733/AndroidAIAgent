package com.kaiser.aiagent.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.kaiser.aiagent.domain.agent.AgentState
import com.kaiser.aiagent.domain.agent.AgentRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import timber.log.Timber

/**
 * v0.6.2: A floating overlay chat window that stays on top of other
 * apps so the user can keep chatting with the agent while the agent
 * drives another app (e.g. YouTube).
 *
 * Why a foreground service + WindowManager overlay instead of PiP:
 *   - PiP requires the source Activity to be in the resumed state and
 *     has tight aspect-ratio constraints. We tried PiP in v0.6.1 and
 *     it silently failed because the activity was paused by the time
 *     enterPictureInPictureMode was called from a tool.
 *   - A WindowManager overlay (TYPE_APPLICATION_OVERLAY) is the
 *     standard pattern for AI assistants / bubbles. It survives the
 *     host activity being backgrounded and can be drawn over any app.
 *
 * The window shows:
 *   - a header bar with the title and a Close button
 *   - a status line that mirrors AgentRuntime.state
 *   - the latest assistant message
 *   - an input field + Send button (disabled in v0.6.2 — for now,
 *     the user returns to the main app to send. v0.7 will add inline
 *     input via Compose-in-overlay.)
 *
 * The window is draggable — the user can long-press the header and
 * drag it to any edge of the screen.
 *
 * The window auto-shows the latest assistant message by collecting
 * AgentRuntime.state.streamingText + lastToolResult.
 */
class FloatingChatService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var statusText: TextView? = null
    private var messageText: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        scope.cancel()
    }

    // ----------------------------------------------------------------
    // Foreground notification — required on Android 8+ to keep a
    // service with an overlay window alive while the app is in the
    // background.
    // ----------------------------------------------------------------

    private fun startForeground() {
        val channelId = "ai_agent_floating"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "AI Agent floating window",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                )
            }
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AI Agent")
            .setContentText("Floating chat is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ----------------------------------------------------------------
    // Overlay window
    // ----------------------------------------------------------------

    private fun showOverlay() {
        if (rootView != null) return // already showing
        if (!canDrawOverlays()) {
            Timber.w("Cannot show overlay — SYSTEM_ALERT_WINDOW not granted")
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
            ).toInt()
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0202020.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "AI Agent"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = Button(this).apply {
            text = "×"
            setBackgroundColor(0x00000000)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setOnClickListener { hideOverlay(); stopSelf() }
        }
        header.addView(title)
        header.addView(closeBtn)
        container.addView(header)

        val status = TextView(this).apply {
            text = "Idle"
            setTextColor(0xFFB0B0B0.toInt())
            textSize = 11f
            setPadding(0, dp(4), 0, dp(4))
        }
        statusText = status
        container.addView(status)

        val msg = TextView(this).apply {
            text = "Listening… tap a button below to ask the agent."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
            maxLines = 8
            setPadding(0, dp(4), 0, dp(4))
        }
        messageText = msg
        container.addView(msg)

        val closeAllBtn = Button(this).apply {
            text = "Close overlay"
            setBackgroundColor(0xFF37474F.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setOnClickListener {
                hideOverlay()
                stopSelf()
            }
        }
        container.addView(closeAllBtn)

        // Dragging: long-press on the header and drag the whole window.
        attachDragHandler(header, container)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
            width = dp(280)
        }

        try {
            wm.addView(container, params)
            rootView = container
        } catch (t: Throwable) {
            Timber.e(t, "Failed to add overlay view")
            return
        }

        // Subscribe to AgentRuntime state so the floating window
        // reflects streaming text and tool status in real time.
        stateJob?.cancel()
        stateJob = scope.launch {
            try {
                val runtime: AgentRuntime = GlobalContext.get().get()
                runtime.state.collectLatest { s -> renderState(s) }
            } catch (t: Throwable) {
                Timber.w(t, "Floating overlay could not observe AgentRuntime")
            }
        }
    }

    private fun hideOverlay() {
        val v = rootView
        if (v != null) {
            try { windowManager?.removeView(v) } catch (_: Throwable) {}
        }
        rootView = null
        statusText = null
        messageText = null
        stateJob?.cancel()
        stateJob = null
    }

    private fun renderState(s: AgentState) {
        val status = statusText
        val msg = messageText
        if (status == null || msg == null) return
        status.text = when {
            s.busy && s.streamingText.isBlank() && s.lastToolCall.isNullOrBlank() -> "Thinking…"
            s.busy && !s.lastToolCall.isNullOrBlank() -> "Running: ${s.lastToolCall}"
            s.busy && s.streamingText.isNotBlank() -> "Streaming…"
            !s.lastError.isNullOrBlank() -> "Error: ${s.lastError?.take(60)}"
            else -> "Idle"
        }
        // Prefer streaming text; fall back to last tool result; then idle.
        val toolData = s.lastToolResult?.data?.takeIf { it.isNotBlank() }
        msg.text = when {
            s.streamingText.isNotBlank() -> s.streamingText.take(600)
            toolData != null -> "tool ${s.lastToolCall}: ${toolData.take(300)}"
            s.lastError != null -> "⚠ ${s.lastError}"
            else -> "Listening… return to the AI Agent app to send a new message."
        }
    }

    // ----------------------------------------------------------------
    // Dragging
    // ----------------------------------------------------------------

    private fun attachDragHandler(header: View, root: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragging = false
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = (root.layoutParams as WindowManager.LayoutParams).x
                    initialY = (root.layoutParams as WindowManager.LayoutParams).y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!dragging && (dx * dx + dy * dy) > 25) dragging = true
                    if (dragging) {
                        val lp = root.layoutParams as WindowManager.LayoutParams
                        lp.x = initialX + dx.toInt()
                        lp.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(root, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasDrag = dragging
                    dragging = false
                    wasDrag
                }
                else -> false
            }
        }
    }

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this)
        else true

    companion object {
        private const val NOTIF_ID = 4242
        private const val ACTION_SHOW = "com.kaiser.aiagent.SHOW_FLOATING"
        private const val ACTION_HIDE = "com.kaiser.aiagent.HIDE_FLOATING"

        /** True if the overlay permission has been granted. */
        fun canDrawOverlays(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Settings.canDrawOverlays(context)
            else true

        /** Opens the system "Draw over other apps" settings page. */
        fun openOverlaySettings(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        }

        /**
         * Starts the floating overlay service if the overlay permission
         * is granted. No-op (returns false) otherwise.
         */
        fun startIfPermitted(context: Context): Boolean {
            if (!canDrawOverlays(context)) return false
            val intent = Intent(context, FloatingChatService::class.java).apply {
                action = ACTION_SHOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        /** Stops the floating overlay service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingChatService::class.java))
        }
    }
}
