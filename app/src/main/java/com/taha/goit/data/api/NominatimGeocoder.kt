package com.taha.goit.data.api

import android.util.Log
import com.taha.goit.data.model.OsmPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Nominatim (OpenStreetMap) tabanlı coğrafi kodlama — izleyicisiz (tracker-free) yedek arama.
 * Yerel veritabanında bulunamayan mekan adları için koordinat çözümler.
 * Kullanım koşulları gereği istekler saniyede en fazla 1 adet ve önbelleklidir.
 */
class NominatimGeocoder(
    private val client: OkHttpClient = defaultClient
) {
    companion object {
        private const val MAX_CACHE_ENTRIES = 200
        private const val CACHE_TTL_MS = 7L * 24 * 3600 * 1000L // 7 gün
        private const val MIN_REQUEST_INTERVAL_MS = 1100L

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var lastRequestTime = 0L

    private data class CacheEntry(val places: List<OsmPlace>, val timestamp: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Bursa şehir merkezine odaklı arama (viewbox: batı,güney,doğu,kuzey). */
    suspend fun searchPlaces(query: String, limit: Int = 5): List<OsmPlace> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val key = trimmed.lowercase(Locale.ROOT) // O-25: Locale.ROOT

        val now = System.currentTimeMillis()
        cache[key]?.let { entry ->
            if (now - entry.timestamp < CACHE_TTL_MS) {
                return@withContext entry.places
            } else {
                cache.remove(key)
            }
        }

        // Y-9: Mutex ile thread-safe rate-limiting (saniyede en fazla 1 istek)
        val responseString = mutex.withLock {
            val currentNow = System.currentTimeMillis()
            val wait = MIN_REQUEST_INTERVAL_MS - (currentNow - lastRequestTime)
            if (wait > 0) delay(wait)
            lastRequestTime = System.currentTimeMillis()

            try {
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&limit=$limit" +
                        "&accept-language=tr&countrycodes=tr&viewbox=28.0,40.6,30.2,39.4&bounded=1"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "GoIt-Bursa-Transit/1.0 (https://github.com/tahayerd/GoIt)") // K-6
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w("NominatimGeocoder", "HTTP error ${resp.code} for query: $trimmed")
                        null
                    } else {
                        resp.body?.string()
                    }
                }
            } catch (e: Exception) {
                Log.w("NominatimGeocoder", "Request failed for query: $trimmed", e)
                null
            }
        } ?: return@withContext emptyList()

        try {
            val parsed = json.decodeFromString<List<OsmPlace>>(responseString)
            if (cache.size >= MAX_CACHE_ENTRIES) {
                val oldestKey = cache.entries.minByOrNull { it.value.timestamp }?.key
                if (oldestKey != null) cache.remove(oldestKey)
            }
            cache[key] = CacheEntry(parsed, System.currentTimeMillis())
            parsed
        } catch (e: Exception) {
            Log.w("NominatimGeocoder", "JSON decode failed for query: $trimmed", e)
            emptyList()
        }
    }
}
