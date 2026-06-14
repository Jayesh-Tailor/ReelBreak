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
    private val DOOM_THRESHOLD = 15f // Re-adjusted for Reels-only detection

    // --- State: Foreground Session Tracking ---
    private var instagramStartTime: Long = 0
    private var lastGentleWarningTime: Long = 0
    private val GENTLE_WARNING_MS = 20 * 60 * 1000L

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
            if (isInWarningMode || isCooldownActive()) {
                handler.postDelayed(this, 5000)
                return
            }

            if (doomScore > 0) {
                // Decay logic: 1 point every 10 seconds of inactivity
                // If not even in Instagram, decay faster
                val decayAmount = if (lastLoggedPackage?.contains("instagram") == true) 1f else 2f
                doomScore = (doomScore - decayAmount).coerceAtLeast(0f)
                Log.d(TAG, "SCORE_DECAY: $doomScore (Current App: $lastLoggedPackage)")
            }
            handler.postDelayed(this, 10000)
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

        // Start score decay to allow for short Reel sessions
        handler.removeCallbacks(pauseDecayRunnable)
        handler.post(pauseDecayRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventSafe = event ?: return
        val packageName = eventSafe.packageName?.toString() ?: "null"
        val eventType = eventSafe.eventType

        // Log package changes and manage session timers
        if (packageName != lastLoggedPackage && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.e(TAG, "FOREGROUND_CHANGE: $packageName")
            
            if (packageName.contains("instagram", ignoreCase = true)) {
                // Entered Instagram
                if (instagramStartTime == 0L) instagramStartTime = System.currentTimeMillis()
            } else if (lastLoggedPackage?.contains("instagram", ignoreCase = true) == true) {
                // Left Instagram
                Log.d(TAG, "Left Instagram - Resetting session and score")
                doomScore = 0f
                instagramStartTime = 0
                lastGentleWarningTime = 0
            }
            
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

        // 3. MONITORING STATE: Specifically target Instagram interactions
        if (packageName.contains("instagram", ignoreCase = true)) {
            // Foreground timer check for gentle warning
            if (instagramStartTime == 0L) instagramStartTime = System.currentTimeMillis()
            val sessionDuration = System.currentTimeMillis() - instagramStartTime
            if (sessionDuration > GENTLE_WARNING_MS && System.currentTimeMillis() - lastGentleWarningTime > 5 * 60 * 1000) {
                Toast.makeText(this, "Gentle Reminder: You've been on Instagram for 20 minutes.", Toast.LENGTH_LONG).show()
                lastGentleWarningTime = System.currentTimeMillis()
            }

            val isComments = isCommentsViewActive(eventSafe)
            
            // Focus primarily on scroll events for "active" scrolling
            if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                if (isComments) {
                    Log.d(TAG, "SCROLL: User is in Comments. Reducing doom score.")
                    doomScore = (doomScore - 1.0f).coerceAtLeast(0f)
                    return
                }

                val isReels = isReelsViewActive()
                if (isReels) {
                    Log.d(TAG, "SCROLL: Reel interaction detected.")
                    processInteraction(isReel = true)
                } else {
                    Log.d(TAG, "SCROLL: Feed interaction. Reducing doom score.")
                    // Feed scroll decays the doom score to reward escaping Reels
                    doomScore = (doomScore - 0.5f).coerceAtLeast(0f)
                }
            }
            
            // On content change, check for background decay if in comments
            if ((eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || 
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) && isComments) {
                doomScore = (doomScore - 0.5f).coerceAtLeast(0f)
            }
        }
    }

    /**
     * Detects if the user is currently interacting with the comments section.
     */
    private fun isCommentsViewActive(event: AccessibilityEvent?): Boolean {
        // 1. Check the event source (the view being scrolled)
        val source = event?.source
        if (source != null) {
            val resId = source.viewIdResourceName ?: ""
            
            // Instagram often uses specific IDs for the comment recycler/list
            if (resId.contains("comment", ignoreCase = true) || 
                resId.contains("fixed_tabbar", ignoreCase = true) ||
                resId.contains("bottom_sheet", ignoreCase = true)) {
                return true
            }
        }

        // 2. Check the global UI state for comment markers
        val rootNode = rootInActiveWindow ?: return false
        
        val commentMarkers = listOf(
            "com.instagram.android:id/layout_comment_thread_edittext",
            "com.instagram.android:id/comment_list_container",
            "com.instagram.android:id/comment_thread_recycler_view",
            "com.instagram.android:id/comments_sheet_container",
            "com.instagram.android:id/comment_composer_edittext",
            "com.instagram.android:id/bottom_sheet_container",
            "com.instagram.android:id/bottom_sheet_content_view",
            "com.instagram.android:id/text_box_container" // Comment input box
        )
        for (id in commentMarkers) {
            if (rootNode.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) return true
        }

        // 3. Check for text hints that indicate a comment section is open
        val commentTexts = listOf("Comments", "Add a comment...", "Add a comment…", "Reply to")
        for (text in commentTexts) {
            if (rootNode.findAccessibilityNodeInfosByText(text).isNotEmpty()) return true
        }

        // 4. Check for active keyboard/input focus (user is typing a comment)
        val focusedNode = rootNode.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode?.className?.contains("EditText", ignoreCase = true) == true) return true

        return false
    }

    private fun isReelsViewActive(): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        // 1. If a bottom sheet (like comments) is visible, we are NOT doom-scrolling reels
        val bottomSheets = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/bottom_sheet_container")
        if (bottomSheets.isNotEmpty()) return false
        
        // 2. FEED DETECTION: Look for Home Feed markers to avoid false positives
        val feedMarkers = listOf("For you", "Following", "Instagram")
        for (marker in feedMarkers) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(marker)
            for (node in nodes) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                // Home feed headers are always at the top
                if (bounds.top < 300) return false
            }
        }

        // 3. Check for known Reels-specific container IDs
        val reelsIds = listOf(
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/clips_viewer_video_container",
            "com.instagram.android:id/reel_viewer_container",
            "com.instagram.android:id/reels_video_container"
            // Removed generic "video_container" and "viewer_container" as they appear in Feed
        )
        for (id in reelsIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) return true
        }
        
        // 4. Check for "Reels" text header
        val textNodes = rootNode.findAccessibilityNodeInfosByText("Reels")
        if (!textNodes.isNullOrEmpty()) {
            for (node in textNodes) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                // Reels header is at the extreme top
                if (bounds.top < 250) return true
            }
        }

        return false
    }

    private fun processInteraction(isReel: Boolean) {
        if (!isReel) return // ONLY Reels scrolling triggers the hard block

        val currentTime = System.currentTimeMillis()

        if (lastScrollTime == 0L) {
            lastScrollTime = currentTime
            return
        }

        val interval = currentTime - lastScrollTime
        if (interval < 300) return // Debounce noise

        lastScrollTime = currentTime
        
        // Every valid Reel interaction increments the doom score.
        doomScore += 1.0f
        
        Log.e(TAG, "REEL_SCORE_UPDATE: $doomScore / THRESHOLD: $DOOM_THRESHOLD")

        if (doomScore >= DOOM_THRESHOLD) {
            Log.e(TAG, "THRESHOLD_REACHED!")
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
