package com.taha.goit.data.api

import com.taha.goit.data.model.OsmPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Nominatim (OpenStreetMap) tabanlı coğrafi kodlama — izleyicisiz (tracker-free) yedek arama.
 * Yerel veritabanında bulunamayan mekan adları için koordinat çözümler.
 * Kullanım koşulları gereği istekler düşük hacimli ve önbelleklidir.
 */
class NominatimGeocoder {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private var lastRequestTime = 0L
    private val cache = mutableMapOf<String, List<OsmPlace>>()

    /** Bursa şehir merkezine odaklı arama (viewbox: batı,güney,doğu,kuzey). */
    suspend fun searchPlaces(query: String, limit: Int = 5): List<OsmPlace> = withContext(Dispatchers.IO) {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return@withContext emptyList()
        cache[key]?.let { return@withContext it }

        // Nominatim politika: saniyede en fazla 1 istek
        val now = System.currentTimeMillis()
        val wait = 1100 - (now - lastRequestTime)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        lastRequestTime = System.currentTimeMillis()

        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&limit=$limit" +
                    "&accept-language=tr&countrycodes=tr&viewbox=28.0,40.6,30.2,39.4&bounded=1"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "GoIt-Bursa-Transit/1.0 (Android; OSM Nominatim)")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<List<OsmPlace>>(str)
                cache[key] = parsed
                return@withContext parsed
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
