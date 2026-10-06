package com.sree.sasi.overlay

/**
 * Emotional states Sasi can be in. Only a subset is wired to live signals in
 * Phase 1 (NORMAL, HAPPY, TIRED, SLEEPY, WORRIED); the rest exist for later
 * phases (focus sessions, play, achievements) and map to the nearest wired
 * mood in [CompanionView.refreshFace].
 */
enum class Mood {
    NORMAL,
    HAPPY,
    BORED,
    PLAYFUL,
    EXCITED,
    PROUD,
    FOCUSED,
    TIRED,
    SLEEPY,
    WORRIED,
    RESTING,
}

/**
 * Decides Sasi's mood from live signals. Deterministic and calm:
 * - at least 90s between mood changes (cooldown),
 * - TIRED has hysteresis: continuous use must stay under 40 min for a full
 *   10 minutes before TIRED clears, so the mood doesn't flicker.
 */
class MoodEngine {

    data class Input(
        val nowMillis: Long,
        val continuousMinutes: Int,
        val todayMinutes: Int,
        val isInteractive: Boolean,
        val screenOn: Boolean,
        val lastInteractMillis: Long,
        val inBedtimeWindow: Boolean,
    )

    private var current: Mood = Mood.NORMAL
    private var lastChangeMillis: Long = 0L
    private var reliefStartMillis: Long = 0L

    fun update(input: Input): Mood {
        val now = input.nowMillis
        val desired = computeDesired(input)

        // Hysteresis: TIRED only clears after 10 min of light use (< 40 min
        // continuous). Bedtime/screen-off may still override to SLEEPY.
        if (current == Mood.TIRED && desired != Mood.TIRED) {
            if (desired != Mood.SLEEPY) {
                if (input.continuousMinutes < 40) {
                    if (reliefStartMillis == 0L) reliefStartMillis = now
                    if (now - reliefStartMillis < 10 * 60_000L) return current
                } else {
                    reliefStartMillis = 0L
                    return current
                }
            } else {
                reliefStartMillis = 0L
            }
        } else if (current != Mood.TIRED) {
            reliefStartMillis = 0L
        }

        if (desired == current) return current
        // Cooldown: don't flip moods more often than every 90s. The very
        // first update applies immediately.
        if (lastChangeMillis != 0L && now - lastChangeMillis < 90_000L) {
            return current
        }
        current = desired
        lastChangeMillis = now
        return current
    }

    private fun computeDesired(input: Input): Mood {
        if (!input.screenOn || input.inBedtimeWindow) return Mood.SLEEPY
        if (input.continuousMinutes >= 90) return Mood.TIRED
        if (input.continuousMinutes >= 60) return Mood.WORRIED
        if (input.lastInteractMillis > 0L &&
            input.nowMillis - input.lastInteractMillis < 3 * 60_000L
        ) {
            return Mood.HAPPY
        }
        return Mood.NORMAL
    }

    fun current(): Mood = current
}
