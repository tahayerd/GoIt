package com.taha.goit.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
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
 *  - Google embed servisi üzerinden mekan adı veya CID ile kesin koordinat çözümü
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
        "(?:[?&](?:q|query|ll|center)=)([+-]?\\d{1,2}\\.\\d+)(?:,|%20|\\s)+([+-]?\\d{1,3}\\.\\d+)"
    )

    // geo:lat,lng
    private val GEO_PATTERN = Pattern.compile("^geo:([+-]?\\d{1,2}\\.\\d+),([+-]?\\d{1,3}\\.\\d+)")

    // data parametresinde CID (hex)
    private val DATA_CID_PATTERN = Pattern.compile("!1s0x[0-9a-fA-F]+:0x([0-9a-fA-F]+)")

    // query parametresinde CID
    private val QUERY_CID_PATTERN = Pattern.compile("[?&]cid=([0-9]+)")

    // Embed HTML koordinat çifti regex'i
    private val EMBED_COORD_PATTERN = Pattern.compile("\\[(-?\\d{1,3}\\.\\d+),\\s*(-?\\d{1,3}\\.\\d+)\\]")

    // Bellek içi koordinat önbelleği
    private val embedCache = ConcurrentHashMap<String, ParsedLocation>()

    /** Düz metin (koordinat veya link) ayrıştırır; ağ gerektirebilir (kısa linkler / embed çözümü). */
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

        // 3. URL içeriyorsa veya URL ise
        val urlRegex = Regex("https?://\\S+")
        val urlMatch = urlRegex.find(trimmed)?.value ?: if (isMapsUrl(trimmed)) trimmed else null
        if (urlMatch != null) {
            var url = urlMatch
            // Kısa link ise yönlendirmeyi GET ile takip et
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

    private suspend fun parseUrl(url: String): ParsedLocation? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val placeName = extractPlaceName(url) ?: extractPlaceName(decoded)

        // a) !3d..!4d.. (En güvenilir protobuf noktası)
        val mProto = PROTO_PATTERN.matcher(decoded)
        if (mProto.find()) {
            val lat = mProto.group(1)?.toDoubleOrNull()
            val lng = mProto.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null && isValid(lat, lng)) {
                return ParsedLocation(lat, lng, label = placeName ?: "Google Haritalar")
            }
        }

        // b) ?q=lat,lng / query=lat,lng / ll=lat,lng
        val mQ = QUERY_COORD_PATTERN.matcher(decoded)
        if (mQ.find()) {
            val lat = mQ.group(1)?.toDoubleOrNull()
            val lng = mQ.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null && isValid(lat, lng)) {
                return ParsedLocation(lat, lng, label = placeName ?: "Google Haritalar")
            }
        }

        // c) CID veya Place Name ile Embed çözümü
        val cid = extractCid(url) ?: extractCid(decoded)
        if (cid != null || !placeName.isNullOrBlank()) {
            val embedLoc = resolveViaEmbed(cid, placeName)
            if (embedLoc != null) return embedLoc
        }

        // d) @lat,lng (Son çare fallback — yalnızca diğerleri bulunamadığında)
        val mAt = AT_PATTERN.matcher(decoded)
        if (mAt.find()) {
            val lat = mAt.group(1)?.toDoubleOrNull()
            val lng = mAt.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null && isValid(lat, lng)) {
                return ParsedLocation(lat, lng, label = placeName ?: "Google Haritalar")
            }
        }

        return null
    }

    private fun extractCid(url: String): String? {
        val mQuery = QUERY_CID_PATTERN.matcher(url)
        if (mQuery.find()) {
            return mQuery.group(1)
        }
        val mData = DATA_CID_PATTERN.matcher(url)
        if (mData.find()) {
            val hex = mData.group(1) ?: return null
            return runCatching {
                val unsignedLong = java.lang.Long.parseUnsignedLong(hex, 16)
                java.lang.Long.toUnsignedString(unsignedLong)
            }.getOrNull()
        }
        return null
    }

    private fun extractPlaceName(url: String): String? {
        // 1. /place/
        val placeIdx = url.indexOf("/place/")
        if (placeIdx != -1) {
            val sub = url.substring(placeIdx + 7)
            val endIdx = findSegmentEnd(sub)
            val raw = sub.substring(0, endIdx)
            val cleaned = cleanName(raw)
            if (cleaned.isNotEmpty()) return cleaned
        }

        // 2. /dir/
        val dirIdx = url.indexOf("/dir/")
        if (dirIdx != -1) {
            val sub = url.substring(dirIdx + 5)
            val cutIdx = findPathCut(sub)
            val path = sub.substring(0, cutIdx)
            val segments = path.split('/').filter { it.isNotBlank() }
            val lastSegment = segments.lastOrNull()
            if (lastSegment != null) {
                val cleaned = cleanName(lastSegment)
                if (cleaned.isNotEmpty()) return cleaned
            }
        }

        // 3. ?q= veya ?query= (koordinat değilse)
        val qIdx = url.indexOf("?q=")
        val queryIdx = if (qIdx != -1) qIdx + 3 else {
            val alt = url.indexOf("&q=")
            if (alt != -1) alt + 3 else {
                val q2 = url.indexOf("?query=")
                if (q2 != -1) q2 + 7 else {
                    val alt2 = url.indexOf("&query=")
                    if (alt2 != -1) alt2 + 7 else -1
                }
            }
        }
        if (queryIdx != -1) {
            val end = url.indexOf('&', queryIdx).let { if (it == -1) url.length else it }
            val raw = url.substring(queryIdx, end)
            if (!raw.matches(Regex("^[+-]?\\d{1,2}\\.\\d+.*"))) {
                val cleaned = cleanName(raw)
                if (cleaned.isNotEmpty()) return cleaned
            }
        }

        return null
    }

    private fun findSegmentEnd(s: String): Int {
        val at = s.indexOf("/@")
        val slash = s.indexOf('/')
        val q = s.indexOf('?')
        val data = s.indexOf("/data=")
        val candidates = listOf(at, slash, q, data).filter { it != -1 }
        return candidates.minOrNull() ?: s.length
    }

    private fun findPathCut(s: String): Int {
        val at = s.indexOf("/@")
        val q = s.indexOf('?')
        val data = s.indexOf("/data=")
        val atPlain = s.indexOf('@')
        val candidates = listOf(at, q, data, atPlain).filter { it != -1 }
        return candidates.minOrNull() ?: s.length
    }

    private fun cleanName(raw: String): String {
        val decoded = runCatching { URLDecoder.decode(raw.replace('+', ' '), "UTF-8") }.getOrDefault(raw)
        val trimmed = decoded.trim()
        return if (trimmed.startsWith("@") || trimmed.startsWith("data=")) "" else trimmed
    }

    private fun resolveViaEmbed(cid: String?, placeName: String?): ParsedLocation? {
        if (cid == null && placeName.isNullOrBlank()) return null
        val cacheKey = if (cid != null) "cid:$cid" else "name:${placeName!!.trim().lowercase()}"
        embedCache[cacheKey]?.let { return it }

        val url = if (cid != null) {
            "https://maps.google.com/maps?cid=$cid&output=embed&hl=tr"
        } else {
            val encodedName = runCatching { URLEncoder.encode(placeName, "UTF-8") }.getOrDefault(placeName!!)
            "https://maps.google.com/maps?q=$encodedName&output=embed&hl=tr"
        }

        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .get()
                .build()
            val html = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }
            val matcher = EMBED_COORD_PATTERN.matcher(html)
            val foundCoords = mutableListOf<Pair<Double, Double>>()
            while (matcher.find()) {
                val lat = matcher.group(1)?.toDoubleOrNull()
                val lng = matcher.group(2)?.toDoubleOrNull()
                if (lat != null && lng != null && isValid(lat, lng)) {
                    foundCoords.add(lat to lng)
                }
            }
            val target = foundCoords.firstOrNull { (lat, lng) ->
                lat in 39.5..40.5 && lng in 28.0..30.2
            } ?: foundCoords.firstOrNull()

            if (target != null) {
                val loc = ParsedLocation(target.first, target.second, label = placeName ?: "Google Haritalar")
                embedCache[cacheKey] = loc
                loc
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveRedirect(shortUrl: String): String? {
        return try {
            val req = Request.Builder()
                .url(shortUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .get()
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
