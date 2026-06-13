package com.example.reelbreak

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ReelWatcherService implements a state-based architecture to detect doom-scrolling,
 * intervene with a warning, and enforce a cooldown period for Instagram.
 */
@SuppressLint("AccessibilityPolicy")
class ReelWatcherService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    // --- State: Monitoring (ScrollSessionManager) ---
    private var doomScore = 0f
    private var lastScrollTime: Long = 0
    private var lastInterval: Long = 0

    // --- State: Intervention (ThresholdManager) ---
    private var isInWarningMode = false
    private var countdownValue = 3
    private val DOOM_THRESHOLD = 0f // Trigger on FIRST Instagram event for testing

    // --- State: Cooldown (CooldownManager) ---
    private val PREFS_NAME = "ReelBreakPrefs"
    private val KEY_COOLDOWN_END = "cooldown_end_time"
    private val BLOCK_DURATION_MS = TimeUnit.MINUTES.toMillis(5)

    private val TAG = "ReelWatcherService"
    private var lastLoggedPackage: String? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            Log.e(TAG, "HEARTBEAT: Service is ALIVE. DoomScore: $doomScore, Cooldown: ${getRemainingCooldownMs()}ms")
            handler.postDelayed(this, 10000)
        }
    }

    // --- Runnables ---

    private val countdownRunnable = object : Runnable {
        override fun run() {
            Log.e(TAG, "Countdown: $countdownValue")
            if (countdownValue > 0) {
                updateWarningUI(countdownValue)
                countdownValue--
                handler.postDelayed(this, 1000)
            } else {
                Log.e(TAG, "Countdown finished, starting cooldown")
                startCooldown()
            }
        }
    }

    private val timerUpdateRunnable = object : Runnable {
        override fun run() {
            val remaining = getRemainingCooldownMs()
            Log.e(TAG, "Cooldown timer update: $remaining ms remaining")
            if (remaining > 0) {
                updateCooldownUI(remaining)
                handler.postDelayed(this, 1000)
            } else {
                Log.e(TAG, "COOLDOWN_ENDED")
                resetDetectionState()
                removeOverlay()
            }
        }
    }

    private val pauseDecayRunnable = object : Runnable {
        override fun run() {
            if (doomScore > 0 && !isInWarningMode && !isCooldownActive()) {
                doomScore = (doomScore - 1f).coerceAtLeast(0f)
                Log.d(TAG, "SCORE_UPDATED: $doomScore (Reason: 15s Pause Decay)")
                handler.postDelayed(this, 15000)
            }
        }
    }

    // --- Service Lifecycle ---

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.e(TAG, "!!! ReelWatcherService is CONNECTED and RUNNING !!!")
        Toast.makeText(this, "ReelBreak Service Connected", Toast.LENGTH_LONG).show()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        this.serviceInfo = info
        Log.e(TAG, "Service Info Configured. EventMask: ${info.eventTypes}")
        
        handler.removeCallbacks(heartbeatRunnable)
        handler.post(heartbeatRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventSafe = event ?: return
        val packageName = eventSafe.packageName?.toString() ?: "null"
        val eventType = eventSafe.eventType

        // Log package changes to verify detection
        if (packageName != lastLoggedPackage && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.e(TAG, "FOREGROUND_CHANGE: $packageName")
            lastLoggedPackage = packageName
        }

        // 1. COOLDOWN STATE
        val remainingCooldown = getRemainingCooldownMs()
        if (remainingCooldown > 0) {
            if (packageName.contains("instagram", ignoreCase = true)) {
                Log.e(TAG, "BLOCKING INSTAGRAM: Cooldown active ($remainingCooldown ms)")
                performGlobalAction(GLOBAL_ACTION_HOME)
                showBlockingOverlay()
            }
            return
        }

        // 2. INTERVENTION STATE
        if (isInWarningMode) return

        // 3. MONITORING STATE: Any Instagram event triggers warning for now
        if (packageName.contains("instagram", ignoreCase = true)) {
            Log.e(TAG, "INSTAGRAM DETECTED! Type=${AccessibilityEvent.eventTypeToString(eventType)}")
            startWarningSequence()
        }
    }

    private fun processInteraction() {
        val currentTime = System.currentTimeMillis()

        if (lastScrollTime == 0L) {
            Log.i(TAG, "Session Started")
            lastScrollTime = currentTime
            return
        }

        val interval = currentTime - lastScrollTime
        if (interval < 300) return // Debounce noise

        lastScrollTime = currentTime
        
        // Every valid interaction increments the score
        doomScore += 1f
        Log.i(TAG, "SCORE_INCREASED: $doomScore (Interval: ${interval}ms)")

        // Threshold check
        if (doomScore >= DOOM_THRESHOLD) {
            Log.i(TAG, "THRESHOLD_REACHED: $doomScore")
            startWarningSequence()
        }
    }

    private fun startWarningSequence() {
        if (isInWarningMode) return
        Log.e(TAG, "startWarningSequence() - Showing UI")
        
        isInWarningMode = true
        countdownValue = 3

        handler.post {
            Toast.makeText(this, "REEL BREAK STARTING", Toast.LENGTH_SHORT).show()
            showOverlayBase()
            updateWarningUI(countdownValue)
            handler.post(countdownRunnable)
        }
    }

    private fun updateWarningUI(seconds: Int) {
        overlayView?.apply {
            findViewById<TextView>(R.id.title_text)?.text = "REEL BREAK"
            findViewById<TextView>(R.id.desc_text)?.text = "Take a break.\nInstagram will close in $seconds..."
            findViewById<TextView>(R.id.timer_text)?.visibility = View.GONE
            findViewById<View>(R.id.btn_close_overlay)?.visibility = View.GONE
        }
    }

    // --- Cooldown Logic (CooldownManager) ---

    private fun startCooldown() {
        Log.e(TAG, "CLOSING_INSTAGRAM - START")
        isInWarningMode = false

        val endTime = System.currentTimeMillis() + BLOCK_DURATION_MS
        saveCooldownEndTime(endTime)

        Log.e(TAG, "COOLDOWN_STARTED - Setting Home Action")
        val success = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.e(TAG, "performGlobalAction(HOME) success: $success")
        showBlockingOverlay()
    }

    private fun showBlockingOverlay() {
        val remaining = getRemainingCooldownMs()
        if (remaining <= 0) return

        showOverlayBase()
        updateCooldownUI(remaining)

        handler.removeCallbacks(timerUpdateRunnable)
        handler.post(timerUpdateRunnable)
    }

    private fun updateCooldownUI(remainingMs: Long) {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60
        val timeStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        overlayView?.apply {
            findViewById<TextView>(R.id.title_text)?.text = "INSTAGRAM LOCKED"
            findViewById<TextView>(R.id.desc_text)?.text = "Remaining break time:"

            val timerView = findViewById<TextView>(R.id.timer_text)
            timerView?.visibility = View.VISIBLE
            timerView?.text = timeStr

            val closeBtn = findViewById<View>(R.id.btn_close_overlay)
            closeBtn?.visibility = View.VISIBLE
            closeBtn?.setOnClickListener { removeOverlay() }
        }
    }

    private fun saveCooldownEndTime(time: Long) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(KEY_COOLDOWN_END, time)
        }
    }

    private fun getRemainingCooldownMs(): Long {
        val endTime = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_COOLDOWN_END, 0)
        return (endTime - System.currentTimeMillis()).coerceAtLeast(0)
    }

    private fun isCooldownActive(): Boolean = getRemainingCooldownMs() > 0

    private fun resetDetectionState() {
        doomScore = 0f
        lastScrollTime = 0
        lastInterval = 0
        saveCooldownEndTime(0)
    }

    // --- UI Helpers ---

    private fun showOverlayBase() {
        Log.e(TAG, "showOverlayBase() called. Current overlayView: $overlayView")
        if (overlayView != null) return

        if (windowManager == null) {
            Log.e(TAG, "WindowManager is NULL, attempting to recover")
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        try {
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_reel_blocker, null)
            overlayView?.setBackgroundColor(0xDD000000.toInt()) // Darker overlay for visibility
            
            windowManager?.addView(overlayView, params)
            Log.e(TAG, "SUCCESS: Overlay added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR adding overlay: ${e.message}")
            e.printStackTrace()
            overlayView = null
        }
    }

    private fun removeOverlay() {
        handler.removeCallbacks(countdownRunnable)
        handler.removeCallbacks(timerUpdateRunnable)
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {}
            overlayView = null
        }
        isInWarningMode = false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        handler.removeCallbacksAndMessages(null)
    }
}
