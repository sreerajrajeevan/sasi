package com.sree.sasi.screentime

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/**
 * Tracks screen time using two signals:
 * - [getTodayScreenMinutes]: total foreground time today from UsageStatsManager
 *   (requires PACKAGE_USAGE_STATS to be granted by the user; returns 0 otherwise).
 * - [tick]: accumulates a "continuous session" while the screen is interactive,
 *   resetting when the screen turns off.
 */
class ScreenTimeTracker {

    var continuousMinutes: Int = 0
        private set

    var sessionStartMillis: Long = System.currentTimeMillis()
        private set

    /**
     * Single-app watch: how long the current foreground app has been continuously
     * in the foreground (resets when the app changes or screen turns off).
     * Used for the 30-minute single-app web-carpet intervention.
     */
    var singleAppPackage: String? = null
        private set
    var singleAppMinutes: Int = 0
        private set
    private var singleAppStartMillis: Long = 0L

    /**
     * The most recently finished screen session as (startMillis, durationMillis),
     * set when the screen turns off. Consume with [takeEndedSession].
     */
    var lastEndedSession: Pair<Long, Long>? = null
        private set

    private var continuousMillis: Long = 0L
    private var wasInteractive: Boolean = false

    fun tick(isInteractive: Boolean, tickMillis: Long = 5000L) {
        if (isInteractive) {
            if (!wasInteractive) {
                // New session: screen just turned on.
                sessionStartMillis = System.currentTimeMillis()
                continuousMillis = 0L
            }
            continuousMillis += tickMillis
        } else if (wasInteractive) {
            // Screen turned off: session ends, continuous counter resets.
            lastEndedSession = sessionStartMillis to continuousMillis
            continuousMillis = 0L
        }
        wasInteractive = isInteractive
        continuousMinutes = (continuousMillis / 60_000L).toInt()
    }

    /**
     * Tracks how long a single app has been in the foreground. Call on each
     * tick with the current foreground package (null if unknown). Resets when
     * the app changes or the screen turns off.
     */
    fun tickForegroundApp(foregroundPackage: String?, isInteractive: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        if (!isInteractive || foregroundPackage == null) {
            singleAppPackage = null
            singleAppMinutes = 0
            singleAppStartMillis = 0L
            return
        }
        if (foregroundPackage != singleAppPackage) {
            // App changed: start a new single-app session.
            singleAppPackage = foregroundPackage
            singleAppStartMillis = nowMillis
            singleAppMinutes = 0
        } else {
            singleAppMinutes = ((nowMillis - singleAppStartMillis) / 60_000L).toInt()
        }
    }

    /** Returns and clears the most recently ended session, if any. */
    fun takeEndedSession(): Pair<Long, Long>? {
        val ended = lastEndedSession
        lastEndedSession = null
        return ended
    }

    fun getTodayScreenMinutes(context: Context): Long {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        val stats = try {
            usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startOfDay,
                now,
            )
        } catch (e: Exception) {
            null
        }
        if (stats.isNullOrEmpty()) return 0L
        return stats.sumOf { it.totalTimeInForeground } / 60_000L
    }

    companion object {
        /** True when the app can actually read usage stats (permission granted). */
        fun hasUsageAccess(context: Context): Boolean {
            val usageStatsManager =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = try {
                usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 60_000L,
                    now,
                )
            } catch (e: Exception) {
                null
            }
            return !stats.isNullOrEmpty()
        }

        /**
         * Returns the current foreground app package, or null if unavailable.
         * Uses UsageEvents (requires PACKAGE_USAGE_STATS).
         */
        fun getForegroundApp(context: Context): String? {
            return try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val end = System.currentTimeMillis()
                val events = usm.queryEvents(end - 10_000L, end)
                var lastPackage: String? = null
                val event = android.app.usage.UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        lastPackage = event.packageName
                    }
                }
                lastPackage
            } catch (e: Exception) {
                null
            }
        }

        /** Human-readable app label, or the package name if unavailable. */
        fun getAppLabel(context: Context, packageName: String): String {
            return try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) {
                packageName
            }
        }

        fun formatMinutes(totalMinutes: Long): String {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }

        /** "12:34" or "1:02:03" for timer countdowns. */
        fun formatCountdown(millis: Long): String {
            val totalSec = (millis / 1000).toInt().coerceAtLeast(0)
            val hours = totalSec / 3600
            val minutes = (totalSec % 3600) / 60
            val seconds = totalSec % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
    }
}
