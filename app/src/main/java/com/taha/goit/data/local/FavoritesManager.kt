package com.taha.goit.data.local

import android.content.Context
import com.taha.goit.data.model.SearchItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FavoriteItem(
    val id: String,          // "R:1150" or "S:165"
    val type: String,        // "R" or "S"
    val title: String,       // Hat kodu veya durak adı
    val subtitle: String,    // Açıklama
    val refId: Int           // hatNo veya stationId
)

class FavoritesManager(context: Context) {
    private val prefs = context.getSharedPreferences("goit_favorites", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getFavorites(): List<FavoriteItem> {
        return try {
            val raw = prefs.getString("items", null) ?: return emptyList()
            json.decodeFromString<List<FavoriteItem>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isFavorite(type: String, refId: Int): Boolean {
        return getFavorites().any { it.type == type && it.refId == refId }
    }

    fun toggleFavorite(item: SearchItem) {
        val favorites = getFavorites().toMutableList()
        val type = item.type ?: return
        val refId = if (type == "R") item.hatNo ?: return else item.stationId ?: return
        val id = "$type:$refId"
        val existing = favorites.find { it.id == id }
        if (existing != null) {
            favorites.remove(existing)
        } else {
            favorites.add(
                FavoriteItem(
                    id = id,
                    type = type,
                    title = item.kod ?: item.stationName ?: "",
                    subtitle = item.aciklama ?: "",
                    refId = refId
                )
            )
        }
        prefs.edit().putString("items", json.encodeToString(favorites)).apply()
    }

    fun toggleFavoriteRaw(type: String, title: String, subtitle: String, refId: Int) {
        val favorites = getFavorites().toMutableList()
        val id = "$type:$refId"
        val existing = favorites.find { it.id == id }
        if (existing != null) {
            favorites.remove(existing)
        } else {
            favorites.add(FavoriteItem(id, type, title, subtitle, refId))
        }
        prefs.edit().putString("items", json.encodeToString(favorites)).apply()
    }
}
