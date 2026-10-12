package com.taha.goit.data.local

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class VisitedPlaceEntry(
    val key: String, // lat,lng veya durak adı
    val name: String,
    val lat: Double,
    val lng: Double,
    val count: Int
)

/**
 * Kullanıcının en çok seyahat ettiği hedefleri sayar ve en sık 5 tanesini döndürür.
 */
class VisitedPlacesManager(context: Context) {
    private val prefs = context.getSharedPreferences("goit_visited_places", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun recordVisit(name: String, lat: Double, lng: Double) {
        val key = String.format(java.util.Locale.ROOT, "%.4f,%.4f", lat, lng)
        val current = getVisitedList().toMutableList()
        val existingIdx = current.indexOfFirst { it.key == key || it.name.equals(name, ignoreCase = true) }
        if (existingIdx >= 0) {
            val old = current[existingIdx]
            current[existingIdx] = old.copy(count = old.count + 1, name = name, lat = lat, lng = lng)
        } else {
            current.add(VisitedPlaceEntry(key = key, name = name, lat = lat, lng = lng, count = 1))
        }
        saveList(current)
    }

    fun getTopVisited(limit: Int = 5): List<VisitedPlaceEntry> {
        return getVisitedList()
            .filter { it.count >= 2 } // En az iki kez gidilmiş
            .sortedByDescending { it.count }
            .take(limit)
    }

    private fun getVisitedList(): List<VisitedPlaceEntry> {
        val raw = prefs.getString("trip_visit_counts", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<VisitedPlaceEntry>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveList(list: List<VisitedPlaceEntry>) {
        val raw = json.encodeToString(list.take(30))
        prefs.edit().putString("trip_visit_counts", raw).apply()
    }
}
