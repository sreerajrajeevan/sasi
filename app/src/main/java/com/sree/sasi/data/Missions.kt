package com.sree.sasi.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Phase 3: daily missions — 3 small healthy goals per day, picked
 * deterministically from a pool (seeded by date, so every install agrees and
 * there's no server involved).
 *
 * Rules: new day = fresh missions, no carryover, no penalty for missing any.
 * Each mission listens to one event id; [applyEvent] bumps matching missions
 * and returns the ones that just completed (awarded exactly once).
 */
data class MissionDef(
    val id: String,
    val title: String,
    val target: Int,
    val event: String,
)

data class Mission(
    val id: String,
    val title: String,
    val target: Int,
    val progress: Int = 0,
    val done: Boolean = false,
)

object Missions {

    val POOL = listOf(
        MissionDef("focus1", "Complete 1 focus session", 1, "focus"),
        MissionDef("breaks3", "Take 3 breaks", 3, "break"),
        MissionDef("taps5", "Say hi to Sasi 5 times", 5, "tap"),
        MissionDef("rest1", "Respect a rest reminder", 1, "rest_respected"),
        MissionDef("goal1", "Stay under your screen-time goal", 1, "goal_met"),
        MissionDef("xp100", "Earn 100 XP today", 100, "xp"),
        MissionDef("winddown1", "Wind down nicely before bed", 1, "wind_down"),
        MissionDef("break5", "Take 5 breaks", 5, "break"),
    )

    /** Deterministic 3-mission pick for a yyyy-MM-dd date key. */
    fun pickForDate(dateKey: String): List<Mission> =
        POOL.shuffled(Random(dateKey.hashCode().toLong()))
            .take(3)
            .map { Mission(it.id, it.title, it.target) }

    fun defFor(id: String): MissionDef? = POOL.find { it.id == id }

    /**
     * Loads persisted missions. Title/target are re-derived from [POOL] so a
     * pool change can never corrupt saved progress; unknown ids are skipped.
     */
    fun load(json: String): MutableList<Mission> {
        val out = mutableListOf<Mission>()
        if (json.isBlank()) return out
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val def = defFor(o.optString("id")) ?: continue
                val progress = o.optInt("progress").coerceIn(0, def.target)
                out += Mission(def.id, def.title, def.target, progress, o.optBoolean("done") || progress >= def.target)
            }
        } catch (e: Exception) {
            return mutableListOf()
        }
        return out
    }

    fun save(missions: List<Mission>): String {
        val arr = JSONArray()
        for (m in missions) {
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("progress", m.progress)
                    put("done", m.done)
                },
            )
        }
        return arr.toString()
    }

    /**
     * Applies an event (e.g. "focus", "break", "tap", "xp" with amount) to all
     * matching incomplete missions. Returns the missions that just completed.
     */
    fun applyEvent(
        missions: MutableList<Mission>,
        event: String,
        amount: Int = 1,
    ): List<Mission> {
        val newly = mutableListOf<Mission>()
        for (i in missions.indices) {
            val m = missions[i]
            if (m.done) continue
            val def = defFor(m.id) ?: continue
            if (def.event != event) continue
            val progress = (m.progress + amount).coerceAtMost(m.target)
            val done = progress >= m.target
            missions[i] = m.copy(progress = progress, done = done)
            if (done) newly += missions[i]
        }
        return newly
    }
}
