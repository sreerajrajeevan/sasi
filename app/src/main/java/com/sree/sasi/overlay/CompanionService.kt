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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sree.sasi.SasiApp
import com.sree.sasi.data.Achievements
import com.sree.sasi.data.Awards
import com.sree.sasi.data.DayRecord
import com.sree.sasi.data.History
import com.sree.sasi.data.LifetimeStats
import com.sree.sasi.data.Missions
import com.sree.sasi.data.Prefs
import com.sree.sasi.data.PrefsSnapshot
import com.sree.sasi.data.Progression
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
 * Phase 2: the tick also advances focus/break timers (state persisted in
 * Prefs, so restarts and reboots resume or gracefully expire them), overrides
 * mood/movement while a timer runs, records day history, and mirrors the
 * active timer in the foreground notification.
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
        const val ACTION_CANCEL_MODE = "com.sree.sasi.action.CANCEL_MODE"

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

        /** Cancel an active focus or break timer (Home button / notification). */
        fun cancelModes(context: Context) {
            context.startService(
                Intent(context, CompanionService::class.java).setAction(ACTION_CANCEL_MODE),
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

    // Phase 2: focus/break bookkeeping (timers themselves live in Prefs).
    private var lastFocusBubbleAt = 0L
    private var breakGreetedEndsAt = 0L
    private var lastHistoryScreenMin = -1
    private var lastModeNotifText: String? = null

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
            val dayKey = Prefs.todayKey()

            // --- History: event-driven, never on a hot loop ---
            val history = History.load(snapshot.historyJson)
            var historyDirty = false
            if (dayKey != snapshot.historyDay) {
                // Day rollover: yesterday keeps its last-known totals.
                History.ensureDay(history, dayKey)
                prefs.setHistoryDay(dayKey)
                historyDirty = true
                lastHistoryScreenMin = -1
            }
            if (tracker.takeEndedSession() != null) {
                History.updateDay(history, dayKey) { it.copy(sessions = it.sessions + 1) }
                historyDirty = true
            }
            if (todayMinutes != lastHistoryScreenMin) {
                lastHistoryScreenMin = todayMinutes
                History.updateDay(history, dayKey) { it.copy(screenMin = todayMinutes) }
                historyDirty = true
            }

            // --- Focus / break timers (5s granularity is fine; state in Prefs) ---
            val modes = handleModes(snapshot, history, dayKey, todayMinutes, now)
            if (modes.historyDirty) historyDirty = true

            // --- Phase 3: progression (awards, missions, achievements, rollover) ---
            // Everything rides this 5s tick — no new loops, no new wakeups.
            val bedtimeNow = ReminderEngine.isBedtime(
                now,
                snapshot.bedtimeHour,
                snapshot.bedtimeMinute,
                snapshot.wakeHour,
                snapshot.wakeMinute,
            )
            if (handleProgression(
                    snapshot, history, dayKey, todayMinutes, now,
                    interactive, bedtimeNow, modes,
                )
            ) {
                historyDirty = true
            }
            if (historyDirty) {
                prefs.setHistoryJson(History.save(history))
            }

            // Mood.
            val bedtime = bedtimeNow
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
            // An active focus/break overrides the emotional state.
            val effectiveMood = when {
                modes.focusActive -> Mood.FOCUSED
                modes.breakActive -> Mood.RESTING
                else -> mood
            }

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
                // Phase 3: remember when a rest reminder fired, so "rest
                // respected" can be awarded if the screen goes idle soon after.
                if (reminder is ReminderEngine.Reminder.RestDue) {
                    prefs.setRestFiredAt(now)
                }
                handleReminder(reminder, snapshot, interactive)
            }

            withContext(Dispatchers.Main) {
                val v = view
                if (v != null) {
                    v.setMood(effectiveMood)
                    // Focus/break forces calm, minimal movement (prefs untouched).
                    val calmOverride = modes.focusActive || modes.breakActive
                    v.applyPrefs(
                        snapshot.overlaySize,
                        snapshot.walkSpeed,
                        CompanionView.colorForTheme(snapshot.colorTheme, this@CompanionService),
                        if (calmOverride) 2 else snapshot.movementMode,
                        if (calmOverride) 0 else snapshot.movementFrequency,
                    )
                    v.tapReactionsEnabled = snapshot.tapReactions
                    v.speechBubblesEnabled = snapshot.speechBubbles
                    v.hapticEnabled = snapshot.hapticFeedback
                    v.refreshScreenSize()
                }
            }

            // Foreground notification mirrors the active timer (only on change).
            val modeText = when {
                modes.focusActive ->
                    "🎯 Focus ${ScreenTimeTracker.formatCountdown((snapshot.focusEndsAt - now).coerceAtLeast(0L))}"
                modes.breakActive ->
                    "🌱 Break ${ScreenTimeTracker.formatCountdown((snapshot.breakEndsAt - now).coerceAtLeast(0L))}"
                else -> null
            }
            if (modeText != lastModeNotifText) {
                lastModeNotifText = modeText
                try {
                    NotificationManagerCompat.from(this@CompanionService).notify(
                        Notif.SERVICE_NOTIF_ID,
                        Notif.serviceNotification(
                            this@CompanionService,
                            modeText,
                            modeText != null,
                        ),
                    )
                } catch (e: Exception) {
                    // Notifications revoked; the timer itself still works.
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

    // ------------------------------------------------------------------
    // Focus & break timers (Phase 2)
    // ------------------------------------------------------------------

    private data class ModeResult(
        val focusActive: Boolean,
        val breakActive: Boolean,
        val historyDirty: Boolean,
    )

    /**
     * Advances focus/break timers. All state lives in Prefs, so a service
     * restart or reboot resumes (or gracefully expires) the timer on the
     * next tick. A focus start cancels a break and vice versa.
     */
    private suspend fun handleModes(
        snapshot: PrefsSnapshot,
        history: MutableList<com.sree.sasi.data.DayRecord>,
        dayKey: String,
        todayMinutes: Int,
        now: Long,
    ): ModeResult {
        var dirty = false
        var focusActive = snapshot.focusActive
        var breakActive = snapshot.breakActive
        val name = snapshot.companionName

        // --- Focus ---
        if (focusActive) {
            if (now >= snapshot.focusEndsAt) {
                focusActive = false
                val endedWhileAway = now - snapshot.focusEndsAt > 90_000L
                History.updateDay(history, dayKey) {
                    it.copy(
                        focusSessions = it.focusSessions + 1,
                        focusMin = it.focusMin + snapshot.focusTotalMin,
                        screenMin = todayMinutes,
                    )
                }
                dirty = true
                prefs.setFocusActive(false)
                if (snapshot.focusPomodoro) {
                    // Pomodoro: a 5-minute break, then the next cycle auto-starts.
                    val breakEnds = now + 5 * 60_000L
                    prefs.setBreakActive(true)
                    prefs.setBreakEndsAt(breakEnds)
                    prefs.setBreakTotalSec(300)
                    prefs.setBreakIsPomodoro(true)
                    breakActive = true
                    breakGreetedEndsAt = breakEnds // skip the "stretch" greeting
                    if (!endedWhileAway) {
                        if (snapshot.speechBubbles) {
                            withContext(Dispatchers.Main) {
                                view?.flashMood(Mood.HAPPY, 2000)
                                view?.speak("Cycle ${snapshot.focusCycle} done! Break 🌱")
                            }
                        }
                        Notif.showQuietReminder(
                            this,
                            "$name: break time",
                            "5-minute breather, then back to focus.",
                        )
                    }
                } else if (!endedWhileAway) {
                    // A focus completion is an event the user asked for: it may
                    // bring Sasi back, like a reminder does.
                    prefs.setVisibilityState(VisibilityState.VISIBLE.name)
                    withContext(Dispatchers.Main) {
                        ensureOverlay()
                        view?.showForWarning()
                        view?.flashMood(Mood.EXCITED, 2500)
                        if (snapshot.speechBubbles) view?.speak("Focus complete! 🎉")
                    }
                    Notif.showReminder(
                        this,
                        "$name: focus complete 🎉",
                        "Nice work — ${snapshot.focusTotalMin} focused minutes banked.",
                    )
                }
            } else if (snapshot.speechBubbles &&
                now - snapshot.focusStartedAt > 300_000L &&
                now - lastFocusBubbleAt >= 300_000L
            ) {
                // Gentle timer bubble, roughly every 5 minutes.
                lastFocusBubbleAt = now
                val remain = (snapshot.focusEndsAt - now).coerceAtLeast(0L)
                withContext(Dispatchers.Main) {
                    view?.speak("🎯 ${ScreenTimeTracker.formatCountdown(remain)} left")
                }
            }
        }

        // --- Break ---
        if (breakActive) {
            if (snapshot.breakEndsAt != breakGreetedEndsAt) {
                breakGreetedEndsAt = snapshot.breakEndsAt
                if (snapshot.speechBubbles) {
                    withContext(Dispatchers.Main) { view?.speak("Stretch a little 🌱") }
                }
            }
            if (now >= snapshot.breakEndsAt) {
                breakActive = false
                prefs.setBreakActive(false)
                if (snapshot.breakIsPomodoro) {
                    // Next pomodoro focus cycle (25 min).
                    val minutes = 25
                    prefs.setFocusActive(true)
                    prefs.setFocusEndsAt(now + minutes * 60_000L)
                    prefs.setFocusTotalMin(minutes)
                    prefs.setFocusPomodoro(true)
                    prefs.setFocusCycle(snapshot.focusCycle + 1)
                    prefs.setFocusStartedAt(now)
                    focusActive = true
                    lastFocusBubbleAt = 0L
                    if (snapshot.speechBubbles) {
                        withContext(Dispatchers.Main) { view?.speak("Back to focus 🎯") }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        view?.flashMood(Mood.HAPPY, 1500)
                        if (snapshot.speechBubbles) view?.speak("Back at it 💪")
                    }
                    Notif.showQuietReminder(this, "$name: break over", "Back at it 💪")
                }
            }
        }

        return ModeResult(focusActive, breakActive, dirty)
    }

    // ------------------------------------------------------------------
    // Phase 3: progression & gamification.
    // Everything rides the 5s tick (or a direct user tap) — no new loops,
    // no new wakeups. All state lives in Prefs: rotation/restart/reboot safe.
    // Rewards ONLY healthy behaviors; floors on energy/bond; no punishment.
    // ------------------------------------------------------------------

    /**
     * Advances progression for this tick. Returns true when [history] was
     * touched and needs saving.
     */
    private suspend fun handleProgression(
        snapshot: PrefsSnapshot,
        history: MutableList<DayRecord>,
        dayKey: String,
        todayMinutes: Int,
        now: Long,
        interactive: Boolean,
        inBedtime: Boolean,
        modes: ModeResult,
    ): Boolean {
        var dirty = false

        // 1. Day rollover: evaluate yesterday, reset daily counters, new missions.
        if (dayKey != snapshot.progDay) {
            if (handleDayRollover(snapshot, history, dayKey)) dirty = true
        }

        // 2. Count screen time after bedtime (12 ticks ~= 1 minute) for wind-down.
        if (inBedtime && interactive) {
            prefs.setAfterBedTicks(snapshot.afterBedTicks + 1)
        }

        // 3. Rest respected: screen went idle within 15 min of a RestDue firing.
        if (!interactive &&
            snapshot.restFiredAt > snapshot.restRewardedAt &&
            now - snapshot.restFiredAt <= 15 * 60_000L
        ) {
            prefs.setRestRewardedAt(snapshot.restFiredAt)
            val award = Awards.restRespected()
            bankXp(award.xp, award.coins, history, dayKey)
            applyMissionEvent("rest_respected", 1, history, dayKey, snapshot)
            dirty = true
            withContext(Dispatchers.Main) {
                if (snapshot.speechBubbles) view?.speak("Thanks for resting 🌱")
            }
        }

        // 4. Focus session just completed (manual or pomodoro cycle).
        if (snapshot.focusActive && !modes.focusActive) {
            val award = Awards.focusComplete(snapshot.focusPomodoro)
            bankXp(award.xp, award.coins, history, dayKey)
            prefs.incTotalFocusSessions()
            prefs.addTotalFocusMin(snapshot.focusTotalMin)
            prefs.addEnergy(6)
            prefs.addBond(8)
            applyMissionEvent("focus", 1, history, dayKey, snapshot)
            dirty = true
        }

        // 5. Break just completed (manual or pomodoro).
        if (snapshot.breakActive && !modes.breakActive) {
            val award = Awards.breakComplete()
            bankXp(award.xp, award.coins, history, dayKey)
            prefs.incTotalBreaks()
            prefs.addEnergy(12)
            applyMissionEvent("break", 1, history, dayKey, snapshot)
            dirty = true
        }

        return dirty
    }

    /**
     * Midnight rollover: evaluates yesterday's healthy behaviors, applies the
     * gentle energy/bond rules, resets daily counters, and deals fresh missions.
     * Only the most recent full day is evaluated; missed days are simply skipped.
     */
    private suspend fun handleDayRollover(
        snapshot: PrefsSnapshot,
        history: MutableList<DayRecord>,
        dayKey: String,
    ): Boolean {
        val isFirstRun = snapshot.progDay.isEmpty()
        val yesterdayKey = Prefs.dayKeyMinus(dayKey)
        val yScreenMin = history.find { it.date == yesterdayKey }?.screenMin ?: 0
        val goal = snapshot.dailyGoalMinutes

        var energy = snapshot.energy
        var bond = snapshot.bond

        if (!isFirstRun) {
            // Daily screen goal met — needs real data (0 = no usage access).
            if (yScreenMin in 1..goal) {
                val award = Awards.goalMet()
                bankXp(award.xp, award.coins, history, dayKey)
                prefs.incGoalDays()
                applyMissionEvent("goal_met", 1, history, dayKey, snapshot)
            }
            // Wind-down: under 15 min of screen after bedtime → full recovery.
            // (Otherwise energy simply stays where it is — no punishment.)
            val afterBedMin = snapshot.afterBedTicks / 12
            if (afterBedMin < 15) {
                val award = Awards.windDown()
                bankXp(award.xp, award.coins, history, dayKey)
                prefs.incWindDownDays()
                applyMissionEvent("wind_down", 1, history, dayKey, snapshot)
                energy = 100
            }
            // Energy drain: -1 per 30 min beyond half the daily goal. Floor 25.
            val over = yScreenMin - goal / 2
            if (over > 0) {
                energy = (energy - over / 30).coerceAtLeast(Progression.ENERGY_FLOOR)
            }
            // Bond decay: -1 on days with zero taps. Floor 30 — never punishing.
            if (snapshot.tapsToday == 0) {
                bond = (bond - 1).coerceAtLeast(Progression.BOND_FLOOR)
            }
        }
        prefs.setEnergy(energy)
        prefs.setBond(bond)

        // Reset daily counters.
        prefs.setTapsToday(0)
        prefs.setXpToday(0)
        prefs.setProgDay(dayKey)
        prefs.setAfterBedTicks(0)

        // Fresh missions: no carryover, no penalty.
        prefs.setMissionsJson(Missions.save(Missions.pickForDate(dayKey)))
        prefs.setMissionsDay(dayKey)

        // Lifetime counters may have flipped an achievement (goal/wind-down days).
        checkAchievements(history, dayKey)
        return true
    }

    /**
     * Banks XP/coins atomically, updates today's history record, fires the
     * "xp" mission event, celebrates level-ups, and re-checks achievements.
     */
    private suspend fun bankXp(
        xp: Int,
        coins: Int,
        history: MutableList<DayRecord>,
        dayKey: String,
        fireXpMissionEvent: Boolean = true,
    ) {
        if (xp <= 0 && coins <= 0) return
        val (newXp, _) = prefs.addXpCoins(xp, coins)
        prefs.addXpToday(xp)
        History.updateDay(history, dayKey) { it.copy(xpEarned = it.xpEarned + xp) }
        val snap = prefs.snapshot()
        // Mission/achievement awards skip this to avoid event loops.
        if (fireXpMissionEvent) {
            applyMissionEvent("xp", xp, history, dayKey, snap)
        }
        val newLevel = Progression.levelFor(newXp)
        if (newLevel > snap.levelSeen) {
            celebrateLevelUp(newLevel, snap)
        }
        checkAchievements(history, dayKey)
    }

    private suspend fun celebrateLevelUp(newLevel: Int, snapshot: PrefsSnapshot) {
        prefs.setLevelSeen(newLevel)
        withContext(Dispatchers.Main) {
            view?.flashMood(Mood.EXCITED, 2500)
            if (snapshot.speechBubbles) {
                view?.speak("Level up! Sasi is now level $newLevel 🎉")
            }
        }
        Notif.showQuietReminder(
            this,
            "${snapshot.companionName}: level $newLevel! 🎉",
            "Your healthy habits made Sasi stronger.",
        )
    }

    /**
     * Applies a mission event ("focus", "break", "tap", "xp", ...). Newly
     * completed missions award XP/coins + bond exactly once, with a bubble.
     */
    private suspend fun applyMissionEvent(
        event: String,
        amount: Int,
        history: MutableList<DayRecord>,
        dayKey: String,
        snapshot: PrefsSnapshot,
    ) {
        var missions = Missions.load(snapshot.missionsJson).toMutableList()
        if (snapshot.missionsDay != dayKey || missions.isEmpty()) {
            // Self-heal: service was down at rollover, or first run after update.
            missions = Missions.pickForDate(dayKey).toMutableList()
        }
        val newlyDone = Missions.applyEvent(missions, event, amount)
        prefs.setMissionsJson(Missions.save(missions))
        prefs.setMissionsDay(dayKey)
        if (newlyDone.isEmpty()) return
        for (m in newlyDone) {
            val award = Awards.missionComplete()
            prefs.incMissionsDone()
            prefs.addBond(4)
            bankXp(award.xp, award.coins, history, dayKey, fireXpMissionEvent = false)
            val snap = prefs.snapshot()
            withContext(Dispatchers.Main) {
                view?.flashMood(Mood.HAPPY, 2000)
                if (snap.speechBubbles) {
                    view?.speak("Mission complete! 🎯 +${award.xp} XP")
                }
            }
        }
        checkAchievements(history, dayKey)
    }

    /**
     * Unlocks newly-earned achievements (bubble + quiet notification each).
     * Loops because achievement XP can chain-unlock further achievements;
     * every unlock is one-time (persisted set), so this always terminates.
     */
    private suspend fun checkAchievements(
        history: MutableList<DayRecord>,
        dayKey: String,
    ) {
        repeat(12) {
            val snap = prefs.snapshot()
            val unlocked = Achievements.loadUnlocked(snap.achievementsJson)
            val stats = LifetimeStats(
                totalFocusSessions = snap.totalFocusSessions,
                totalFocusMin = snap.totalFocusMin,
                totalBreaks = snap.totalBreaks,
                totalTaps = snap.totalTaps,
                missionsDone = snap.missionsDone,
                goalDays = snap.goalDays,
                windDownDays = snap.windDownDays,
                bond = snap.bond,
                level = Progression.levelFor(snap.xp),
            )
            val newly = Achievements.checkNewly(unlocked, stats)
            if (newly.isEmpty()) return
            prefs.setAchievementsJson(Achievements.saveUnlocked(unlocked))
            val award = Awards.achievementUnlock()
            val totalXp = award.xp * newly.size
            val (newXp, _) = prefs.addXpCoins(totalXp, award.coins * newly.size)
            prefs.addXpToday(totalXp)
            History.updateDay(history, dayKey) { it.copy(xpEarned = it.xpEarned + totalXp) }
            val fresh = prefs.snapshot()
            val newLevel = Progression.levelFor(newXp)
            if (newLevel > fresh.levelSeen) {
                celebrateLevelUp(newLevel, fresh)
            }
            for (def in newly) {
                withContext(Dispatchers.Main) {
                    if (fresh.speechBubbles) view?.speak("🏆 ${def.title}!")
                }
                Notif.showQuietReminder(this, "🏆 ${def.title}", def.desc)
            }
        }
    }

    /** Phase 3: a real tap on Sasi earns a little XP (capped daily). */
    private fun onTapReaction() {
        scope.launch {
            val snap = prefs.snapshot()
            if (snap.tapsToday >= Progression.MAX_TAPS_PER_DAY) return@launch
            val dayKey = Prefs.todayKey()
            prefs.incTapsToday()
            prefs.incTotalTaps()
            prefs.addBond(2)
            val history = History.load(snap.historyJson).toMutableList()
            val award = Awards.tap()
            bankXp(award.xp, award.coins, history, dayKey)
            applyMissionEvent("tap", 1, history, dayKey, prefs.snapshot())
            prefs.setHistoryJson(History.save(history))
        }
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
        companionView.onTapReaction = { onTapReaction() }
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
            ACTION_CANCEL_MODE -> {
                scope.launch {
                    prefs.setFocusActive(false)
                    prefs.setBreakActive(false)
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
