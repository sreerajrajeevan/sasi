package com.sree.sasi.overlay

/**
 * Where Sasi is allowed to be right now. Persisted in [com.sree.sasi.data.Prefs]
 * so a rotation, service restart or reboot can never accidentally resurrect it.
 */
enum class VisibilityState {
    VISIBLE,
    HIDDEN_TEMPORARILY,
    HIDDEN_UNTIL_SCREEN_LOCK,
    HIDDEN_UNTIL_APP_OPEN,
    HIDDEN_UNTIL_REMINDER,
    DISABLED;

    companion object {
        fun fromName(name: String?): VisibilityState =
            values().firstOrNull { it.name == name } ?: VISIBLE
    }

    /** True when the overlay must stay off screen right now. */
    fun isHidden(): Boolean = this != VISIBLE

    /**
     * Only real warnings (rest, milestones, bedtime, wake) may pop Sasi back
     * from this state — never idle chatter or gentle nudges.
     */
    fun restorableByReminder(): Boolean = this == HIDDEN_UNTIL_REMINDER
}
