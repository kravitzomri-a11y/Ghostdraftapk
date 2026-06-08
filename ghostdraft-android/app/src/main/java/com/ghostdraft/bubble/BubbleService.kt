package com.ghostdraft.bubble

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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ghostdraft.bubble.recognizer.AndroidSpeechRecognizer
import com.ghostdraft.bubble.recognizer.Recognizer

/**
 * The always-on floating mic. A draggable overlay button: tap = listen,
 * tap again = stop & insert. The window is NOT focusable, which is the whole
 * trick — tapping the bubble doesn't pull focus away from the text field
 * underneath, so the AccessibilityService still finds it to write into.
 */
class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: ImageView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var recognizer: Recognizer

    private var listening = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        recognizer = AndroidSpeechRecognizer(this)
        startInForeground()
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // ---- Foreground notification ------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "GhostDraft bubble",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }

        val stopIntent = android.app.PendingIntent.getService(
            this, 0,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GhostDraft is active")
            .setContentText("Tap the floating mic to dictate anywhere.")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()

        // On Android 14+ a microphone-typed FGS throws if RECORD_AUDIO isn't
        // held. Only claim the mic type when we actually have the permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Permissions.hasMic(this)) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---- The floating view -------------------------------------------------

    private fun addBubble() {
        bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setBackgroundResource(R.drawable.bubble_bg)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // NOT_FOCUSABLE: never steal focus from the field below.
            // LAYOUT_NO_LIMITS: lets the bubble sit flush at screen edges.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(180)
        }

        bubble.setOnTouchListener(DragTapListener())
        windowManager.addView(bubble, params)
    }

    /** Distinguishes a drag (move the bubble) from a tap (toggle listening). */
    private inner class DragTapListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (!moved && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager.updateViewLayout(bubble, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleListening()
                    return true
                }
            }
            return false
        }
    }

    // ---- Listening lifecycle ----------------------------------------------

    private fun toggleListening() {
        if (listening) recognizer.stop() else startListening()
    }

    private fun startListening() {
        if (!Permissions.hasMic(this)) {
            toast("Microphone permission is off — open GhostDraft to grant it.")
            return
        }
        setListeningUi(true)
        recognizer.start(object : Recognizer.Callbacks {
            override fun onFinalText(text: String) {
                if (text.isNotBlank()) deliver(text)
            }
            override fun onError(message: String) = toast(message)
            override fun onDone() = setListeningUi(false)
        })
    }

    private fun deliver(text: String) {
        val svc = GhostDraftAccessibilityService.instance
        if (svc == null) {
            toast("Enable GhostDraft accessibility to type into apps. Text copied.")
            copyToClipboard(text)
            return
        }
        when (svc.insertIntoFocusedField(text)) {
            GhostDraftAccessibilityService.Result.COPIED_TO_CLIPBOARD ->
                toast("No text field focused — copied to clipboard.")
            GhostDraftAccessibilityService.Result.NO_FOCUS,
            GhostDraftAccessibilityService.Result.NOT_EDITABLE ->
                toast("Tap into a text field first.")
            else -> { /* inserted / pasted — stay quiet */ }
        }
    }

    private fun setListeningUi(on: Boolean) {
        listening = on
        bubble.post {
            bubble.setBackgroundResource(
                if (on) R.drawable.bubble_bg_active else R.drawable.bubble_bg
            )
            bubble.setImageResource(if (on) R.drawable.ic_mic_active else R.drawable.ic_mic)
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("GhostDraft", text))
    }

    // ---- Helpers -----------------------------------------------------------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) =
        bubble.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { recognizer.destroy() }
        if (this::bubble.isInitialized && bubble.isAttachedToWindow) {
            runCatching { windowManager.removeView(bubble) }
        }
    }

    companion object {
        const val ACTION_STOP = "com.ghostdraft.bubble.STOP"
        private const val CHANNEL_ID = "ghostdraft_bubble"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, BubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BubbleService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
