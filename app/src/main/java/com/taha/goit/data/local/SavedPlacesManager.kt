package com.taha.goit.data.local

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val kind: String // "home" | "work" | "custom"
)

class SavedPlacesManager(context: Context) {
    private val prefs = context.getSharedPreferences("goit_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getAll(): List<SavedPlace> {
        val raw = prefs.getString("saved_places", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SavedPlace>>(raw) }.getOrDefault(emptyList())
    }

    fun save(place: SavedPlace) {
        val current = getAll().toMutableList()
        if (place.kind == "home" || place.kind == "work") {
            current.removeAll { it.kind == place.kind }
            current.add(0, place)
        } else {
            current.removeAll { it.id == place.id }
            current.add(place)
        }
        prefs.edit().putString("saved_places", json.encodeToString(current)).apply()
    }

    fun remove(id: String) {
        val current = getAll().filter { it.id != id }
        prefs.edit().putString("saved_places", json.encodeToString(current)).apply()
    }
}
