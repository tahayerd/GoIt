package com.taha.goit.data.local

import android.content.Context
import com.taha.goit.data.model.LocalStation
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Cihazda gömülü durak/mekan veritabanı (assets/stations.json).
 * 7.682 durak + 6.240 mekan; gerçek koordinatlar içerir.
 * Arama ve en yakın durak çözümlemesi tamamen offline çalışır (izleyicisiz).
 */
object StationDatabase {

    private class Entry(val s: LocalStation, val norm: String)

    @Volatile
    private var all: List<LocalStation> = emptyList()

    @Volatile
    private var stations: List<LocalStation> = emptyList()

    @Volatile
    private var entries: List<Entry> = emptyList()

    @Volatile
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val text = context.assets.open("stations.json").bufferedReader().use { it.readText() }
                val parsed = Json.decodeFromString<List<LocalStation>>(text)
                all = parsed
                stations = parsed.filter { it.t == 1 }
                entries = parsed.map { Entry(it, normalize(it.n)) }
            } catch (_: Exception) {
                all = emptyList()
                stations = emptyList()
                entries = emptyList()
            }
            loaded = true
        }
    }

    fun isLoaded(): Boolean = loaded

    fun size(): Int = all.size

    /** Kimliğe göre kayıt bulur (durak veya mekan). */
    fun findById(id: Int): LocalStation? = all.firstOrNull { it.i == id }

    /** Ad içinde arama (Türkçe harf duyarsız, önceden normalize edilmiş). */
    fun search(query: String, limit: Int = 40): List<LocalStation> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        val out = ArrayList<LocalStation>(limit)
        for (e in entries) {
            if (e.norm.contains(q)) {
                out.add(e.s)
                if (out.size >= limit) break
            }
        }
        return out
    }

    /**
     * Sorguya en iyi uyan tek kaydı bulur: önce tam eşleşme, sonra başlangıç, sonra içerik.
     * Kelime bazlı kısmi eşleşme de dener ("zafer plaza avm" -> "ZAFER PLAZA AVM").
     */
    fun resolveBest(query: String): LocalStation? {
        val q = normalize(query)
        if (q.length < 2) return null
        val words = q.split(' ').filter { it.length >= 3 }
        var best: LocalStation? = null
        var bestScore = -1
        for (e in entries) {
            val n = e.norm
            val score = when {
                n == q -> 1000
                n.startsWith(q) -> 700
                n.contains(q) -> 400
                words.isNotEmpty() && words.all { n.contains(it) } -> 300 + words.size * 10
                else -> -1
            }
            if (score > bestScore || (score == bestScore && best != null && e.s.t == 1 && best.t == 2)) {
                bestScore = score
                best = e.s
            }
            if (bestScore >= 1000) break
        }
        return if (bestScore > 0) best else null
    }

    /** Verilen noktaya en yakın durakları döndürür (yalnızca durak tipi, mesafeyle). */
    fun nearestStations(lat: Double, lng: Double, limit: Int = 20): List<Pair<LocalStation, Double>> {
        val withDist = ArrayList<Pair<LocalStation, Double>>(stations.size)
        for (s in stations) {
            withDist.add(s to haversine(lat, lng, s.la, s.lo))
        }
        withDist.sortBy { it.second }
        return withDist.take(limit)
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale("tr", "TR"))
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ç', 'c').replace('ö', 'o').replace('ü', 'u')
            .replace("(", " ").replace(")", " ").replace(".", " ").replace(",", " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
