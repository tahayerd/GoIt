package com.taha.goit.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * FOSSGIS OSRM routed-foot servisi üzerinden gerçek yürüyüş rotası sağlar.
 * Şebeke hatasında veya zaman aşımında null döner, çağıran düz çizgi fallback uygular.
 */
object OsrmRouter {
    private val inMemoryCache = mutableMapOf<String, List<Pair<Double, Double>>>()
    private var cacheDir: File? = null

    fun init(context: Context) {
        cacheDir = File(context.filesDir, "osrm").apply { mkdirs() }
    }

    suspend fun footRoute(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): List<Pair<Double, Double>>? = withContext(Dispatchers.IO) {
        val key = String.format(Locale.ROOT, "%.5f,%.5f_%.5f,%.5f", lat1, lng1, lat2, lng2)

        synchronized(inMemoryCache) {
            inMemoryCache[key]?.let { return@withContext it }
        }

        val diskFile = cacheDir?.let { File(it, "$key.json") }
        if (diskFile != null && diskFile.exists()) {
            try {
                val pts = parseCoordinates(diskFile.readText())
                if (pts.isNotEmpty()) {
                    synchronized(inMemoryCache) { inMemoryCache[key] = pts }
                    return@withContext pts
                }
            } catch (_: Exception) {}
        }

        try {
            val urlStr = String.format(
                Locale.ROOT,
                "https://routing.openstreetmap.de/routed-foot/route/v1/driving/%.6f,%.6f;%%.6f,%.6f?overview=full&geometries=geojson",
                lng1, lat1, lng2, lat2
            )
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "GoIt-Android/1.6.0")

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val pts = parseCoordinates(jsonStr)
                if (pts.isNotEmpty()) {
                    diskFile?.writeText(jsonStr)
                    synchronized(inMemoryCache) { inMemoryCache[key] = pts }
                    return@withContext pts
                }
            } else {
                conn.disconnect()
            }
        } catch (_: Exception) {
            // Çevrimdışı / timeout durumunda sessizce null döner
        }
        null
    }

    private fun parseCoordinates(jsonText: String): List<Pair<Double, Double>> {
        return try {
            val root = Json.parseToJsonElement(jsonText).jsonObject
            val routes = root["routes"]?.jsonArray ?: return emptyList()
            val first = routes.firstOrNull()?.jsonObject ?: return emptyList()
            val geom = first["geometry"]?.jsonObject ?: return emptyList()
            val coords = geom["coordinates"]?.jsonArray ?: return emptyList()

            coords.mapNotNull { item ->
                val arr = item.jsonArray
                val lon = arr.getOrNull(0)?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val lat = arr.getOrNull(1)?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                lat to lon
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
