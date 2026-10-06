package com.sree.sasi.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "sasi_prefs")

data class PrefsSnapshot(
    val companionName: String,
    val companionEnabled: Boolean,
    val onboardingDone: Boolean,
    val restMinutes: Int,
    val bedtimeHour: Int,
    val bedtimeMinute: Int,
    val wakeHour: Int,
    val wakeMinute: Int,
    val dailyGoalMinutes: Int,
    val overlaySize: Int,
    val walkSpeed: Int,
    val colorTheme: Int,
    val continuousMinutes: Int,
    val firedDay: String,
    val firedKeys: Set<String>,
    // Phase 1: visibility + movement + interaction prefs
    val visibilityState: String,
    val hiddenUntilMillis: Long,
    val movementMode: Int,
    val movementFrequency: Int,
    val posX: Float,
    val posY: Float,
    val tapReactions: Boolean,
    val speechBubbles: Boolean,
    val hapticFeedback: Boolean,
    val lockHideArmed: Boolean,
    // Phase 2: focus/break timers + history
    val focusActive: Boolean,
    val focusEndsAt: Long,
    val focusTotalMin: Int,
    val focusPomodoro: Boolean,
    val focusCycle: Int,
    val focusStartedAt: Long,
    val breakActive: Boolean,
    val breakEndsAt: Long,
    val breakTotalSec: Int,
    val breakIsPomodoro: Boolean,
    val historyJson: String,
    val historyDay: String,
)

class Prefs(private val context: Context) {

    companion object {
        private val KEY_NAME = stringPreferencesKey("companion_name")
        private val KEY_ENABLED = booleanPreferencesKey("companion_enabled")
        private val KEY_ONBOARDING = booleanPreferencesKey("onboarding_done")
        private val KEY_REST_MINUTES = intPreferencesKey("rest_minutes")
        private val KEY_BEDTIME_HOUR = intPreferencesKey("bedtime_hour")
        private val KEY_BEDTIME_MINUTE = intPreferencesKey("bedtime_minute")
        private val KEY_WAKE_HOUR = intPreferencesKey("wake_hour")
        private val KEY_WAKE_MINUTE = intPreferencesKey("wake_minute")
        private val KEY_DAILY_GOAL = intPreferencesKey("daily_goal_minutes")
        private val KEY_OVERLAY_SIZE = intPreferencesKey("overlay_size")
        private val KEY_WALK_SPEED = intPreferencesKey("walk_speed")
        private val KEY_COLOR_THEME = intPreferencesKey("color_theme")
        private val KEY_CONTINUOUS_MINUTES = intPreferencesKey("continuous_minutes")
        private val KEY_FIRED_DAY = stringPreferencesKey("fired_day")
        private val KEY_FIRED_KEYS = stringSetPreferencesKey("fired_keys")
        private val KEY_VISIBILITY = stringPreferencesKey("visibility_state")
        private val KEY_HIDDEN_UNTIL = longPreferencesKey("hidden_until_millis")
        private val KEY_MOVEMENT_MODE = intPreferencesKey("movement_mode")
        private val KEY_MOVEMENT_FREQ = intPreferencesKey("movement_frequency")
        private val KEY_POS_X = floatPreferencesKey("pos_x")
        private val KEY_POS_Y = floatPreferencesKey("pos_y")
        private val KEY_TAP_REACTIONS = booleanPreferencesKey("tap_reactions")
        private val KEY_SPEECH_BUBBLES = booleanPreferencesKey("speech_bubbles")
        private val KEY_HAPTIC = booleanPreferencesKey("haptic_feedback")
        private val KEY_LOCK_ARMED = booleanPreferencesKey("lock_hide_armed")
        private val KEY_FOCUS_ACTIVE = booleanPreferencesKey("focus_active")
        private val KEY_FOCUS_ENDS_AT = longPreferencesKey("focus_ends_at")
        private val KEY_FOCUS_TOTAL_MIN = intPreferencesKey("focus_total_min")
        private val KEY_FOCUS_POMODORO = booleanPreferencesKey("focus_pomodoro")
        private val KEY_FOCUS_CYCLE = intPreferencesKey("focus_cycle")
        private val KEY_FOCUS_STARTED_AT = longPreferencesKey("focus_started_at")
        private val KEY_BREAK_ACTIVE = booleanPreferencesKey("break_active")
        private val KEY_BREAK_ENDS_AT = longPreferencesKey("break_ends_at")
        private val KEY_BREAK_TOTAL_SEC = intPreferencesKey("break_total_sec")
        private val KEY_BREAK_POMODORO = booleanPreferencesKey("break_is_pomodoro")
        private val KEY_HISTORY_JSON = stringPreferencesKey("history_json")
        private val KEY_HISTORY_DAY = stringPreferencesKey("history_day")

        fun todayKey(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    val companionName: Flow<String> =
        context.dataStore.data.map { it[KEY_NAME] ?: "Sasi" }
    val companionEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_ENABLED] ?: false }
    val onboardingDone: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_ONBOARDING] ?: false }
    val restMinutes: Flow<Int> =
        context.dataStore.data.map { it[KEY_REST_MINUTES] ?: 50 }
    val bedtimeHour: Flow<Int> =
        context.dataStore.data.map { it[KEY_BEDTIME_HOUR] ?: 23 }
    val bedtimeMinute: Flow<Int> =
        context.dataStore.data.map { it[KEY_BEDTIME_MINUTE] ?: 0 }
    val wakeHour: Flow<Int> =
        context.dataStore.data.map { it[KEY_WAKE_HOUR] ?: 7 }
    val wakeMinute: Flow<Int> =
        context.dataStore.data.map { it[KEY_WAKE_MINUTE] ?: 0 }
    val dailyGoalMinutes: Flow<Int> =
        context.dataStore.data.map { it[KEY_DAILY_GOAL] ?: 240 }
    val overlaySize: Flow<Int> =
        context.dataStore.data.map { it[KEY_OVERLAY_SIZE] ?: 1 }
    val walkSpeed: Flow<Int> =
        context.dataStore.data.map { it[KEY_WALK_SPEED] ?: 1 }
    val colorTheme: Flow<Int> =
        context.dataStore.data.map { it[KEY_COLOR_THEME] ?: 0 }
    val continuousMinutes: Flow<Int> =
        context.dataStore.data.map { it[KEY_CONTINUOUS_MINUTES] ?: 0 }
    val firedKeys: Flow<Set<String>> =
        context.dataStore.data.map { it[KEY_FIRED_KEYS] ?: emptySet() }
    val visibilityState: Flow<String> =
        context.dataStore.data.map { it[KEY_VISIBILITY] ?: "VISIBLE" }
    val hiddenUntilMillis: Flow<Long> =
        context.dataStore.data.map { it[KEY_HIDDEN_UNTIL] ?: 0L }
    val movementMode: Flow<Int> =
        context.dataStore.data.map { it[KEY_MOVEMENT_MODE] ?: 0 }
    val movementFrequency: Flow<Int> =
        context.dataStore.data.map { it[KEY_MOVEMENT_FREQ] ?: 1 }
    val posX: Flow<Float> =
        context.dataStore.data.map { it[KEY_POS_X] ?: -1f }
    val posY: Flow<Float> =
        context.dataStore.data.map { it[KEY_POS_Y] ?: -1f }
    val tapReactions: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_TAP_REACTIONS] ?: true }
    val speechBubbles: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_SPEECH_BUBBLES] ?: true }
    val hapticFeedback: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_HAPTIC] ?: true }
    val lockHideArmed: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_LOCK_ARMED] ?: false }
    val focusActive: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_FOCUS_ACTIVE] ?: false }
    val focusEndsAt: Flow<Long> =
        context.dataStore.data.map { it[KEY_FOCUS_ENDS_AT] ?: 0L }
    val focusTotalMin: Flow<Int> =
        context.dataStore.data.map { it[KEY_FOCUS_TOTAL_MIN] ?: 25 }
    val focusPomodoro: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_FOCUS_POMODORO] ?: false }
    val focusCycle: Flow<Int> =
        context.dataStore.data.map { it[KEY_FOCUS_CYCLE] ?: 1 }
    val focusStartedAt: Flow<Long> =
        context.dataStore.data.map { it[KEY_FOCUS_STARTED_AT] ?: 0L }
    val breakActive: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_BREAK_ACTIVE] ?: false }
    val breakEndsAt: Flow<Long> =
        context.dataStore.data.map { it[KEY_BREAK_ENDS_AT] ?: 0L }
    val breakTotalSec: Flow<Int> =
        context.dataStore.data.map { it[KEY_BREAK_TOTAL_SEC] ?: 120 }
    val breakIsPomodoro: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_BREAK_POMODORO] ?: false }
    val historyJson: Flow<String> =
        context.dataStore.data.map { it[KEY_HISTORY_JSON] ?: "[]" }
    val historyDay: Flow<String> =
        context.dataStore.data.map { it[KEY_HISTORY_DAY] ?: "" }

    suspend fun setCompanionName(value: String) {
        context.dataStore.edit { it[KEY_NAME] = value }
    }

    suspend fun setCompanionEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun setOnboardingDone(value: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDING] = value }
    }

    suspend fun setRestMinutes(value: Int) {
        context.dataStore.edit { it[KEY_REST_MINUTES] = value.coerceIn(20, 120) }
    }

    suspend fun setBedtime(hour: Int, minute: Int) {
        context.dataStore.edit {
            it[KEY_BEDTIME_HOUR] = hour.coerceIn(0, 23)
            it[KEY_BEDTIME_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setWakeTime(hour: Int, minute: Int) {
        context.dataStore.edit {
            it[KEY_WAKE_HOUR] = hour.coerceIn(0, 23)
            it[KEY_WAKE_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setDailyGoalMinutes(value: Int) {
        context.dataStore.edit { it[KEY_DAILY_GOAL] = value.coerceIn(30, 960) }
    }

    suspend fun setOverlaySize(value: Int) {
        context.dataStore.edit { it[KEY_OVERLAY_SIZE] = value.coerceIn(0, 2) }
    }

    suspend fun setWalkSpeed(value: Int) {
        context.dataStore.edit { it[KEY_WALK_SPEED] = value.coerceIn(0, 2) }
    }

    suspend fun setColorTheme(value: Int) {
        context.dataStore.edit { it[KEY_COLOR_THEME] = value.coerceIn(0, 3) }
    }

    suspend fun setContinuousMinutes(value: Int) {
        context.dataStore.edit { it[KEY_CONTINUOUS_MINUTES] = value.coerceAtLeast(0) }
    }

    suspend fun setVisibilityState(value: String) {
        context.dataStore.edit { it[KEY_VISIBILITY] = value }
    }

    suspend fun setHiddenUntilMillis(value: Long) {
        context.dataStore.edit { it[KEY_HIDDEN_UNTIL] = value.coerceAtLeast(0L) }
    }

    suspend fun setMovementMode(value: Int) {
        context.dataStore.edit { it[KEY_MOVEMENT_MODE] = value.coerceIn(0, 3) }
    }

    suspend fun setMovementFrequency(value: Int) {
        context.dataStore.edit { it[KEY_MOVEMENT_FREQ] = value.coerceIn(0, 2) }
    }

    suspend fun setPos(x: Float, y: Float) {
        context.dataStore.edit {
            it[KEY_POS_X] = x
            it[KEY_POS_Y] = y
        }
    }

    suspend fun setTapReactions(value: Boolean) {
        context.dataStore.edit { it[KEY_TAP_REACTIONS] = value }
    }

    suspend fun setSpeechBubbles(value: Boolean) {
        context.dataStore.edit { it[KEY_SPEECH_BUBBLES] = value }
    }

    suspend fun setHapticFeedback(value: Boolean) {
        context.dataStore.edit { it[KEY_HAPTIC] = value }
    }

    suspend fun setLockHideArmed(value: Boolean) {
        context.dataStore.edit { it[KEY_LOCK_ARMED] = value }
    }
    suspend fun setFocusActive(value: Boolean) {
        context.dataStore.edit { it[KEY_FOCUS_ACTIVE] = value }
    }

    suspend fun setFocusEndsAt(value: Long) {
        context.dataStore.edit { it[KEY_FOCUS_ENDS_AT] = value.coerceAtLeast(0L) }
    }

    suspend fun setFocusTotalMin(value: Int) {
        context.dataStore.edit { it[KEY_FOCUS_TOTAL_MIN] = value.coerceIn(1, 480) }
    }

    suspend fun setFocusPomodoro(value: Boolean) {
        context.dataStore.edit { it[KEY_FOCUS_POMODORO] = value }
    }

    suspend fun setFocusCycle(value: Int) {
        context.dataStore.edit { it[KEY_FOCUS_CYCLE] = value.coerceAtLeast(1) }
    }

    suspend fun setFocusStartedAt(value: Long) {
        context.dataStore.edit { it[KEY_FOCUS_STARTED_AT] = value.coerceAtLeast(0L) }
    }

    suspend fun setBreakActive(value: Boolean) {
        context.dataStore.edit { it[KEY_BREAK_ACTIVE] = value }
    }

    suspend fun setBreakEndsAt(value: Long) {
        context.dataStore.edit { it[KEY_BREAK_ENDS_AT] = value.coerceAtLeast(0L) }
    }

    suspend fun setBreakTotalSec(value: Int) {
        context.dataStore.edit { it[KEY_BREAK_TOTAL_SEC] = value.coerceIn(5, 7200) }
    }

    suspend fun setBreakIsPomodoro(value: Boolean) {
        context.dataStore.edit { it[KEY_BREAK_POMODORO] = value }
    }

    suspend fun setHistoryJson(value: String) {
        context.dataStore.edit { it[KEY_HISTORY_JSON] = value }
    }

    suspend fun setHistoryDay(value: String) {
        context.dataStore.edit { it[KEY_HISTORY_DAY] = value }
    }

    /** Records a fired reminder key, rolling the dedupe set over at day change. */
    suspend fun markFired(key: String) {
        val today = todayKey()
        context.dataStore.edit { prefs ->
            if (prefs[KEY_FIRED_DAY] != today) {
                prefs[KEY_FIRED_DAY] = today
                prefs[KEY_FIRED_KEYS] = emptySet()
            }
            prefs[KEY_FIRED_KEYS] = (prefs[KEY_FIRED_KEYS] ?: emptySet()) + key
        }
    }

    suspend fun snapshot(): PrefsSnapshot {
        val data = context.dataStore.data.first()
        return PrefsSnapshot(
            companionName = data[KEY_NAME] ?: "Sasi",
            companionEnabled = data[KEY_ENABLED] ?: false,
            onboardingDone = data[KEY_ONBOARDING] ?: false,
            restMinutes = data[KEY_REST_MINUTES] ?: 50,
            bedtimeHour = data[KEY_BEDTIME_HOUR] ?: 23,
            bedtimeMinute = data[KEY_BEDTIME_MINUTE] ?: 0,
            wakeHour = data[KEY_WAKE_HOUR] ?: 7,
            wakeMinute = data[KEY_WAKE_MINUTE] ?: 0,
            dailyGoalMinutes = data[KEY_DAILY_GOAL] ?: 240,
            overlaySize = data[KEY_OVERLAY_SIZE] ?: 1,
            walkSpeed = data[KEY_WALK_SPEED] ?: 1,
            colorTheme = data[KEY_COLOR_THEME] ?: 0,
            continuousMinutes = data[KEY_CONTINUOUS_MINUTES] ?: 0,
            firedDay = data[KEY_FIRED_DAY] ?: "",
            firedKeys = data[KEY_FIRED_KEYS] ?: emptySet(),
            visibilityState = data[KEY_VISIBILITY] ?: "VISIBLE",
            hiddenUntilMillis = data[KEY_HIDDEN_UNTIL] ?: 0L,
            movementMode = data[KEY_MOVEMENT_MODE] ?: 0,
            movementFrequency = data[KEY_MOVEMENT_FREQ] ?: 1,
            posX = data[KEY_POS_X] ?: -1f,
            posY = data[KEY_POS_Y] ?: -1f,
            tapReactions = data[KEY_TAP_REACTIONS] ?: true,
            speechBubbles = data[KEY_SPEECH_BUBBLES] ?: true,
            hapticFeedback = data[KEY_HAPTIC] ?: true,
            lockHideArmed = data[KEY_LOCK_ARMED] ?: false,
            focusActive = data[KEY_FOCUS_ACTIVE] ?: false,
            focusEndsAt = data[KEY_FOCUS_ENDS_AT] ?: 0L,
            focusTotalMin = data[KEY_FOCUS_TOTAL_MIN] ?: 25,
            focusPomodoro = data[KEY_FOCUS_POMODORO] ?: false,
            focusCycle = data[KEY_FOCUS_CYCLE] ?: 1,
            focusStartedAt = data[KEY_FOCUS_STARTED_AT] ?: 0L,
            breakActive = data[KEY_BREAK_ACTIVE] ?: false,
            breakEndsAt = data[KEY_BREAK_ENDS_AT] ?: 0L,
            breakTotalSec = data[KEY_BREAK_TOTAL_SEC] ?: 120,
            breakIsPomodoro = data[KEY_BREAK_POMODORO] ?: false,
            historyJson = data[KEY_HISTORY_JSON] ?: "[]",
            historyDay = data[KEY_HISTORY_DAY] ?: "",
        )
    }
}
