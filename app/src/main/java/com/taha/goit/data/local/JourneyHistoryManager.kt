package com.taha.goit.data.local

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class JourneyHistoryEntry(
    val originName: String,
    val originLat: Double,
    val originLng: Double,
    val destName: String,
    val destLat: Double,
    val destLng: Double,
    val ts: Long
)

class JourneyHistoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("goit_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getHistory(): List<JourneyHistoryEntry> {
        val raw = prefs.getString("journey_history", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<JourneyHistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    fun add(entry: JourneyHistoryEntry) {
        val current = getHistory().toMutableList()
        val now = System.currentTimeMillis()
        // Dedupe: son 10 dk içinde aynı origin ve dest varsa tekrar ekleme
        current.removeAll {
            it.originName == entry.originName &&
            it.destName == entry.destName &&
            (now - it.ts) < 10 * 60 * 1000L
        }
        current.add(0, entry)
        val trimmed = current.take(10)
        prefs.edit().putString("journey_history", json.encodeToString(trimmed)).apply()
    }

    fun clear() {
        prefs.edit().remove("journey_history").apply()
    }

    fun recordDestinationVisit(name: String, lat: Double, lng: Double) {
        if (name.isBlank()) return
        val raw = prefs.getString("trip_visit_counts", null)
        val list = if (raw != null) {
            runCatching { json.decodeFromString<List<VisitedPlace>>(raw) }.getOrDefault(emptyList()).toMutableList()
        } else {
            mutableListOf()
        }

        val idx = list.indexOfFirst {
            it.name.equals(name, ignoreCase = true) ||
            (Math.abs(it.lat - lat) < 0.001 && Math.abs(it.lng - lng) < 0.001)
        }

        if (idx >= 0) {
            val existing = list[idx]
            list[idx] = existing.copy(name = name, lat = lat, lng = lng, count = existing.count + 1)
        } else {
            list.add(VisitedPlace(name = name, lat = lat, lng = lng, count = 1))
        }

        prefs.edit().putString("trip_visit_counts", json.encodeToString(list)).apply()
    }

    fun getMostVisited(limit: Int = 5): List<VisitedPlace> {
        val raw = prefs.getString("trip_visit_counts", null) ?: return emptyList()
        val list = runCatching { json.decodeFromString<List<VisitedPlace>>(raw) }.getOrDefault(emptyList())
        return list.sortedByDescending { it.count }.take(limit)
    }
}

@Serializable
data class VisitedPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val count: Int
)
