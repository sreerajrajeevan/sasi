package com.sree.sasi.screentime

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.os.Process
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
     * Used for the web-carpet intervention (threshold configurable in Settings).
     */
    var singleAppPackage: String? = null
        private set
    var singleAppMinutes: Int = 0
        private set
    private var singleAppStartMillis: Long = 0L

    private var continuousMillis: Long = 0L
    private var wasInteractive: Boolean = false

    private var lastElapsedMillis: Long? = null

    fun tick(
        isInteractive: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        elapsedMillis: Long = System.nanoTime() / 1_000_000L,
    ) {
        if (isInteractive) {
            if (!wasInteractive) {
                // New session: screen just turned on.
                sessionStartMillis = nowMillis
                continuousMillis = 0L
            }
            if (wasInteractive) {
                continuousMillis += (elapsedMillis - (lastElapsedMillis ?: elapsedMillis))
                    .coerceAtLeast(0L)
            }
        } else if (wasInteractive) {
            // Screen turned off: session ends, continuous counter resets.
            continuousMillis = 0L
        }
        lastElapsedMillis = elapsedMillis
        wasInteractive = isInteractive
        continuousMinutes = (continuousMillis / 60_000L).toInt()
    }

    /**
     * Tracks how long a single app has been in the foreground. Call on each
     * tick with the current foreground package (null if unknown). Resets when
     * the app changes or the screen turns off.
     */
    fun tickForegroundApp(foregroundPackage: String?, isInteractive: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        if (!isInteractive) {
            singleAppPackage = null
            singleAppMinutes = 0
            singleAppStartMillis = 0L
            return
        }
        if (foregroundPackage == null) {
            // No foreground event in the query window — this happens when the
            // user has been on the SAME app for a while (only one
            // MOVE_TO_FOREGROUND event, from when they opened it). Don't reset;
            // keep accumulating with the previously tracked app.
            if (singleAppPackage != null && singleAppStartMillis > 0L) {
                singleAppMinutes = ((nowMillis - singleAppStartMillis) / 60_000L).toInt()
            }
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
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            return appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
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

        /**
         * Best guess at the current foreground app package, or null if
         * unavailable. Uses UsageEvents (requires PACKAGE_USAGE_STATS).
         *
         * Falls back to the most-recently-used app when no foreground event
         * exists in the short window — e.g. the service (re)started while the
         * user was already inside an app. Without this the single-app timer
         * could never bootstrap and the web carpet would never fire.
         */
        fun getForegroundApp(context: Context): String? {
            queryForegroundEvent(context)?.let { return it }
            return queryMostRecentApp(context)
        }

        private fun queryForegroundEvent(context: Context): String? {
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

        private fun queryMostRecentApp(context: Context): String? {
            return try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val end = System.currentTimeMillis()
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    end - 60_000L,
                    end,
                )
                stats.filter { it.lastTimeUsed >= end - 60_000L }
                    .maxByOrNull { it.lastTimeUsed }
                    ?.packageName
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
    }
}
