package com.sree.sasi.screentime

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeTrackerTest {
    @Test fun delayedTicksUseActualElapsedTime() {
        val tracker = ScreenTimeTracker()
        tracker.tick(true, nowMillis = 1000L, elapsedMillis = 0L)
        assertEquals(0, tracker.continuousMinutes)
        tracker.tick(true, nowMillis = 121000L, elapsedMillis = 120000L)
        assertEquals(2, tracker.continuousMinutes)
        assertEquals(1000L, tracker.sessionStartMillis)
    }

    @Test fun screenLockStartsANewSessionEvenBetweenPollingTicks() {
        val tracker = ScreenTimeTracker()
        tracker.tick(true, 1000L, 0L)
        tracker.tick(true, 61000L, 60000L)
        tracker.tick(false, 62000L, 61000L)
        assertEquals(0, tracker.continuousMinutes)
        tracker.tick(true, 63000L, 62000L)
        assertEquals(63000L, tracker.sessionStartMillis)
        assertEquals(0, tracker.continuousMinutes)
    }

    @Test fun wallClockChangesDoNotChangeElapsedUse() {
        val tracker = ScreenTimeTracker()
        tracker.tick(true, 1000L, 0L)
        tracker.tick(true, -3600000L, 60000L)
        assertEquals(1, tracker.continuousMinutes)
    }
}
