package com.sree.sasi.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One day of simple, honest stats. Everything here is derived locally:
 * screen minutes from UsageStatsManager, session counts from the screen
 * on/off rhythm, focus numbers from completed focus sessions.
 */
data class DayRecord(
    val date: String, // yyyy-MM-dd
    val screenMin: Int = 0,
    val sessions: Int = 0,
    val focusSessions: Int = 0,
    val focusMin: Int = 0,
    /** Phase 3: XP earned on this day (0 for records saved before Phase 3). */
    val xpEarned: Int = 0,
)

/**
 * Stores the last [MAX_DAYS] of [DayRecord] as a JSON string in DataStore
 * (key `history_json`). org.json ships with Android, so no new dependency.
 * Updates are event-driven (session end, focus end, day rollover) — never on
 * a hot loop.
 */
object History {

    const val MAX_DAYS = 14

    fun load(json: String): MutableList<DayRecord> {
        val out = mutableListOf<DayRecord>()
        if (json.isBlank()) return out
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val date = o.optString("date")
                if (date.isBlank()) continue
                out += DayRecord(
                    date = date,
                    screenMin = o.optInt("screenMin"),
                    sessions = o.optInt("sessions"),
                    focusSessions = o.optInt("focusSessions"),
                    focusMin = o.optInt("focusMin"),
                    xpEarned = o.optInt("xpEarned"),
                )
            }
        } catch (e: Exception) {
            // Corrupted payload: start fresh rather than crash.
            return mutableListOf()
        }
        return out
    }

    fun save(records: List<DayRecord>): String {
        val pruned = records.filter { it.date.isNotBlank() }
            .sortedBy { it.date }
            .takeLast(MAX_DAYS)
        val arr = JSONArray()
        for (r in pruned) {
            arr.put(
                JSONObject().apply {
                    put("date", r.date)
                    put("screenMin", r.screenMin)
                    put("sessions", r.sessions)
                    put("focusSessions", r.focusSessions)
                    put("focusMin", r.focusMin)
                    put("xpEarned", r.xpEarned)
                },
            )
        }
        return arr.toString()
    }

    /** Returns the record for [date], creating it if absent. */
    fun ensureDay(records: MutableList<DayRecord>, date: String): DayRecord {
        val existing = records.find { it.date == date }
        if (existing != null) return existing
        val fresh = DayRecord(date)
        records += fresh
        return fresh
    }

    /** Replaces (or creates) the record for [date] with [mutate] applied. */
    fun updateDay(
        records: MutableList<DayRecord>,
        date: String,
        mutate: (DayRecord) -> DayRecord,
    ) {
        val idx = records.indexOfFirst { it.date == date }
        if (idx >= 0) {
            records[idx] = mutate(records[idx])
        } else {
            records += mutate(DayRecord(date))
        }
    }
}
