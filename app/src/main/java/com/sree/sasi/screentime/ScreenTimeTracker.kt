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
            continuousMillis = 0L
        }
        wasInteractive = isInteractive
        continuousMinutes = (continuousMillis / 60_000L).toInt()
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

        fun formatMinutes(totalMinutes: Long): String {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }
    }
}
