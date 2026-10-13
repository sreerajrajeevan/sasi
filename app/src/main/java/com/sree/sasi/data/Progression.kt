package com.sree.sasi.data

/**
 * Phase 3: progression & gamification — the "raise a healthy Sasi" loop.
 *
 * CORE RULES (non-negotiable, enforced by every caller):
 * - Only HEALTHY behaviors earn rewards. Raw screen time NEVER earns anything.
 * - No punishment, ever: energy and bond have floors, recover quickly, Sasi
 *   never dies, no streak is ever reset punitively, no negative feedback.
 * - Anti-farming caps on everything countable (taps, etc.).
 *
 * Everything is local (DataStore + JSON), no network, no new dependencies.
 */
data class PetStats(
    val xp: Int = 0,
    val coins: Int = 0,
    /** 0..100, but never below [Progression.ENERGY_FLOOR] — no punishment. */
    val energy: Int = Progression.ENERGY_START,
    /** 0..100, but never below [Progression.BOND_FLOOR] — no punishment. */
    val bond: Int = Progression.BOND_START,
)

object Progression {

    const val ENERGY_START = 80
    const val ENERGY_FLOOR = 25
    const val BOND_START = 40
    const val BOND_FLOOR = 30

    /** Anti-farming: taps only count this many times per day. */
    const val MAX_TAPS_PER_DAY = 10

    /**
     * Cumulative XP required to REACH level n:
     * L1 = 0, L2 = 100, L3 = 300, L4 = 600, L5 = 1000, L6 = 1500, ...
     */
    fun xpForLevel(n: Int): Int = if (n <= 1) 0 else 100 * n * (n - 1) / 2

    fun levelFor(xp: Int): Int {
        var level = 1
        while (xp >= xpForLevel(level + 1)) level++
        return level
    }

    /** 0..1 progress through the current level, for the XP bar. */
    fun levelProgress(xp: Int): Float {
        val level = levelFor(xp)
        val base = xpForLevel(level)
        val next = xpForLevel(level + 1)
        return if (next <= base) 1f
        else ((xp - base).toFloat() / (next - base)).coerceIn(0f, 1f)
    }
}

/** One reward grant: XP plus coins. */
data class Award(val xp: Int, val coins: Int)

/**
 * The full reward table. Documented here so the design stays honest:
 * nothing below may ever reward raw screen time.
 *
 * - Focus session complete: +50 XP, +10 coins (pomodoro auto-cycle: +25 XP, +5 coins)
 * - Break complete: +15 XP, +3 coins
 * - Rest respected (screen goes idle within 15 min after a RestDue fired): +20 XP, +5 coins
 * - Daily screen goal met (at midnight rollover, 0 < yesterdayMinutes <= dailyGoal): +40 XP, +10 coins
 * - Wind-down (screen minutes after bedtime < 15): +25 XP, +5 coins (evaluated at rollover)
 * - Tap interaction with Sasi: +2 XP, no coins, capped at 10 taps/day
 * - Mission complete: +30 XP, +15 coins
 * - Achievement unlock: +40 XP, +20 coins
 */
object Awards {

    fun focusComplete(pomodoro: Boolean): Award =
        if (pomodoro) Award(25, 5) else Award(50, 10)

    fun breakComplete(): Award = Award(15, 3)

    fun restRespected(): Award = Award(20, 5)

    fun goalMet(): Award = Award(40, 10)

    fun windDown(): Award = Award(25, 5)

    fun tap(): Award = Award(2, 0)

    fun missionComplete(): Award = Award(30, 15)

    fun achievementUnlock(): Award = Award(40, 20)
}
