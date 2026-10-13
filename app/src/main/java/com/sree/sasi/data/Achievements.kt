package com.sree.sasi.data

import org.json.JSONArray

/**
 * Phase 3: one-time achievements for healthy milestones. Unlocking is purely
 * additive — nothing is ever taken away, and there is no decay or punishment.
 * Unlocked ids persist as a JSON array; lifetime counters live in [Prefs].
 */
data class LifetimeStats(
    val totalFocusSessions: Int = 0,
    val totalFocusMin: Int = 0,
    val totalBreaks: Int = 0,
    val totalTaps: Int = 0,
    val missionsDone: Int = 0,
    val goalDays: Int = 0,
    val windDownDays: Int = 0,
    val bond: Int = Progression.BOND_START,
    val level: Int = 1,
)

data class AchievementDef(
    val id: String,
    val title: String,
    val desc: String,
    val check: (LifetimeStats) -> Boolean,
)

object Achievements {

    val ALL = listOf(
        AchievementDef(
            "first_focus", "First Focus",
            "Completed your first focus session",
            { it.totalFocusSessions >= 1 },
        ),
        AchievementDef(
            "focus_5", "Getting Focused",
            "Completed 5 focus sessions",
            { it.totalFocusSessions >= 5 },
        ),
        AchievementDef(
            "focus_100min", "Deep Worker",
            "Banked 100 total focus minutes",
            { it.totalFocusMin >= 100 },
        ),
        AchievementDef(
            "break_10", "Break Taker",
            "Taken 10 healthy breaks",
            { it.totalBreaks >= 10 },
        ),
        AchievementDef(
            "taps_100", "Best Friends",
            "Said hi to Sasi 100 times",
            { it.totalTaps >= 100 },
        ),
        AchievementDef(
            "bond_80", "Close Bond",
            "Reached 80 bond with Sasi",
            { it.bond >= 80 },
        ),
        AchievementDef(
            "level_5", "Rising Star",
            "Reached level 5",
            { it.level >= 5 },
        ),
        AchievementDef(
            "goal_3days", "Balanced",
            "Met your screen-time goal on 3 days",
            { it.goalDays >= 3 },
        ),
        AchievementDef(
            "wind_down_3", "Good Sleeper",
            "Wound down nicely on 3 nights",
            { it.windDownDays >= 3 },
        ),
        AchievementDef(
            "mission_10", "Mission Machine",
            "Completed 10 daily missions",
            { it.missionsDone >= 10 },
        ),
    )

    fun defFor(id: String): AchievementDef? = ALL.find { it.id == id }

    fun loadUnlocked(json: String): MutableSet<String> {
        val out = mutableSetOf<String>()
        if (json.isBlank()) return out
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (defFor(id) != null) out += id
            }
        } catch (e: Exception) {
            return mutableSetOf()
        }
        return out
    }

    fun saveUnlocked(ids: Set<String>): String =
        JSONArray(ids.filter { defFor(it) != null }).toString()

    /**
     * Adds every newly-earned achievement to [unlocked] and returns the defs.
     * One-time by construction: ids already in the set are never returned.
     */
    fun checkNewly(
        unlocked: MutableSet<String>,
        stats: LifetimeStats,
    ): List<AchievementDef> {
        val newly = ALL.filter { it.id !in unlocked && it.check(stats) }
        newly.forEach { unlocked += it.id }
        return newly
    }
}
