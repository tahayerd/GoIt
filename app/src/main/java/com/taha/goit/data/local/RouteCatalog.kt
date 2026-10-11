package com.taha.goit.data.local

import android.content.Context
import com.taha.goit.data.model.RouteCatalogEntry
import kotlinx.serialization.json.Json

/**
 * Şehirdeki tüm hatların kataloğu (assets/routes.json — Burulaş API'den crawl edilmiş 409 hat).
 * Hatlar sekmesindeki "tüm şehir hat listesi" buradan beslenir; ağ gerekmez.
 */
object RouteCatalog {

    @Volatile
    private var routes: List<RouteCatalogEntry> = emptyList()

    @Volatile
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val text = context.assets.open("routes.json").bufferedReader().use { it.readText() }
                routes = Json.decodeFromString<List<RouteCatalogEntry>>(text)
            } catch (_: Exception) {
                routes = emptyList()
            }
            loaded = true
        }
    }

    fun all(): List<RouteCatalogEntry> = routes

    fun size(): Int = routes.size

    /** Hat kodu araması (Türkçe harf duyarsız, kısmi eşleşme). */
    fun search(query: String, limit: Int = 80): List<RouteCatalogEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val norm = q.lowercase(java.util.Locale("tr", "TR"))
        return routes.filter { r ->
            r.kod.lowercase(java.util.Locale("tr", "TR")).contains(norm) ||
                    r.aciklama.lowercase(java.util.Locale("tr", "TR")).contains(norm)
        }.take(limit)
    }

    fun findByCode(code: String): RouteCatalogEntry? =
        routes.firstOrNull { it.kod.equals(code, ignoreCase = true) }
}
