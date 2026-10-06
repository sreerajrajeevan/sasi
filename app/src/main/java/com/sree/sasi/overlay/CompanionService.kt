package com.sree.sasi.overlay

import android.app.ActivityManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Every 5 seconds it: syncs the persisted [VisibilityState], advances the
 * [ScreenTimeTracker], evaluates [ReminderEngine], updates the [MoodEngine],
 * applies prefs to the overlay and re-queries the screen size (so rotation
 * is handled). Gentle nudges and chatter are bubble-only — no notification,
 * no sound. Real warnings pop Sasi back (when the visibility state allows),
 * show a notification with the system sound, and auto-hide after a minute.
 *
 * A screen on/off receiver pauses the movement loop when the screen is off
 * (battery) and implements the hide-until-screen-lock restore.
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
        const val ACTION_SHOW = "com.sree.sasi.action.SHOW"

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

        /** Bring Sasi back from any hidden state (Home "Bring Sasi Back"). */
        fun bringBack(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CompanionService::class.java).setAction(ACTION_SHOW),
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
    private val moodEngine = MoodEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val powerManager: PowerManager by lazy {
        getSystemService(POWER_SERVICE) as PowerManager
    }

    private var view: CompanionView? = null
    private var menuView: HideMenuView? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var tickCount = 0
    private var lastInteractMillis = 0L
    private var visibility = VisibilityState.VISIBLE
    private var lockHideArmed = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    view?.systemPaused = true
                    if (visibility == VisibilityState.HIDDEN_UNTIL_SCREEN_LOCK) {
                        // The next screen-on will restore Sasi.
                        scope.launch { prefs.setLockHideArmed(true) }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    view?.systemPaused = false
                    if (visibility == VisibilityState.HIDDEN_UNTIL_SCREEN_LOCK && lockHideArmed) {
                        scope.launch {
                            prefs.setLockHideArmed(false)
                            prefs.setVisibilityState(VisibilityState.VISIBLE.name)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = (application as SasiApp).prefs
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
        scope.launch {
            visibility = try {
                VisibilityState.fromName(prefs.snapshot().visibilityState)
            } catch (e: Exception) {
                VisibilityState.VISIBLE
            }
            withContext(Dispatchers.Main) {
                // Apply the persisted state before the first tick: a hidden
                // Sasi must never flash visible on service start/restart.
                if (visibility == VisibilityState.DISABLED) {
                    stopSelf()
                    return@withContext
                }
                ensureOverlay()
                applyVisibility()
            }
        }
        startForegroundInternal()
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

            // Visibility state machine (authoritative state lives in Prefs).
            val newVis = VisibilityState.fromName(snapshot.visibilityState)
            lockHideArmed = snapshot.lockHideArmed
            if (newVis != visibility) {
                visibility = newVis
                withContext(Dispatchers.Main) { applyVisibility() }
                if (visibility == VisibilityState.DISABLED) return@launch
            }
            val now = System.currentTimeMillis()
            if (visibility == VisibilityState.HIDDEN_TEMPORARILY &&
                snapshot.hiddenUntilMillis > 0L && now >= snapshot.hiddenUntilMillis
            ) {
                prefs.setVisibilityState(VisibilityState.VISIBLE.name)
            }
            if (menuView != null && now >= menuDismissAt) dismissMenuOnMain()

            val interactive = try {
                powerManager.isInteractive
            } catch (e: Exception) {
                true
            }
            tracker.tick(interactive)
            prefs.setContinuousMinutes(tracker.continuousMinutes)

            val todayMinutes = try {
                tracker.getTodayScreenMinutes(this@CompanionService).toInt()
            } catch (e: Exception) {
                0
            }

            // Mood.
            val bedtime = ReminderEngine.isBedtime(
                now,
                snapshot.bedtimeHour,
                snapshot.bedtimeMinute,
                snapshot.wakeHour,
                snapshot.wakeMinute,
            )
            val mood = moodEngine.update(
                MoodEngine.Input(
                    nowMillis = now,
                    continuousMinutes = tracker.continuousMinutes,
                    todayMinutes = todayMinutes,
                    isInteractive = interactive,
                    screenOn = interactive,
                    lastInteractMillis = lastInteractMillis,
                    inBedtimeWindow = bedtime,
                ),
            )

            // Reminders.
            val input = ReminderEngine.Input(
                nowMillis = now,
                name = snapshot.companionName,
                isInteractive = interactive,
                continuousMinutes = tracker.continuousMinutes,
                sessionStartMillis = tracker.sessionStartMillis,
                todayScreenMinutes = todayMinutes,
                restIntervalMinutes = snapshot.restMinutes,
                bedtimeHour = snapshot.bedtimeHour,
                bedtimeMinute = snapshot.bedtimeMinute,
                wakeHour = snapshot.wakeHour,
                wakeMinute = snapshot.wakeMinute,
                tickCount = tickCount++,
                dayKey = Prefs.todayKey(),
                firedKeys = snapshot.firedKeys,
            )
            val reminder = engine.evaluate(input)
            val key = reminder?.let {
                ReminderEngine.keyFor(it, input.dayKey, input.sessionStartMillis, input.tickCount, now)
            }
            val shouldFire = reminder != null && (key == null || key !in input.firedKeys)
            if (shouldFire && reminder != null) {
                if (key != null) prefs.markFired(key)
                handleReminder(reminder, snapshot, interactive)
            }

            withContext(Dispatchers.Main) {
                val v = view
                if (v != null) {
                    v.setMood(mood)
                    v.applyPrefs(
                        snapshot.overlaySize,
                        snapshot.walkSpeed,
                        CompanionView.colorForTheme(snapshot.colorTheme, this@CompanionService),
                        snapshot.movementMode,
                        snapshot.movementFrequency,
                    )
                    v.tapReactionsEnabled = snapshot.tapReactions
                    v.speechBubblesEnabled = snapshot.speechBubbles
                    v.hapticEnabled = snapshot.hapticFeedback
                    v.refreshScreenSize()
                }
            }
        }
    }

    private suspend fun handleReminder(
        reminder: ReminderEngine.Reminder,
        snapshot: com.sree.sasi.data.PrefsSnapshot,
        interactive: Boolean,
    ) {
        val silent = reminder is ReminderEngine.Reminder.IdleChatter ||
            reminder is ReminderEngine.Reminder.GentleNudge ||
            reminder is ReminderEngine.Reminder.Milestone
        if (silent) {
            // Bubble-only: never a notification, never a sound, never pops back.
            if (snapshot.speechBubbles) {
                withContext(Dispatchers.Main) { view?.speak(reminder.bubble()) }
            }
            return
        }
        // Real warning (rest due / bedtime / wake): notification + system sound.
        val canRestore = visibility == VisibilityState.VISIBLE || visibility.restorableByReminder()
        if (canRestore && visibility != VisibilityState.VISIBLE) {
            prefs.setVisibilityState(VisibilityState.VISIBLE.name)
        }
        if (canRestore) {
            withContext(Dispatchers.Main) {
                ensureOverlay()
                view?.showForWarning()
                if (interactive && snapshot.speechBubbles) view?.speak(reminder.bubble())
                view?.scheduleAutoHide(60_000L)
            }
        }
        Notif.showReminder(this@CompanionService, reminder.title(), reminder.text())
    }

    /** Applies the current [visibility] to the overlay (main thread). */
    private fun applyVisibility() {
        when (visibility) {
            VisibilityState.VISIBLE -> {
                ensureOverlay()
                view?.ensureVisible()
            }
            VisibilityState.DISABLED -> {
                dismissMenu()
                removeOverlayView()
                stopSelf()
            }
            else -> {
                dismissMenu()
                view?.hideImmediately()
            }
        }
    }

    private fun setVisibility(state: VisibilityState) {
        // Update in-memory state and the overlay immediately; persist for the
        // tick loop (which treats Prefs as authoritative) right after.
        visibility = state
        handler.post { applyVisibility() }
        scope.launch { prefs.setVisibilityState(state.name) }
    }

    private fun hideForMinutes(minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        visibility = VisibilityState.HIDDEN_TEMPORARILY
        handler.post { applyVisibility() }
        scope.launch {
            prefs.setHiddenUntilMillis(until)
            prefs.setVisibilityState(VisibilityState.HIDDEN_TEMPORARILY.name)
        }
    }

    private fun ensureOverlay() {
        if (view != null) return
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        // Note: the window is intentionally sized to the character (WRAP_CONTENT),
        // not full-screen, so touches pass through to the apps underneath everywhere
        // except on Sasi itself.
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
        val companionView = CompanionView(this)
        companionView.onUserInteraction = { lastInteractMillis = System.currentTimeMillis() }
        companionView.onLongPressMenu = { showHideMenu() }
        companionView.onDrop = { x, y -> scope.launch { prefs.setPos(x, y) } }
        try {
            wm.addView(companionView, params)
        } catch (e: Exception) {
            return
        }
        view = companionView

        scope.launch {
            val s = try {
                prefs.snapshot()
            } catch (e: Exception) {
                return@launch
            }
            val (screenWidth, screenHeight) = screenSize()
            val color = CompanionView.colorForTheme(s.colorTheme, this@CompanionService)
            withContext(Dispatchers.Main) {
                companionView.applyPrefs(
                    s.overlaySize, s.walkSpeed, color, s.movementMode, s.movementFrequency,
                )
                companionView.bindWindow(
                    wm,
                    params,
                    screenWidth,
                    screenHeight,
                    if (s.posX >= 0f) s.posX else (screenWidth / 2).toFloat(),
                    if (s.posY >= 0f) s.posY else (screenHeight / 3).toFloat(),
                )
            }
        }
    }

    private fun removeOverlayView() {
        view?.let { v ->
            v.destroy()
            try {
                wm.removeView(v)
            } catch (e: Exception) {
                // Already removed.
            }
        }
        view = null
    }

    // ------------------------------------------------------------------
    // Long-press hide menu
    // ------------------------------------------------------------------

    private var menuDismissAt = 0L

    private fun showHideMenu() {
        val v = view ?: return
        if (menuView != null) return
        v.externalPause = true
        val menu = HideMenuView(this)
        menu.onAction = { action ->
            dismissMenu()
            when (action) {
                HideMenuView.HideAction.HIDE_15MIN -> hideForMinutes(15)
                HideMenuView.HideAction.HIDE_1HOUR -> hideForMinutes(60)
                HideMenuView.HideAction.HIDE_UNTIL_LOCK ->
                    setVisibility(VisibilityState.HIDDEN_UNTIL_SCREEN_LOCK)
                HideMenuView.HideAction.CLOSE_UNTIL_REMINDER ->
                    setVisibility(VisibilityState.HIDDEN_UNTIL_REMINDER)
                HideMenuView.HideAction.TURN_OFF -> setVisibility(VisibilityState.DISABLED)
                HideMenuView.HideAction.CANCEL -> Unit
            }
        }
        val density = resources.displayMetrics.density
        val margin = (16 * density).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Anchor the card just above Sasi, clamped on-screen.
            x = v.currentX().toInt().coerceAtLeast(margin)
            y = (v.currentY() - menu.estimatedHeightPx() - margin).toInt().coerceAtLeast(margin)
        }
        menuView = menu
        menuParams = params
        try {
            wm.addView(menu, params)
        } catch (e: Exception) {
            menuView = null
            menuParams = null
            v.externalPause = false
            return
        }
        menuDismissAt = System.currentTimeMillis() + 10_000L
        menu.animateIn()
    }

    private fun dismissMenuOnMain() {
        handler.post { dismissMenu() }
    }

    private fun dismissMenu() {
        val menu = menuView ?: return
        menuView = null
        menuParams = null
        menuDismissAt = 0L
        try {
            wm.removeView(menu)
        } catch (e: Exception) {
            // Already removed.
        }
        view?.externalPause = false
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { prefs.setCompanionEnabled(false) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                // Notification toggle: hide until the next reminder, or bring back.
                scope.launch {
                    val next = if (visibility == VisibilityState.VISIBLE) {
                        VisibilityState.HIDDEN_UNTIL_REMINDER
                    } else {
                        VisibilityState.VISIBLE
                    }
                    prefs.setVisibilityState(next.name)
                    if (next == VisibilityState.VISIBLE) {
                        withContext(Dispatchers.Main) {
                            ensureOverlay()
                            view?.showForWarning()
                        }
                    }
                }
                return START_STICKY
            }
            ACTION_SHOW -> {
                scope.launch {
                    prefs.setVisibilityState(VisibilityState.VISIBLE.name)
                    withContext(Dispatchers.Main) {
                        ensureOverlay()
                        view?.showForWarning()
                        view?.scheduleAutoHide(60_000L)
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
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // Never registered or already unregistered.
        }
        dismissMenu()
        removeOverlayView()
        scope.cancel()
        super.onDestroy()
    }
}
