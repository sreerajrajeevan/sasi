package com.sree.sasi.overlay

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.sree.sasi.SasiApp
import com.sree.sasi.data.Prefs
import com.sree.sasi.reminders.ReminderEngine
import com.sree.sasi.screentime.ScreenTimeTracker
import com.sree.sasi.util.Notif
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service hosting the floating companion overlay.
 *
 * Every 5 seconds it: checks screen interactivity, advances the
 * [ScreenTimeTracker], evaluates [ReminderEngine], applies prefs to the
 * overlay and toggles sleep mode inside the bedtime window.
 *
 * Started from onboarding, the Home toggle, or [com.sree.sasi.BootReceiver].
 * Background start from boot is permitted because the app holds
 * SYSTEM_ALERT_WINDOW (a background-FGS-start exemption).
 */
class CompanionService : Service() {

    companion object {
        const val ACTION_START = "com.sree.sasi.action.START"
        const val ACTION_STOP = "com.sree.sasi.action.STOP"
        const val ACTION_TOGGLE = "com.sree.sasi.action.TOGGLE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CompanionService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CompanionService::class.java).setAction(ACTION_STOP),
            )
        }

        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return manager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == CompanionService::class.java.name }
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val tracker = ScreenTimeTracker()
    private val engine = ReminderEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val powerManager: PowerManager by lazy {
        getSystemService(POWER_SERVICE) as PowerManager
    }

    private var view: CompanionView? = null
    private var tickCount = 0

    override fun onCreate() {
        super.onCreate()
        prefs = (application as SasiApp).prefs
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundInternal()
        addOverlay()
        handler.post(tickLoop)
    }

    private fun startForegroundInternal() {
        val notification = Notif.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notif.SERVICE_NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(Notif.SERVICE_NOTIF_ID, notification)
        }
    }

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }
        // Note: the window is intentionally sized to the character (WRAP_CONTENT),
        // not full-screen, so touches pass through to the apps underneath everywhere
        // except on Sasi itself.
        val companionView = CompanionView(this)
        wm.addView(companionView, params)
        view = companionView

        scope.launch {
            val snapshot = try {
                prefs.snapshot()
            } catch (e: Exception) {
                return@launch
            }
            val (screenWidth, screenHeight) = screenSize()
            val color = CompanionView.colorForTheme(snapshot.colorTheme, this@CompanionService)
            withContext(Dispatchers.Main) {
                companionView.applyPrefs(snapshot.overlaySize, snapshot.walkSpeed, color)
                companionView.bindWindow(
                    wm,
                    params,
                    screenWidth,
                    screenHeight,
                    (screenWidth / 2).toFloat(),
                    (screenHeight / 3).toFloat(),
                )
            }
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private val tickLoop = object : Runnable {
        override fun run() {
            doTick()
            handler.postDelayed(this, 5000)
        }
    }

    private fun doTick() {
        scope.launch {
            val snapshot = try {
                prefs.snapshot()
            } catch (e: Exception) {
                return@launch
            }
            if (!snapshot.companionEnabled) {
                stopSelf()
                return@launch
            }

            val interactive = try {
                powerManager.isInteractive
            } catch (e: Exception) {
                true
            }
            tracker.tick(interactive)
            prefs.setContinuousMinutes(tracker.continuousMinutes)

            val todayMinutes = try {
                tracker.getTodayScreenMinutes(this@CompanionService)
            } catch (e: Exception) {
                0L
            }

            val now = System.currentTimeMillis()
            val dayKey = Prefs.todayKey()
            val input = ReminderEngine.Input(
                nowMillis = now,
                name = snapshot.companionName,
                isInteractive = interactive,
                continuousMinutes = tracker.continuousMinutes,
                sessionStartMillis = tracker.sessionStartMillis,
                todayScreenMinutes = todayMinutes.toInt(),
                restIntervalMinutes = snapshot.restMinutes,
                bedtimeHour = snapshot.bedtimeHour,
                bedtimeMinute = snapshot.bedtimeMinute,
                wakeHour = snapshot.wakeHour,
                wakeMinute = snapshot.wakeMinute,
                tickCount = tickCount++,
                dayKey = dayKey,
                firedKeys = snapshot.firedKeys,
            )
            val bedtime = ReminderEngine.isBedtime(
                now,
                snapshot.bedtimeHour,
                snapshot.bedtimeMinute,
                snapshot.wakeHour,
                snapshot.wakeMinute,
            )
            val reminder = engine.evaluate(input)
            val key = reminder?.let {
                ReminderEngine.keyFor(it, dayKey, input.sessionStartMillis, input.tickCount)
            }
            val shouldFire = reminder != null && (key == null || key !in input.firedKeys)
            if (shouldFire && reminder != null) {
                if (key != null) prefs.markFired(key)
                Notif.showReminder(this@CompanionService, reminder.title(), reminder.text())
            }

            withContext(Dispatchers.Main) {
                val v = view
                if (v != null) {
                    v.setSleeping(bedtime)
                    v.applyPrefs(
                        snapshot.overlaySize,
                        snapshot.walkSpeed,
                        CompanionView.colorForTheme(snapshot.colorTheme, this@CompanionService),
                    )
                    if (shouldFire && reminder != null) {
                        val isChatter = reminder is ReminderEngine.Reminder.IdleChatter
                        if (!isChatter || !v.isHiddenByUser()) {
                            v.showForWarning()
                            if (interactive) v.speak(reminder.bubble())
                            v.scheduleAutoHide(30_000L)
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { prefs.setCompanionEnabled(false) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                val v = view
                if (v != null) {
                    if (v.isHiddenByUser()) {
                        v.showForWarning()
                        v.scheduleAutoHide(60_000L)
                    } else {
                        v.byeAndHide()
                    }
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tickLoop)
        scope.cancel()
        view?.let { v ->
            v.destroy()
            try {
                wm.removeView(v)
            } catch (e: Exception) {
                // Already removed.
            }
        }
        view = null
        super.onDestroy()
    }
}
