package com.example.autoclicker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SHOW = "ACTION_SHOW"
        const val ACTION_HIDE = "ACTION_HIDE"
        const val ACTION_TOGGLE_CLICK = "ACTION_TOGGLE_CLICK"
        const val CHANNEL_ID = "autoclicker_channel"
        const val NOTIF_ID = 1
        const val CLICK_INTERVAL_MS = 800L
    }

    private lateinit var windowManager: WindowManager
    private var toolbarView: View? = null
    private var targetView: View? = null
    private var isToolbarVisible = false
    private var isClicking = false

    // إحداثيات نقطة الضغط الحالية (بداية في منتصف الشاشة تقريبًا)
    private var targetX = 300
    private var targetY = 600

    private val clickHandler = Handler(Looper.getMainLooper())
    private val clickRunnable = object : Runnable {
        override fun run() {
            if (isClicking) {
                ClickAccessibilityService.instance?.performClick(targetX.toFloat(), targetY.toFloat())
                clickHandler.postDelayed(this, CLICK_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopClicking()
                removeOverlayViews()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> {
                startForeground(NOTIF_ID, buildNotification())
                showOverlayViews()
                return START_STICKY
            }
            ACTION_HIDE -> {
                startForeground(NOTIF_ID, buildNotification())
                hideOverlayViews()
                return START_STICKY
            }
            ACTION_TOGGLE_CLICK -> {
                toggleClicking()
                startForeground(NOTIF_ID, buildNotification())
                return START_STICKY
            }
            else -> {
                // ACTION_START أو تشغيل أول مرة
                startForeground(NOTIF_ID, buildNotification())
                addOverlayViews()
                return START_STICKY
            }
        }
    }

    // ---------- إنشاء وإدارة العناصر العائمة ----------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun addOverlayViews() {
        if (toolbarView != null) return // متضاف بالفعل

        val inflater = LayoutInflater.from(this)

        // --- البار (التولز) ---
        toolbarView = inflater.inflate(R.layout.overlay_toolbar, null)
        val toolbarParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUS_MODAL,
            PixelFormat.TRANSLUCENT
        )
        toolbarParams.gravity = Gravity.TOP or Gravity.START
        toolbarParams.x = 50
        toolbarParams.y = 150

        setupToolbarDrag(toolbarView!!, toolbarParams)

        toolbarView!!.findViewById<ImageButton>(R.id.btnPlayPause).setOnClickListener {
            toggleClicking()
        }
        toolbarView!!.findViewById<ImageButton>(R.id.btnHide).setOnClickListener {
            hideOverlayViews()
        }

        windowManager.addView(toolbarView, toolbarParams)

        // --- علامة الهدف (نقطة الضغط) ---
        targetView = inflater.inflate(R.layout.overlay_target, null)
        val targetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUS_MODAL,
            PixelFormat.TRANSLUCENT
        )
        targetParams.gravity = Gravity.TOP or Gravity.START
        targetParams.x = targetX
        targetParams.y = targetY

        setupTargetDrag(targetView!!, targetParams)

        windowManager.addView(targetView, targetParams)

        isToolbarVisible = true
    }

    private fun showOverlayViews() {
        if (toolbarView == null) {
            addOverlayViews()
        } else if (!isToolbarVisible) {
            toolbarView?.let { windowManager.addView(it, it.layoutParams) }
            targetView?.let { windowManager.addView(it, it.layoutParams) }
            isToolbarVisible = true
        }
    }

    /** إخفاء تام: شيل الـ Views من الـ WindowManager نفسه فمفيش أي بيكسل بيترسم */
    private fun hideOverlayViews() {
        if (isToolbarVisible) {
            toolbarView?.let { runCatching { windowManager.removeView(it) } }
            targetView?.let { runCatching { windowManager.removeView(it) } }
            isToolbarVisible = false
        }
    }

    private fun removeOverlayViews() {
        hideOverlayViews()
        toolbarView = null
        targetView = null
    }

    private fun setupToolbarDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        view.findViewById<ImageButton>(R.id.btnDrag).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupTargetDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    // نحدث إحداثيات الضغط الفعلية لمركز العلامة
                    targetX = params.x + view.width / 2
                    targetY = params.y + view.height / 2
                    true
                }
                else -> false
            }
        }
    }

    // ---------- منطق الضغط التلقائي ----------

    private fun toggleClicking() {
        isClicking = !isClicking
        if (isClicking) {
            clickHandler.post(clickRunnable)
        } else {
            clickHandler.removeCallbacks(clickRunnable)
        }
        val icon = toolbarView?.findViewById<ImageButton>(R.id.btnPlayPause)
        icon?.setImageResource(
            if (isClicking) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun stopClicking() {
        isClicking = false
        clickHandler.removeCallbacks(clickRunnable)
    }

    // ---------- الإشعار: هو وسيلة التحكم الوحيدة وقت إخفاء البار ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Auto Clicker", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun pendingActionIntent(action: String): PendingIntent {
        val intent = Intent(this, OverlayService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun buildNotification(): android.app.Notification {
        val toggleLabel = if (isToolbarVisible) "إخفاء البار" else "إظهار البار"
        val toggleAction = if (isToolbarVisible) ACTION_HIDE else ACTION_SHOW
        val clickLabel = if (isClicking) "إيقاف الضغط" else "بدء الضغط"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Clicker شغال")
            .setContentText(if (isClicking) "بيضغط كل ${CLICK_INTERVAL_MS}ms" else "متوقف")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, toggleLabel, pendingActionIntent(toggleAction))
            .addAction(0, clickLabel, pendingActionIntent(ACTION_TOGGLE_CLICK))
            .addAction(0, "إيقاف نهائي", pendingActionIntent(ACTION_STOP))
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopClicking()
        removeOverlayViews()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
