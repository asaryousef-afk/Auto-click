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
import com.smarttouch.ai.MainActivity
import com.smarttouch.ai.R
import com.smarttouch.ai.ServiceActionReceiver
import com.smarttouch.ai.data.SettingsRepository
import com.smarttouch.ai.data.TouchSettings
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
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var overlayVisible = false

    private val mainHandler = Handler(Looper.getMainLooper())

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
                }
            }
        }

        updateNotification("Ready", videoActive = false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used - detection relies on periodic screenshots */ }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        instance = null
        stopEngine()
        removeFloatingView()
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

    fun hideFloatingControl() {
        mainHandler.post { removeFloatingView() }
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

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    pushDebugSnapshot(lastEvent = "Video detection needs Android 11+")
                    delay(1000)
                    continue
                }

                val frame = captureLuminanceFrame()
                if (frame != null) {
                    val score = motionDetector.analyzeFrame(frame)
                    val motionDetected = motionDetector.isMotionSignificant(score)
                    val active = activityTracker.onFrameAnalyzed(motionDetected, System.currentTimeMillis())

                    pushDebugSnapshot(
                        motionScore = score,
                        videoActive = active,
                        lastEvent = if (motionDetected) "Motion" else "Still"
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
        val path = Path().apply { moveTo(point.first, point.second) }
        val duration = currentSettings.touchDurationMs.coerceIn(1L, 2000L)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Clamps the configured touch position to stay within the safe content area,
     * away from the system navigation bar / gesture strip, so Smart Touch AI never
     * accidentally triggers Back / Home / Recents.
     */
    private fun safeTouchPoint(): Pair<Float, Float>? {
        val settings = currentSettings
        if (!settings.hasTouchPosition) return null

        val bounds = safeContentBounds() ?: return settings.touchX to settings.touchY
        val x = settings.touchX.coerceIn(bounds.left.toFloat(), bounds.right.toFloat())
        val y = settings.touchY.coerceIn(bounds.top.toFloat(), bounds.bottom.toFloat())
        return x to y
    }

    private fun safeContentBounds(): Rect? {
        windowManager ?: return null
        val metrics: DisplayMetrics = resources.displayMetrics
        val navBarMargin = (48 * metrics.density).toInt() // keep clear of gesture/nav area
        return Rect(
            navBarMargin,
            navBarMargin,
            metrics.widthPixels - navBarMargin,
            metrics.heightPixels - navBarMargin
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
        val w = bitmap.width
        val h = bitmap.height
        var index = 0
        for (gy in 0 until GRID_SIZE) {
            for (gx in 0 until GRID_SIZE) {
                val px = (gx * w / GRID_SIZE).coerceIn(0, w - 1)
                val py = (gy * h / GRID_SIZE).coerceIn(0, h - 1)
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
    }

    // ---------- Floating control overlay ----------

    private fun addFloatingViewIfNeeded() {
        if (floatingView != null) return
        val wm = windowManager ?: return

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_floating_dot, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val existing = currentSettings
        params.x = if (existing.hasTouchPosition) existing.touchX.toInt() else 200
        params.y = if (existing.hasTouchPosition) existing.touchY.toInt() else 400

        setupDragListener(view, params)

        wm.addView(view, params)
        floatingView = view
        floatingParams = params
        overlayVisible = true

        applyOverlayVisuals(currentSettings)
    }

    private fun removeFloatingView() {
        val wm = windowManager ?: return
        floatingView?.let { runCatching { wm.removeView(it) } }
        floatingView = null
        floatingParams = null
        overlayVisible = false
    }

    private fun applyOverlayVisuals(settings: TouchSettings) {
        val view = floatingView ?: return
        view.alpha = settings.overlayOpacity.coerceIn(0.15f, 1f)
        view.visibility = if (settings.cleanScreenMode) View.GONE else View.VISIBLE
    }

    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
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
                    serviceScope.launch {
                        settingsRepository.updateTouchPosition(params.x.toFloat(), params.y.toFloat())
                    }
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
