package com.taha.goit.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Google Maps linki ve serbest metin koordinat ayrıştırıcısı.
 *
 * Desteklenen formatlar:
 *  - "40.1832, 29.0647" veya "40.1832,29.0647"
 *  - geo:40.1832,29.0647?q=... veya geo:0,0?q=40.1832,29.0647(Etiket)
 *  - https://maps.google.com/?q=40.1832,29.0647
 *  - https://www.google.com/maps/place/.../@40.1832,29.0647,17z/...
 *  - https://www.google.com/maps/search/?api=1&query=40.1832,29.0647
 *  - https://www.google.com/maps/.../data=!3d40.1832!4d29.0647...
 *  - https://maps.app.goo.gl/... ve https://goo.gl/maps/... (HTTP yönlendirme takibi)
 */
object MapsLinkParser {

    data class ParsedLocation(
        val lat: Double,
        val lng: Double,
        val label: String? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // 40.1832, 29.0647 serbest metin regex'i
    private val COORD_PATTERN = Pattern.compile(
        "^\\s*([+-]?\\d{1,2}(?:\\.\\d+)?)[\\s,]+([+-]?\\d{1,3}(?:\\.\\d+)?)\\s*$"
    )

    // @lat,lng
    private val AT_PATTERN = Pattern.compile("@([+-]?\\d{1,2}\\.\\d+),([+-]?\\d{1,3}\\.\\d+)")

    // !3dlat!4dlng (Google Maps protobuf data parametresi)
    private val PROTO_PATTERN = Pattern.compile("!3d([+-]?\\d{1,2}\\.\\d+)!4d([+-]?\\d{1,3}\\.\\d+)")

    // ?q=lat,lng veya ?query=lat,lng veya ll=lat,lng
    private val QUERY_COORD_PATTERN = Pattern.compile(
        "(?:[?&](?:q|query|ll|center)=)([+-]?\\d{1,2}\\.\\d+)[,%20]+([+-]?\\d{1,3}\\.\\d+)"
    )

    // geo:lat,lng
    private val GEO_PATTERN = Pattern.compile("^geo:([+-]?\\d{1,2}\\.\\d+),([+-]?\\d{1,3}\\.\\d+)")

    /** Düz metin (koordinat veya link) ayrıştırır; ağ gerektirebilir (kısa linkler). */
    suspend fun parse(input: String): ParsedLocation? = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return@withContext null

        // 1. Doğrudan "lat, lng" metni
        val mCoord = COORD_PATTERN.matcher(trimmed)
        if (mCoord.matches()) {
            val lat = mCoord.group(1)?.toDoubleOrNull()
            val lng = mCoord.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null && isValid(lat, lng)) {
                return@withContext ParsedLocation(lat, lng, label = "Harita Konumu")
            }
        }

        // 2. geo: URI
        if (trimmed.startsWith("geo:", ignoreCase = true)) {
            val fromGeo = parseGeoUri(trimmed)
            if (fromGeo != null) return@withContext fromGeo
        }

        // 3. Google Maps URL'si
        if (isMapsUrl(trimmed)) {
            var url = trimmed
            // Kısa link ise yönlendirmeyi çöz
            if (url.contains("goo.gl/") || url.contains("maps.app.goo.gl/")) {
                url = resolveRedirect(url) ?: url
            }
            return@withContext parseUrl(url)
        }

        null
    }

    private fun isMapsUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun parseGeoUri(uri: String): ParsedLocation? {
        // geo:0,0?q=40.18,29.06(Etiket) desteği
        val qIdx = uri.indexOf("?q=")
        if (qIdx != -1) {
            val queryPart = uri.substring(qIdx + 3)
            val parenStart = queryPart.indexOf('(')
            val parenEnd = queryPart.indexOf(')')
            val label = if (parenStart != -1 && parenEnd > parenStart) {
                queryPart.substring(parenStart + 1, parenEnd)
            } else null
            val coordPart = if (parenStart != -1) queryPart.substring(0, parenStart) else queryPart
            val m = COORD_PATTERN.matcher(coordPart)
            if (m.matches()) {
                val lat = m.group(1)?.toDoubleOrNull()
                val lng = m.group(2)?.toDoubleOrNull()
                if (lat != null && lng != null && isValid(lat, lng)) {
                    return ParsedLocation(lat, lng, label = label ?: "Harita Konumu")
                }
            }
        }

        val m = GEO_PATTERN.matcher(uri)
        if (m.find()) {
            val lat = m.group(1)?.toDoubleOrNull()
            val lng = m.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null && isValid(lat, lng)) {
                return ParsedLocation(lat, lng, label = "Harita Konumu")
            }
        }
        return null
    }

    private fun parseUrl(url: String): ParsedLocation? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)

        // a) !3d..!4d..
        val mProto = PROTO_PATTERN.matcher(decoded)
        if (mProto.find()) {
            val lat = mProto.group(1)?.toDoubleOrNull()
            val lng = mProto.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null && isValid(lat, lng)) {
                return ParsedLocation(lat, lng, label = extractPlaceName(decoded) ?: "Google Haritalar")
            }
        }

        // b) ?q=lat,lng
        val mQ = QUERY_COORD_PATTERN.matcher(decoded)
        if (mQ.find()) {
            val lat = mQ.group(1)?.toDoubleOrNull()
            val lng = mQ.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null && isValid(lat, lng)) {
                return ParsedLocation(lat, lng, label = extractPlaceName(decoded) ?: "Google Haritalar")
            }
        }

        // c) @lat,lng
        val mAt = AT_PATTERN.matcher(decoded)
        if (mAt.find()) {
            val lat = mAt.group(1)?.toDoubleOrNull()
            val lng = mAt.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null && isValid(lat, lng)) {
                return ParsedLocation(lat, lng, label = extractPlaceName(decoded) ?: "Google Haritalar")
            }
        }

        return null
    }

    private fun extractPlaceName(url: String): String? {
        val placeIdx = url.indexOf("/place/")
        if (placeIdx != -1) {
            val sub = url.substring(placeIdx + 7)
            val slash = sub.indexOf('/')
            val rawName = if (slash != -1) sub.substring(0, slash) else sub
            val cleaned = rawName.replace('+', ' ').trim()
            if (cleaned.isNotEmpty() && !cleaned.startsWith("@")) return cleaned
        }
        return null
    }

    private fun resolveRedirect(shortUrl: String): String? {
        return try {
            val req = Request.Builder()
                .url(shortUrl)
                .head()
                .build()
            client.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isValid(lat: Double, lng: Double): Boolean {
        return lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)
    }
}
