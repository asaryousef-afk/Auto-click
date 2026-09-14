package com.smarttouch.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.smarttouch.ai.MainActivity
import com.smarttouch.ai.R
import com.smarttouch.ai.ServiceActionReceiver
import com.smarttouch.ai.data.SettingsRepository
import com.smarttouch.ai.data.TouchSettings
import com.smarttouch.ai.detection.DetectionMode
import com.smarttouch.ai.detection.MotionDetector
import com.smarttouch.ai.detection.TouchIntervalCalculator
import com.smarttouch.ai.detection.VideoActivityTracker
import com.smarttouch.ai.state.ServiceState
import com.smarttouch.ai.state.TouchStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DebugSnapshot(
    val state: ServiceState = ServiceState.IDLE,
    val motionScore: Float = 0f,
    val threshold: Float = 0f,
    val videoActive: Boolean = false,
    val touchX: Float = -1f,
    val touchY: Float = -1f,
    val samplingIntervalMs: Long = 0L,
    val lastEvent: String = "-"
)

class TouchAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TouchAccessibilityService? = null
            private set

        const val CHANNEL_ID = "smart_touch_channel"
        const val NOTIF_ID = 7
        const val ACTION_START = "com.smarttouch.ai.action.START"
        const val ACTION_STOP = "com.smarttouch.ai.action.STOP"
        const val ACTION_PAUSE = "com.smarttouch.ai.action.PAUSE"
        const val ACTION_RESUME = "com.smarttouch.ai.action.RESUME"

        private const val GRID_SIZE = 24 // 24x24 luminance samples per frame - cheap and battery-light
        private const val TAP_VISUAL_SETTLE_MS = 400L // ignore frames captured just after our own tap,
        // so the tap's own visual ripple/feedback isn't mistaken for "video motion"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var detectionJob: Job? = null
    private var touchJob: Job? = null

    private lateinit var settingsRepository: SettingsRepository
    private var currentSettings: TouchSettings = TouchSettings()

    private val stateMachine = TouchStateMachine()
    private var motionDetector = MotionDetector()
    private var activityTracker = VideoActivityTracker(800L, 1500L)

    private val _debugSnapshot = MutableStateFlow(DebugSnapshot())
    val debugSnapshot: StateFlow<DebugSnapshot> = _debugSnapshot.asStateFlow()

    private var windowManager: WindowManager? = null
    private val audioManager: android.media.AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    private var detectionPointView: View? = null
    private var detectionPointParams: WindowManager.LayoutParams? = null
    private var overlayVisible = false

    private var debugBadgeView: View? = null
    private var debugBadgeParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastTapAtMs = 0L

    // Landscape auto-safe-position: temporarily overrides the tap point while in
    // landscape (e.g. fullscreen video), without ever touching the user's saved
    // portrait position, which is restored automatically on rotating back.
    private var isLandscapeSafeModeActive = false
    private var landscapeOverrideX: Float? = null
    private var landscapeOverrideY: Float? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepository = SettingsRepository(applicationContext)
        createNotificationChannel()

        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                currentSettings = settings
                motionDetector.setSensitivity(settings.sensitivity, settings.customThreshold)
                activityTracker.updateTimings(settings.confirmationTimeMs, settings.noMotionTimeoutMs)
                mainHandler.post {
                    applyOverlayVisuals(settings)
                    if (!isLandscapeSafeModeActive) {
                        repositionDotFromSettings(settings)
                    }
                }
            }
        }

        updateNotification("Ready", videoActive = false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used - detection relies on periodic screenshots */ }

    override fun onInterrupt() { /* no-op */ }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscapeNow = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (isLandscapeNow && !isLandscapeSafeModeActive) {
            enterLandscapeSafeMode()
        } else if (!isLandscapeNow && isLandscapeSafeModeActive) {
            exitLandscapeSafeMode()
        }
    }

    /**
     * Moves the effective tap point to a safe corner (calculated from the current
     * screen size, not a hardcoded coordinate) whenever the device rotates to
     * landscape - e.g. fullscreen video - so it never lands on the video's
     * play/pause, seek bar, or other on-screen controls. The user's saved portrait
     * tap position is never overwritten; it's restored automatically on rotating
     * back to portrait.
     */
    private fun enterLandscapeSafeMode() {
        isLandscapeSafeModeActive = true

        val (safeX, safeY) = computeLandscapeSafePoint()

        landscapeOverrideX = safeX
        landscapeOverrideY = safeY

        mainHandler.post {
            val view = floatingView
            val params = floatingParams
            if (view != null && params != null) {
                params.x = safeX.toInt()
                params.y = safeY.toInt()
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
        }
    }

    /**
     * Reads the device's real system-gesture inset (the edge-swipe zone reserved
     * for Back/Home/notifications) so we know exactly how far in from the corner a
     * dispatched gesture actually reaches the app instead of being swallowed by the
     * OS. Returns null on API < 30 or if it can't be read, in which case the caller
     * falls back to a fixed margin.
     */
    private fun currentSystemGestureInsets(): Rect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val wm = windowManager ?: return null
            val metrics = wm.currentWindowMetrics
            val gestureInsets = metrics.windowInsets.getInsets(
                android.view.WindowInsets.Type.systemGestures()
            )
            Rect(gestureInsets.left, gestureInsets.top, gestureInsets.right, gestureInsets.bottom)
        } catch (e: Exception) {
            null
        }
    }

    /** Real screen height in pixels for the current orientation, used to anchor the
     * safe tap point to the bottom edge instead of a stale/rotated value. */
    private fun currentScreenHeightPx(): Float {
        return try {
            val wm = windowManager
            if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds.height().toFloat()
            } else {
                resources.displayMetrics.heightPixels.toFloat()
            }
        } catch (e: Exception) {
            resources.displayMetrics.heightPixels.toFloat()
        }
    }

    /** Same as currentScreenHeightPx() but for width - see that function for why
     * this reads from windowManager.currentWindowMetrics rather than
     * resources.displayMetrics. */
    private fun currentScreenWidthPx(): Float {
        return try {
            val wm = windowManager
            if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds.width().toFloat()
            } else {
                resources.displayMetrics.widthPixels.toFloat()
            }
        } catch (e: Exception) {
            resources.displayMetrics.widthPixels.toFloat()
        }
    }


    private fun exitLandscapeSafeMode() {
        isLandscapeSafeModeActive = false
        landscapeOverrideX = null
        landscapeOverrideY = null

        mainHandler.post {
            val view = floatingView
            val params = floatingParams
            val settings = currentSettings
            if (view != null && params != null && settings.hasTouchPosition) {
                params.x = settings.touchX.toInt()
                params.y = settings.touchY.toInt()
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
        }
    }

    override fun onDestroy() {
        instance = null
        stopEngine()
        removeFloatingView()
        removeDebugBadge()
        removeDetectionPoint()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------- Public control API (called from MainActivity / notification / shortcuts) ----------

    fun handleAction(action: String) {
        when (action) {
            ACTION_START -> startEngine()
            ACTION_STOP -> stopEngine()
            ACTION_PAUSE -> pauseEngine()
            ACTION_RESUME -> resumeEngine()
        }
    }

    fun startEngine() {
        if (!currentSettings.hasTouchPosition) {
            updateNotification("Set a touch position first", videoActive = false)
            return
        }
        stateMachine.start()
        motionDetector.reset()
        activityTracker.reset()
        updateNotification("Detecting video...", videoActive = false)
        startDetectionLoop()
    }

    fun stopEngine() {
        stateMachine.stop()
        detectionJob?.cancel()
        touchJob?.cancel()
        detectionJob = null
        touchJob = null
        updateNotification("Stopped", videoActive = false)
        pushDebugSnapshot(lastEvent = "Stopped")
    }

    fun pauseEngine() {
        stateMachine.pause()
        touchJob?.cancel()
        touchJob = null
        updateNotification("Paused", videoActive = false)
        pushDebugSnapshot(lastEvent = "Paused")
    }

    fun resumeEngine() {
        if (stateMachine.state == ServiceState.PAUSED) {
            stateMachine.resume()
            updateNotification("Detecting video...", videoActive = false)
            startDetectionLoop()
        }
    }

    fun showFloatingControl() {
        mainHandler.post { addFloatingViewIfNeeded() }
    }

    /** Performs exactly one tap at the current touch position (or overlay position),
     * regardless of engine/video state - used by the "Test tap" button so the user
     * gets immediate feedback while positioning the dot. */
    fun testSingleTap() {
        val point = floatingParams?.let { it.x.toFloat() to it.y.toFloat() } ?: safeTouchPoint()
        if (point == null) {
            updateNotification("Set a touch position first", videoActive = false)
            return
        }
        val path = Path().apply { moveTo(point.first, point.second) }
        val duration = currentSettings.touchDurationMs.coerceIn(1L, 2000L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun hideFloatingControl() {
        mainHandler.post { removeFloatingView() }
    }

    /** A tiny always-visible badge showing live motion score / state anywhere on
     * screen, so you can see what the detector is doing while using another app -
     * not just inside the Debug screen. */
    fun showLiveMotionOverlay() {
        mainHandler.post { addDebugBadgeIfNeeded() }
    }

    fun hideLiveMotionOverlay() {
        mainHandler.post { removeDebugBadge() }
    }

    fun growTouchDot() = adjustDotSize(+4f)
    fun shrinkTouchDot() = adjustDotSize(-2f)

    private fun adjustDotSize(deltaDp: Float) {
        val newSize = (currentSettings.dotSizeDp + deltaDp).coerceIn(3f, 90f)
        serviceScope.launch { settingsRepository.updateDotSize(newSize) }
        mainHandler.post { resizeFloatingView(newSize) }
    }

    private fun resizeFloatingView(sizeDp: Float) {
        val view = floatingView ?: return
        val params = floatingParams ?: return
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt().coerceAtLeast(2)
        params.width = sizePx
        params.height = sizePx
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    /** A second, independently-draggable point marking the CENTER of the region to
     * analyze for video motion - separate from the tap point, since the tap point is
     * often placed somewhere (like near the edge/nav area) where no video ever plays. */
    fun showDetectionPoint() {
        mainHandler.post { addDetectionPointIfNeeded() }
    }

    fun hideDetectionPoint() {
        mainHandler.post { removeDetectionPoint() }
    }

    // ---------- Detection loop ----------

    private fun startDetectionLoop() {
        detectionJob?.cancel()
        detectionJob = serviceScope.launch {
            while (stateMachine.state == ServiceState.VIDEO_DETECTING ||
                stateMachine.state == ServiceState.VIDEO_ACTIVE ||
                stateMachine.state == ServiceState.TOUCHING
            ) {
                if (!currentSettings.videoDetectionEnabled) {
                    // Video detection turned off in settings: behave as a plain interval
                    // clicker instead (still fully user-controlled via start/stop).
                    if (stateMachine.state != ServiceState.TOUCHING) {
                        stateMachine.onVideoActive()
                        stateMachine.onTouchingStarted()
                        startTouchLoop()
                    }
                    pushDebugSnapshot(motionScore = 0f, videoActive = true, lastEvent = "Detection disabled - always on")
                    delay(currentSettings.detectionIntervalMs)
                    continue
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && currentSettings.detectionMode != DetectionMode.AUDIO) {
                    pushDebugSnapshot(lastEvent = "Visual detection needs Android 11+ - try Audio mode instead")
                    delay(1000)
                    continue
                }

                val mode = currentSettings.detectionMode
                val audioActive = runCatching { audioManager.isMusicActive }.getOrDefault(false)

                var motionDetected = false
                var score = 0f
                var eventLabel = "Still"

                if (mode == DetectionMode.AUDIO) {
                    motionDetected = audioActive
                    eventLabel = if (audioActive) "Audio playing" else "No audio"
                } else {
                    val frame = captureLuminanceFrame()
                    val sinceLastTapMs = System.currentTimeMillis() - lastTapAtMs
                    val ignoreThisFrame = sinceLastTapMs in 0..TAP_VISUAL_SETTLE_MS

                    if (frame != null && !ignoreThisFrame) {
                        score = motionDetector.analyzeFrame(frame)
                        val visualMotion = motionDetector.isMotionSignificant(score)
                        motionDetected = if (mode == DetectionMode.EITHER) visualMotion || audioActive else visualMotion
                        eventLabel = when {
                            visualMotion && audioActive -> "Motion + audio"
                            visualMotion -> "Motion"
                            audioActive -> "Audio playing"
                            else -> "Still"
                        }
                    } else if (mode == DetectionMode.EITHER) {
                        // frame skipped (post-tap settle window) - audio can still gate on its own
                        motionDetected = audioActive
                        eventLabel = if (audioActive) "Audio playing" else "Still"
                    }
                }

                val active = activityTracker.onFrameAnalyzed(motionDetected, System.currentTimeMillis())

                pushDebugSnapshot(
                    motionScore = score,
                    videoActive = active,
                    lastEvent = eventLabel
                )

                if (active && stateMachine.state == ServiceState.VIDEO_DETECTING) {
                    stateMachine.onVideoActive()
                    stateMachine.onTouchingStarted()
                    updateNotification("Video active - touching", videoActive = true)
                    startTouchLoop()
                } else if (!active && (stateMachine.state == ServiceState.VIDEO_ACTIVE || stateMachine.state == ServiceState.TOUCHING)) {
                    stateMachine.onVideoInactive()
                    touchJob?.cancel()
                    touchJob = null
                    updateNotification("Detecting video...", videoActive = false)
                }

                delay(currentSettings.detectionIntervalMs)
            }
        }
    }

    private fun startTouchLoop() {
        touchJob?.cancel()
        touchJob = serviceScope.launch {
            while (stateMachine.state == ServiceState.TOUCHING) {
                dispatchConfiguredTap()
                val interval = TouchIntervalCalculator.resolve(currentSettings.intervalMs, currentSettings.customIntervalMs)
                delay(interval)
            }
        }
    }

    // ---------- Gesture dispatch ----------

    private fun dispatchConfiguredTap() {
        val point = safeTouchPoint() ?: return
        syncDotToPoint(point)
        val path = Path().apply { moveTo(point.first, point.second) }
        val duration = currentSettings.touchDurationMs.coerceIn(1L, 2000L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        lastTapAtMs = System.currentTimeMillis()
    }

    /**
     * Keeps the visible dot exactly in sync with the point each tap actually
     * lands on. safeTouchPoint() recomputes live on every tap (so it stays
     * correct if system-gesture insets change after rotation, e.g. YouTube
     * settling into immersive mode a moment after the phone rotates), but
     * enterLandscapeSafeMode() only placed the dot once at rotation time. That
     * gap let the dot visually freeze at a stale spot while real taps moved on
     * to a different, live-computed point. Using the same point for both here
     * makes that drift impossible - the dot always shows exactly where the
     * next tap will land.
     */
    private fun syncDotToPoint(point: Pair<Float, Float>) {
        val view = floatingView ?: return
        val params = floatingParams ?: return
        val x = point.first.toInt()
        val y = point.second.toInt()
        if (params.x == x && params.y == y) return
        mainHandler.post {
            params.x = x
            params.y = y
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    /**
     * Clamps the configured touch position to stay within the safe content area,
     * away from the system navigation bar / gesture strip, so Smart Touch AI never
     * accidentally triggers Back / Home / Recents.
     *
     * Checks the LIVE orientation on every call rather than trusting the cached
     * isLandscapeSafeModeActive flag - onConfigurationChanged can be delayed or
     * arrive out of order during a fast rotation (e.g. entering/exiting a YouTube
     * fullscreen video), which previously left taps stuck on the landscape corner
     * even after the phone was back in portrait. Computing it fresh every tap means
     * there's no stale state to get stuck in.
     */
    private fun safeTouchPoint(): Pair<Float, Float>? {
        val isLandscapeNow = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

        if (isLandscapeNow) {
            return computeLandscapeSafePoint()
        }

        val settings = currentSettings
        if (!settings.hasTouchPosition) return null

        val bounds = safeContentBounds() ?: return settings.touchX to settings.touchY
        val x = settings.touchX.coerceIn(bounds.left.toFloat(), bounds.right.toFloat())
        val y = settings.touchY.coerceIn(bounds.top.toFloat(), bounds.bottom.toFloat())
        return x to y
    }

    /**
     * The bottom-left safe corner used in landscape, computed fresh from the
     * current screen size and real system-gesture insets (not cached), so it's
     * always correct regardless of when/whether onConfigurationChanged fired.
     */
    private fun computeLandscapeSafePoint(): Pair<Float, Float> {
        val insets = currentSystemGestureInsets()
        val fallbackMarginPx = (24 * resources.displayMetrics.density)
        val screenHeightPx = currentScreenHeightPx()

        val safeX = (insets?.left?.toFloat() ?: fallbackMarginPx) + (4 * resources.displayMetrics.density)
        val bottomMargin = (insets?.bottom?.toFloat() ?: fallbackMarginPx) + (4 * resources.displayMetrics.density)
        val safeY = screenHeightPx - bottomMargin

        return safeX to safeY
    }

    private fun safeContentBounds(): Rect? {
        windowManager ?: return null
        val density = resources.displayMetrics.density
        val navBarMargin = (2 * density).toInt() // essentially edge-to-edge
        val widthPx = currentScreenWidthPx().toInt()
        val heightPx = currentScreenHeightPx().toInt()
        return Rect(
            navBarMargin,
            navBarMargin,
            widthPx - navBarMargin,
            heightPx - navBarMargin
        )
    }

    // ---------- Screenshot-based motion sampling (Android 11+) ----------

    private suspend fun captureLuminanceFrame(): IntArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bitmap = try {
                                val hb: HardwareBuffer = result.hardwareBuffer
                                val wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                                val software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                hb.close()
                                software
                            } catch (e: Exception) {
                                null
                            }
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(bitmap?.let { toLuminanceGrid(it) }))
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(null))
                            }
                        }
                    }
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun toLuminanceGrid(bitmap: Bitmap): IntArray {
        val grid = IntArray(GRID_SIZE * GRID_SIZE)
        val fullW = bitmap.width
        val fullH = bitmap.height

        // Sample only around the detection point if one is set, instead of the whole
        // screen - this way a tap point placed near the edge (where no video ever
        // plays) doesn't have to also serve as the motion-sampling area.
        val settings = currentSettings
        val regionPx = (settings.detectionRegionSizeDp * resources.displayMetrics.density).toInt()
        val left: Int
        val top: Int
        val w: Int
        val h: Int
        if (settings.hasDetectionPosition) {
            val cx = settings.detectionX.toInt()
            val cy = settings.detectionY.toInt()
            left = (cx - regionPx / 2).coerceIn(0, (fullW - 1).coerceAtLeast(0))
            top = (cy - regionPx / 2).coerceIn(0, (fullH - 1).coerceAtLeast(0))
            w = regionPx.coerceAtMost(fullW - left).coerceAtLeast(1)
            h = regionPx.coerceAtMost(fullH - top).coerceAtLeast(1)
        } else {
            left = 0
            top = 0
            w = fullW
            h = fullH
        }

        var index = 0
        for (gy in 0 until GRID_SIZE) {
            for (gx in 0 until GRID_SIZE) {
                val px = (left + gx * w / GRID_SIZE).coerceIn(0, fullW - 1)
                val py = (top + gy * h / GRID_SIZE).coerceIn(0, fullH - 1)
                val pixel = bitmap.getPixel(px, py)
                val luminance = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
                grid[index++] = luminance
            }
        }
        bitmap.recycle()
        return grid
    }

    private fun pushDebugSnapshot(
        motionScore: Float = _debugSnapshot.value.motionScore,
        videoActive: Boolean = _debugSnapshot.value.videoActive,
        lastEvent: String = _debugSnapshot.value.lastEvent
    ) {
        _debugSnapshot.value = DebugSnapshot(
            state = stateMachine.state,
            motionScore = motionScore,
            threshold = motionDetector.thresholdForCurrentSensitivity(),
            videoActive = videoActive,
            touchX = currentSettings.touchX,
            touchY = currentSettings.touchY,
            samplingIntervalMs = currentSettings.detectionIntervalMs,
            lastEvent = lastEvent
        )
        mainHandler.post { updateDebugBadgeText() }
    }

    // ---------- Floating control overlay ----------

    private fun addFloatingViewIfNeeded() {
        if (floatingView != null) {
            Toast.makeText(this, "Point already showing at ${floatingParams?.x}, ${floatingParams?.y}", Toast.LENGTH_SHORT).show()
            return
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable \"Display over other apps\" for Smart Touch AI in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        val wm = windowManager ?: return

        // Build the dot as a plain View with a directly-set background color, sized in
        // raw pixels - this avoids any XML-inflation / shape-drawable rendering edge
        // cases and is the most reliable way to guarantee something visible appears.
        val sizePx = (currentSettings.dotSizeDp * resources.displayMetrics.density).toInt().coerceAtLeast(2)
        val view = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#C85AFF"))
                setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), android.graphics.Color.WHITE)
            }
            elevation = 999f
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        params.gravity = Gravity.TOP or Gravity.START

        val metrics = resources.displayMetrics
        val bounds = safeContentBounds()
        val defaultX = bounds?.right ?: (metrics.widthPixels - (30 * metrics.density).toInt())
        val defaultY = bounds?.bottom ?: (metrics.heightPixels - (100 * metrics.density).toInt())
        // Use the exact same point the actual tap dispatch would use (clamped to
        // the safe content area, landscape-aware), not the raw saved touchX/Y.
        // Previously this read currentSettings.touchX/Y directly, which could be
        // an unclamped value - so the dot could appear in one spot when first
        // shown and then jump as soon as a real tap synced it to safeTouchPoint(),
        // making it look like the point moved on its own.
        val safePoint = safeTouchPoint()
        params.x = safePoint?.first?.toInt() ?: defaultX
        params.y = safePoint?.second?.toInt() ?: defaultY

        setupDragListener(view, params)

        val added = runCatching { wm.addView(view, params) }
        if (added.isFailure) {
            val reason = added.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
            Toast.makeText(this, "Couldn't show the point: $reason", Toast.LENGTH_LONG).show()
            updateNotification("Overlay permission missing - enable it in app settings", videoActive = false)
            return
        }
        Toast.makeText(this, "Point shown at ${params.x}, ${params.y}", Toast.LENGTH_SHORT).show()
        floatingView = view
        floatingParams = params
        overlayVisible = true

        applyOverlayVisuals(currentSettings)
    }

    private fun removeFloatingView() {
        val wm = windowManager ?: return
        if (floatingView == null) {
            Toast.makeText(this, "No point currently showing", Toast.LENGTH_SHORT).show()
            return
        }
        floatingView?.let { runCatching { wm.removeView(it) } }
        floatingView = null
        floatingParams = null
        overlayVisible = false
        Toast.makeText(this, "Point hidden", Toast.LENGTH_SHORT).show()
    }

    private fun addDetectionPointIfNeeded() {
        if (detectionPointView != null) {
            Toast.makeText(this, "Detection point already showing", Toast.LENGTH_SHORT).show()
            return
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable \"Display over other apps\" for Smart Touch AI first", Toast.LENGTH_LONG).show()
            return
        }
        val wm = windowManager ?: return

        val sizePx = (18 * resources.displayMetrics.density).toInt()
        val view = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#6BD6FF"))
                setStroke((2 * resources.displayMetrics.density).toInt(), android.graphics.Color.WHITE)
            }
            elevation = 999f
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val metrics = resources.displayMetrics
        val existing = currentSettings
        params.x = if (existing.hasDetectionPosition) existing.detectionX.toInt() else metrics.widthPixels / 2
        params.y = if (existing.hasDetectionPosition) existing.detectionY.toInt() else metrics.heightPixels / 3

        setupDragListener(view, params) { x, y ->
            serviceScope.launch { settingsRepository.updateDetectionPosition(x, y) }
        }

        val added = runCatching { wm.addView(view, params) }
        if (added.isFailure) {
            Toast.makeText(this, "Couldn't show detection point: ${added.exceptionOrNull()?.javaClass?.simpleName}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Detection point shown at ${params.x}, ${params.y}", Toast.LENGTH_SHORT).show()
        detectionPointView = view
        detectionPointParams = params
    }

    private fun removeDetectionPoint() {
        val wm = windowManager ?: return
        if (detectionPointView == null) {
            Toast.makeText(this, "No detection point currently showing", Toast.LENGTH_SHORT).show()
            return
        }
        detectionPointView?.let { runCatching { wm.removeView(it) } }
        detectionPointView = null
        detectionPointParams = null
        Toast.makeText(this, "Detection point hidden", Toast.LENGTH_SHORT).show()
    }

    private fun applyOverlayVisuals(settings: TouchSettings) {
        val view = floatingView ?: return
        view.alpha = settings.overlayOpacity.coerceIn(0.15f, 1f)
        view.visibility = if (settings.cleanScreenMode) View.GONE else View.VISIBLE
    }

    /**
     * Moves the visible dot to match settings.touchX/Y whenever it changes from
     * somewhere other than dragging the dot itself - e.g. the nudge buttons in
     * Touch Settings. Previously only applyOverlayVisuals reacted to settings
     * changes (alpha/visibility only), so pressing a nudge button silently wrote
     * the new position to disk but the on-screen dot never actually moved,
     * making the buttons look like they weren't doing anything.
     */
    private fun repositionDotFromSettings(settings: TouchSettings) {
        val view = floatingView ?: return
        val params = floatingParams ?: return
        if (!settings.hasTouchPosition) return
        params.x = settings.touchX.toInt()
        params.y = settings.touchY.toInt()
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun addDebugBadgeIfNeeded() {
        if (debugBadgeView != null) return
        val wm = windowManager ?: return

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_debug_badge, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 8
        params.y = 120

        val added = runCatching { wm.addView(view, params) }
        if (added.isFailure) {
            Toast.makeText(this, "Couldn't show live overlay: ${added.exceptionOrNull()?.javaClass?.simpleName}", Toast.LENGTH_LONG).show()
            return
        }
        debugBadgeView = view
        debugBadgeParams = params
        updateDebugBadgeText()
    }

    private fun removeDebugBadge() {
        val wm = windowManager ?: return
        debugBadgeView?.let { runCatching { wm.removeView(it) } }
        debugBadgeView = null
        debugBadgeParams = null
    }

    private fun updateDebugBadgeText() {
        val badge = debugBadgeView as? android.widget.TextView ?: return
        val snap = _debugSnapshot.value
        badge.text = "%s | motion %.3f / %.3f | %s".format(
            snap.state.name, snap.motionScore, snap.threshold, snap.lastEvent
        )
    }

    private fun setupDragListener(
        view: View,
        params: WindowManager.LayoutParams,
        onDragEnd: (x: Float, y: Float) -> Unit = { x, y ->
            serviceScope.launch { settingsRepository.updateTouchPosition(x, y) }
        }
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            if (currentSettings.overlayLocked) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val bounds = safeContentBounds()
                    var newX = initialX + (event.rawX - initialTouchX).toInt()
                    var newY = initialY + (event.rawY - initialTouchY).toInt()
                    if (bounds != null) {
                        newX = newX.coerceIn(bounds.left, bounds.right)
                        newY = newY.coerceIn(bounds.top, bounds.bottom)
                    }
                    params.x = newX
                    params.y = newY
                    runCatching { windowManager?.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    onDragEnd(params.x.toFloat(), params.y.toFloat())
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Notification ----------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Smart Touch AI status",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(statusText: String, videoActive: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun actionIntent(action: String, requestCode: Int): PendingIntent {
            val intent = Intent(this, ServiceActionReceiver::class.java).apply { this.action = action }
            return PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val videoStatus = if (videoActive) "VIDEO ACTIVE" else "VIDEO INACTIVE"

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Touch AI - $statusText")
            .setContentText(videoStatus)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Start", actionIntent(ACTION_START, 1))
            .addAction(0, "Pause", actionIntent(ACTION_PAUSE, 2))
            .addAction(0, "STOP", actionIntent(ACTION_STOP, 3))

        manager.notify(NOTIF_ID, builder.build())
    }
}
