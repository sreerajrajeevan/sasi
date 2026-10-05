package com.sree.sasi.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        )
    }
}
