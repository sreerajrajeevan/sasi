package com.sree.sasi.reminders

import java.util.Calendar

/**
 * Pure reminder logic. Given a snapshot of prefs, tracker state and the clock,
 * [evaluate] returns at most one [Reminder] per call. Dedupe keys are produced
 * by [keyFor]; the caller stores fired keys (see Prefs.markFired) and passes
 * them back in [Input.firedKeys].
 */
class ReminderEngine {

    sealed interface Reminder {
        data class RestDue(val name: String, val minutes: Int) : Reminder
        data class BedtimeNudge(val name: String, val bedtimeLabel: String) : Reminder
        data class WakeGreeting(val name: String) : Reminder
        data class Milestone(val name: String, val hours: Int) : Reminder
        data class IdleChatter(val name: String, val line: String) : Reminder

        fun title(): String = when (this) {
            is RestDue -> "$name says take a break"
            is BedtimeNudge -> "Sleepy time"
            is WakeGreeting -> "Good morning!"
            is Milestone -> "$hours hours of screen time"
            is IdleChatter -> name
        }

        fun text(): String = when (this) {
            is RestDue ->
                "$name: You've been scrolling for $minutes min \u2014 rest those eyes a little?"
            is BedtimeNudge ->
                "It's past $bedtimeLabel \u2014 sleepy time. Let's wind down together. \u2014 $name"
            is WakeGreeting ->
                "Good morning! $name missed you."
            is Milestone ->
                "You've hit $hours hours of screen time today. $name believes in balance!"
            is IdleChatter -> line
        }

        fun bubble(): String = when (this) {
            is RestDue -> "Rest those eyes? 🥺"
            is BedtimeNudge -> "Sleepy time… 💤"
            is WakeGreeting -> "Good morning! ☀️"
            is Milestone -> "$hours h already? Balance! ⚖️"
            is IdleChatter -> line
        }
    }

    data class Input(
        val nowMillis: Long,
        val name: String,
        val isInteractive: Boolean,
        val continuousMinutes: Int,
        val sessionStartMillis: Long,
        val todayScreenMinutes: Int,
        val restIntervalMinutes: Int,
        val bedtimeHour: Int,
        val bedtimeMinute: Int,
        val wakeHour: Int,
        val wakeMinute: Int,
        val tickCount: Int,
        val dayKey: String,
        val firedKeys: Set<String>,
    )

    fun evaluate(input: Input): Reminder? {
        val dayKey = input.dayKey
        val fired = input.firedKeys

        // 1. Wake greeting: once per day, within 30 minutes after wake time.
        val wakeStart = atTimeMillis(input.nowMillis, input.wakeHour, input.wakeMinute)
        if (input.nowMillis in wakeStart..(wakeStart + 30 * 60_000L)) {
            if ("wake:$dayKey" !in fired) return Reminder.WakeGreeting(input.name)
        }

        // 2. Bedtime nudge: once per day, during the first 45 minutes of the bedtime window.
        if (isBedtime(
                input.nowMillis,
                input.bedtimeHour,
                input.bedtimeMinute,
                input.wakeHour,
                input.wakeMinute,
            )
        ) {
            val bedStart = lastBedtimeStart(input.nowMillis, input.bedtimeHour, input.bedtimeMinute)
            if (input.nowMillis - bedStart < 45 * 60_000L && "bed:$dayKey" !in fired) {
                val label = "%02d:%02d".format(input.bedtimeHour, input.bedtimeMinute)
                return Reminder.BedtimeNudge(input.name, label)
            }
        }

        // 3. Rest reminder: once per continuous screen session.
        if (input.isInteractive && input.continuousMinutes >= input.restIntervalMinutes) {
            if ("rest:${input.sessionStartMillis}" !in fired) {
                return Reminder.RestDue(input.name, input.continuousMinutes)
            }
        }

        // 4. Screen-time milestones at 2h / 4h / 6h, once per day each.
        for (hours in listOf(6, 4, 2)) {
            if (input.todayScreenMinutes >= hours * 60 && "ms:$hours:$dayKey" !in fired) {
                return Reminder.Milestone(input.name, hours)
            }
        }

        // 5. Idle chatter: a cute line every ~3 minutes of active use (5s ticks).
        if (input.isInteractive && input.tickCount % 36 == 0) {
            return Reminder.IdleChatter(input.name, IDLE_LINES.random())
        }

        return null
    }

    companion object {
        /** Dedupe key for a reminder, or null when the reminder should not be deduped. */
        fun keyFor(
            reminder: Reminder,
            dayKey: String,
            sessionStartMillis: Long,
            tickCount: Int,
        ): String? = when (reminder) {
            is Reminder.WakeGreeting -> "wake:$dayKey"
            is Reminder.BedtimeNudge -> "bed:$dayKey"
            is Reminder.RestDue -> "rest:$sessionStartMillis"
            is Reminder.Milestone -> "ms:${reminder.hours}:$dayKey"
            is Reminder.IdleChatter -> null
        }

        fun atTimeMillis(nowMillis: Long, hour: Int, minute: Int): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }

        /** True when [nowMillis] falls inside the bedtime..wake window (handles overnight). */
        fun isBedtime(
            nowMillis: Long,
            bedHour: Int,
            bedMinute: Int,
            wakeHour: Int,
            wakeMinute: Int,
        ): Boolean {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val bedMin = bedHour * 60 + bedMinute
            val wakeMin = wakeHour * 60 + wakeMinute
            return if (bedMin <= wakeMin) {
                nowMin in bedMin until wakeMin
            } else {
                nowMin >= bedMin || nowMin < wakeMin
            }
        }

        /** Most recent bedtime moment at or before [nowMillis] (handles overnight). */
        fun lastBedtimeStart(nowMillis: Long, bedHour: Int, bedMinute: Int): Long {
            val bedToday = atTimeMillis(nowMillis, bedHour, bedMinute)
            return if (nowMillis >= bedToday) bedToday else bedToday - 24 * 60_000L
        }

        val IDLE_LINES = listOf(
            "Poke me if you're bored!",
            "Did you drink water today? Hydration check!",
            "I'm just vibing over here.",
            "Your wallpaper is cute, but I'm cuter.",
            "Stretch those shoulders for me?",
            "La la la… walking is my cardio.",
            "Blink! Your eyes will thank you.",
            "If I had pockets, I'd keep snacks in them.",
        )

        val TAP_LINES = listOf(
            "Hehe, that tickles!",
            "Boop received!",
            "Best friends forever!",
            "You're doing great today!",
            "Again! Again!",
        )
    }
}
