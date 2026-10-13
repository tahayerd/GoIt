package com.taha.goit.data.api

import android.content.Context
import android.util.Log
import com.taha.goit.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OsrmRouter(
    cacheBaseDir: File,
    private val client: OkHttpClient = defaultClient
) {
    // Context tabanlı başlatma (Context sızıntısını önlemek için applicationContext kullanılır)
    constructor(context: Context, client: OkHttpClient = defaultClient) : this(
        File(context.applicationContext.filesDir, "osrm"),
        client
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheDir = cacheBaseDir

    private data class CacheEntry(val coords: List<LatLng>, val timestamp: Long)

    companion object {
        private const val MAX_MEMORY_ENTRIES = 250
        private const val MEMORY_TTL_MS = 7L * 24 * 3600 * 1000L // 7 gün
        private const val DISK_TTL_MS = 30L * 24 * 3600 * 1000L  // 30 gün
        private const val MAX_DISK_CACHE_BYTES = 50L * 1024 * 1024 // 50 MB

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
        }

        /**
         * FOSSGIS OpenStreetMap yürüme profili URL'i (K-1 düzeltildi).
         */
        fun buildUrl(lat1: Double, lng1: Double, lat2: Double, lng2: Double): String {
            return String.format(
                Locale.ROOT,
                "https://routing.openstreetmap.de/routed-foot/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                lng1, lat1, lng2, lat2
            )
        }

        fun hashKey(key: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(key.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        cleanupDiskCacheIfNeeded()
    }

    /**
     * Calculates walking path using FOSSGIS OpenStreetMap foot router.
     * Returns null on network/parsing failure to allow fallback to straight line.
     */
    suspend fun footRoute(lat1: Double, lng1: Double, lat2: Double, lng2: Double): List<LatLng>? = withContext(Dispatchers.IO) {
        val key = String.format(Locale.ROOT, "%.5f,%.5f_%.5f,%.5f", lat1, lng1, lat2, lng2)
        val now = System.currentTimeMillis()

        // 1. Bellek içi kontrol (LRU / TTL)
        memoryCache[key]?.let { entry ->
            if (now - entry.timestamp < MEMORY_TTL_MS) {
                return@withContext entry.coords
            } else {
                memoryCache.remove(key)
            }
        }

        // 2. Disk cache kontrolü (SHA-256 dosya adı ile collision engellendi)
        val fileName = "${hashKey(key)}.json"
        val cacheFile = File(cacheDir, fileName)
        if (cacheFile.exists()) {
            if (now - cacheFile.lastModified() > DISK_TTL_MS) {
                cacheFile.delete()
            } else {
                try {
                    val coords = parseCoords(cacheFile.readText())
                    if (!coords.isNullOrEmpty()) {
                        putMemory(key, coords)
                        return@withContext coords
                    } else {
                        cacheFile.delete() // O-5: Bozuk dosya temizliği
                    }
                } catch (e: Exception) {
                    Log.w("OsrmRouter", "Cached JSON parse failed, deleting file: ${cacheFile.name}", e)
                    cacheFile.delete() // O-5
                }
            }
        }

        // 3. Ağdan istek
        try {
            val url = buildUrl(lat1, lng1, lat2, lng2)
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "GoItTransit/${BuildConfig.VERSION_NAME} (Android; OpenStreetMap foot router)")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("OsrmRouter", "OSRM HTTP ${resp.code} for URL: $url")
                    return@withContext null
                }
                val bodyStr = resp.body?.string() ?: return@withContext null
                val coords = parseCoords(bodyStr)
                if (!coords.isNullOrEmpty()) {
                    putMemory(key, coords)
                    runCatching { cacheFile.writeText(bodyStr) }
                    return@withContext coords
                }
            }
        } catch (e: Exception) {
            Log.w("OsrmRouter", "OSRM foot route failed: ${e.message}", e)
        }
        null
    }

    private fun putMemory(key: String, coords: List<LatLng>) {
        if (memoryCache.size >= MAX_MEMORY_ENTRIES) {
            val oldestKey = memoryCache.entries.minByOrNull { it.value.timestamp }?.key
            if (oldestKey != null) memoryCache.remove(oldestKey)
        }
        memoryCache[key] = CacheEntry(coords, System.currentTimeMillis())
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
            Log.w("OsrmRouter", "parseCoords failed", e)
            null
        }
    }

    private fun cleanupDiskCacheIfNeeded() {
        try {
            val files = cacheDir.listFiles() ?: return
            val now = System.currentTimeMillis()
            var totalBytes = 0L

            for (f in files) {
                if (now - f.lastModified() > DISK_TTL_MS) {
                    f.delete()
                } else {
                    totalBytes += f.length()
                }
            }

            if (totalBytes > MAX_DISK_CACHE_BYTES) {
                val sorted = files.filter { it.exists() }.sortedBy { it.lastModified() }
                for (f in sorted) {
                    totalBytes -= f.length()
                    f.delete()
                    if (totalBytes <= MAX_DISK_CACHE_BYTES * 0.7) break
                }
            }
        } catch (e: Exception) {
            Log.w("OsrmRouter", "cleanupDiskCache failed: ${e.message}")
        }
    }
}
