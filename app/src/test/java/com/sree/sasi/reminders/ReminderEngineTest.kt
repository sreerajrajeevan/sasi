package com.sree.sasi.reminders

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class ReminderEngineTest {
    private val engine = ReminderEngine()
    private fun input() = ReminderEngine.Input(
        nowMillis = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 13, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis,
        name = "Sasi", isInteractive = true, continuousMinutes = 50,
        sessionStartMillis = 1000L, todayScreenMinutes = 50,
        restIntervalMinutes = 50, bedtimeHour = 23, bedtimeMinute = 0,
        wakeHour = 7, wakeMinute = 0, tickCount = 1,
        dayKey = "2026-10-13", firedKeys = emptySet(),
    )

    @Test fun restWarningTakesPriorityOverGentleNudge() {
        assertTrue(engine.evaluate(input()) is ReminderEngine.Reminder.RestDue)
    }

    @Test fun restWarningIsDeduplicatedWithinSession() {
        val input = input().copy(firedKeys = setOf("rest:1000"))
        assertFalse(engine.evaluate(input) is ReminderEngine.Reminder.RestDue)
    }

    @Test fun newSessionAllowsAnotherRestWarning() {
        val input = input().copy(sessionStartMillis = 2000L, firedKeys = setOf("rest:1000"))
        assertTrue(engine.evaluate(input) is ReminderEngine.Reminder.RestDue)
    }

    @Test fun screenOffDoesNotTriggerRestWarning() {
        assertNull(engine.evaluate(input().copy(isInteractive = false)))
    }

    @Test fun bedtimeWindowCrossesMidnight() {
        val noon = input().nowMillis
        assertTrue(ReminderEngine.isBedtime(ReminderEngine.atTimeMillis(noon, 23, 30), 23, 0, 7, 0))
        assertTrue(ReminderEngine.isBedtime(ReminderEngine.atTimeMillis(noon, 6, 59), 23, 0, 7, 0))
        assertFalse(ReminderEngine.isBedtime(ReminderEngine.atTimeMillis(noon, 7, 0), 23, 0, 7, 0))
    }
}
