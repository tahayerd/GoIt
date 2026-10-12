package com.taha.goit.data.api

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OsrmRouter(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val memoryCache = ConcurrentHashMap<String, List<LatLng>>()
    private val cacheDir = File(context.filesDir, "osrm")

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    /**
     * Calculates walking path using FOSSGIS OpenStreetMap foot router.
     * Returns null on network/parsing failure to allow fallback to straight line.
     */
    suspend fun footRoute(lat1: Double, lng1: Double, lat2: Double, lng2: Double): List<LatLng>? = withContext(Dispatchers.IO) {
        // Cache key with 5 decimal precision (~1 meter)
        val key = String.format(Locale.ROOT, "%.5f,%.5f_%.5f,%.5f", lat1, lng1, lat2, lng2)

        memoryCache[key]?.let { return@withContext it }

        val cacheFile = File(cacheDir, "${key.hashCode()}.json")
        if (cacheFile.exists()) {
            try {
                val coords = parseCoords(cacheFile.readText())
                if (coords != null && coords.isNotEmpty()) {
                    memoryCache[key] = coords
                    return@withContext coords
                }
            } catch (_: Exception) {}
        }

        try {
            // Note: FOSSGIS foot routing profile URL uses 'driving' endpoint with OSM foot backend
            val url = String.format(
                Locale.ROOT,
                "https://routing.openstreetmap.de/routed-foot/route/v1/driving/%.6f,%.6f;%%.6f,%.6f?overview=full&geometries=geojson",
                lng1, lat1, lng2, lat2
            )

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "GoItTransit/1.6.0 (Android; OpenStreetMap foot router)")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                val coords = parseCoords(bodyStr)
                if (coords != null && coords.isNotEmpty()) {
                    memoryCache[key] = coords
                    cacheFile.writeText(bodyStr)
                    return@withContext coords
                }
            }
        } catch (e: Exception) {
            Log.w("OsrmRouter", "OSRM foot route failed: ${e.message}")
        }
        null
    }

    private fun parseCoords(jsonStr: String): List<LatLng>? {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val routes = root["routes"]?.jsonArray ?: return null
            val firstRoute = routes.firstOrNull()?.jsonObject ?: return null
            val geometry = firstRoute["geometry"]?.jsonObject ?: return null
            val coordinates = geometry["coordinates"]?.jsonArray ?: return null

            val result = mutableListOf<LatLng>()
            for (coordElement in coordinates) {
                val arr = coordElement.jsonArray
                val lon = arr.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: continue
                val lat = arr.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: continue
                result.add(LatLng(lat, lon))
            }
            result
        } catch (e: Exception) {
            null
        }
    }
}
