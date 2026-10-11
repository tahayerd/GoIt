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
}
