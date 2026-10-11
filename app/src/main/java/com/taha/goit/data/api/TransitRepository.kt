package com.taha.goit.data.api

import com.taha.goit.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.*

class TransitRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val baseUrl = "https://bursakartapi.abys-web.com"
    private val originHeader = "https://www.bursakart.com.tr"
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    // Basit bellek içi önbellek: ağ hatasında son başarılı veri kullanılır (offline dayanıklılık)
    private val memoryCache = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> cacheGet(key: String): T? = memoryCache[key] as? T

    private fun <T> cachePut(key: String, value: T) {
        if (memoryCache.size > 120) memoryCache.clear() // sınırlı tut
        memoryCache[key] = value as Any
    }

    // 1. Arama (Hat veya Durak)
    suspend fun searchRouteAndStation(keyword: String): List<SearchItem> = withContext(Dispatchers.IO) {
        val cacheKey = "search:$keyword"
        try {
            val bodyObj = buildJsonObject { put("keyword", keyword) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/routeandstation")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext cacheGet<List<SearchItem>>(cacheKey) ?: emptyList()
                val str = resp.body?.string() ?: return@withContext cacheGet<List<SearchItem>>(cacheKey) ?: emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<SearchItem>>>(str)
                val result = parsed.result ?: emptyList()
                cachePut(cacheKey, result)
                return@withContext result
            }
        } catch (e: Exception) {
            cacheGet<List<SearchItem>>(cacheKey) ?: emptyList()
        }
    }

    // 2. Canlı Otobüs Konumları (Plaka, Hız, Enlem, Boylam, İstikamet, Yolcu)
    suspend fun getRealtimeBuses(keyword: String): List<BusRealtimeLocation> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = buildJsonObject { put("keyword", keyword) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/realtimedata")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<BusRealtimeLocation>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 3. Hattın Durak Listesi (Sıra, Ad, Koordinat, Yön)
    suspend fun getRouteStops(routeCode: Int): List<RouteStop> = withContext(Dispatchers.IO) {
        val cacheKey = "stops:$routeCode"
        try {
            val bodyObj = buildJsonObject { put("routeCode", routeCode) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/routestat")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext cacheGet<List<RouteStop>>(cacheKey) ?: emptyList()
                val str = resp.body?.string() ?: return@withContext cacheGet<List<RouteStop>>(cacheKey) ?: emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<RouteStop>>>(str)
                val result = parsed.result ?: emptyList()
                cachePut(cacheKey, result)
                return@withContext result
            }
        } catch (e: Exception) {
            cacheGet<List<RouteStop>>(cacheKey) ?: emptyList()
        }
    }

    // 4. Hattın Yol Koordinatları (Haritaya Güzergah Çizmek İçin)
    suspend fun getRouteCoordinates(routeCode: String): List<RouteCoordinate> = withContext(Dispatchers.IO) {
        val cacheKey = "coords:$routeCode"
        try {
            val bodyObj = buildJsonObject { put("keyword", routeCode) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/routecoordinate")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext cacheGet<List<RouteCoordinate>>(cacheKey) ?: emptyList()
                val str = resp.body?.string() ?: return@withContext cacheGet<List<RouteCoordinate>>(cacheKey) ?: emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<RouteCoordinate>>>(str)
                val result = parsed.result ?: emptyList()
                cachePut(cacheKey, result)
                return@withContext result
            }
        } catch (e: Exception) {
            cacheGet<List<RouteCoordinate>>(cacheKey) ?: emptyList()
        }
    }

    // 5. Durağa Yaklaşan Canlı Otobüsler ve Kalan Dakikalar (Live ETA)
    suspend fun getStationRemainingTime(stationId: Int): List<StopRemainingTime> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = buildJsonObject { put("keyword", stationId) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/stationremainingtime")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<StopRemainingTime>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 6. Bir Duraktan Geçen Tüm Hatlar
    suspend fun getRoutesByStop(stationId: Int): List<RouteByStopItem> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = buildJsonObject { put("keyword", stationId) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/RouteByStop")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<RouteByStopItem>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 8. Yakındaki Duraklar (Konuma Göre En Yakın 20 Durak) - Otobüsüm Nerede
    suspend fun getNearbyStations(lat: Double, lng: Double): List<ActiveStationItem> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = buildJsonObject {
                put("enlem", lat)
                put("boylam", lng)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/static/activestations")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<ActiveStationItem>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 9. Durak Hareket Saatleri (Tarife) - ScheduleByRoute
    suspend fun getStopSchedule(stationId: Int, weekDay: Int): List<ScheduleEntry> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = buildJsonObject {
                put("stopId", stationId)
                put("weekDay", weekDay)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/static/ScheduleByRoute")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<ScheduleEntry>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 10. Hat Ücreti - routeprice
    suspend fun getRoutePrice(routeCode: String): List<CardPrice> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = buildJsonObject { put("keyword", routeCode) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/routeprice")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<CardPrice>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Fiyatlar seyrek değişir; bellek önbelleğiyle tekrar tekrar ağa gitmeyi önler. */
    suspend fun getRoutePriceCached(routeCode: String): List<CardPrice> {
        val key = "price:$routeCode"
        cacheGet<List<CardPrice>>(key)?.let { return it }
        val fresh = getRoutePrice(routeCode)
        if (fresh.isNotEmpty()) cachePut(key, fresh)
        return fresh
    }

    // 7. A'dan B'ye Rota Planlama Motoru (Multimodal Trip Planner)
    // Ring hatlar (38 gibi) "R" yönü kullanır; G/D hatlar ise yön filtrelidir.
    // Aynı hat üzerinden: originIdx < destIdx kontrolü (tek yön sıralaması).
    suspend fun planTrip(originStopId: Int, destStopId: Int): List<RoutePlan> = withContext(Dispatchers.IO) {
        try {
            val originRoutes = getRoutesByStop(originStopId)
            val destRoutes = getRoutesByStop(destStopId)

            val originRouteNos = originRoutes.mapNotNull { it.routeNo }.toSet()
            val commonRoutes = destRoutes.filter { originRouteNos.contains(it.routeNo) }

            val plans = mutableListOf<RoutePlan>()
            val usedRouteNos = mutableSetOf<Int>()

            for (common in commonRoutes) {
                val routeNo = common.routeNo ?: continue
                val stops = getRouteStops(routeNo)

                // Ring hat: "R" yönünde tek sıra. G/D: her iki yön de denenir.
                val directions = stops.mapNotNull { it.direction }.distinct()
                for (dir in directions) {
                    if (dir == "G" || dir == "D" || dir == "R") {
                        val dirStops = stops.filter { it.direction == dir }.sortedBy { it.sequence }
                        val originIdx = dirStops.indexOfFirst { it.stopId == originStopId }
                        val destIdx = dirStops.indexOfFirst { it.stopId == destStopId }
                        if (originIdx != -1 && destIdx != -1 && originIdx != destIdx) {
                            val tripStops: List<RouteStop>
                            val stopCount: Int
                            if (originIdx < destIdx) {
                                tripStops = dirStops.subList(originIdx, destIdx + 1)
                                stopCount = tripStops.size - 1
                            } else if (dir == "R") {
                                // Ring hatta geriye doğru: döngüyü tamamlar
                                tripStops = dirStops.subList(originIdx, dirStops.size) + dirStops.subList(0, destIdx + 1)
                                stopCount = tripStops.size - 1
                            } else {
                                continue // G/D yönünde ters yön: bu yönde plan yok
                            }
                            val duration = max(stopCount * 2, 5)
                            if (usedRouteNos.add(routeNo)) {
                                plans.add(
                                    RoutePlan(
                                        originStop = dirStops[originIdx],
                                        destinationStop = dirStops[destIdx],
                                        routeCode = common.routeCode ?: "",
                                        routeName = common.routeName ?: "",
                                        totalStops = stopCount,
                                        estimatedDurationMinutes = duration,
                                        intermediateStops = tripStops
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Tek aktarmalı alternatifler (doğrudan hat yoksa veya azsa)
            if (plans.size < 2) {
                plans.addAll(findTransferPlans(originStopId, destStopId, plans.map { it.routeCode }.toSet()))
            }

            return@withContext plans.sortedBy { it.estimatedDurationMinutes }.take(6)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Tek aktarmalı rota alternatifi bulur: origin -> transfer -> dest. */
    private suspend fun findTransferPlans(
        originStopId: Int,
        destStopId: Int,
        alreadyFound: Set<String>
    ): List<RoutePlan> {
        val result = mutableListOf<RoutePlan>()
        val originRoutes = getRoutesByStop(originStopId)
        val destRoutes = getRoutesByStop(destStopId)
        val destRouteNos = destRoutes.mapNotNull { it.routeNo }.toSet()

        // Origin'den geçen her hat için: hattın duraklarından birinden dest'e giden hat var mı?
        for (originRoute in originRoutes.take(8)) {
            val originNo = originRoute.routeNo ?: continue
            val originRouteCode = originRoute.routeCode ?: continue
            if (alreadyFound.contains(originRouteCode)) continue

            val firstLegStops = getRouteStops(originNo)
            val firstDir = firstLegStops.mapNotNull { it.direction }.firstOrNull() ?: continue
            val firstLeg = firstLegStops.filter { it.direction == firstDir }.sortedBy { it.sequence }
            val originIdx = firstLeg.indexOfFirst { it.stopId == originStopId }
            if (originIdx == -1) continue

            // İlk ayağın sonraki duraklarını tara (en fazla 25 durak)
            for (i in (originIdx + 1) until minOf(firstLeg.size, originIdx + 26)) {
                val transferCandidate = firstLeg[i]
                val transferId = transferCandidate.stopId ?: continue
                val transferRoutes = getRoutesByStop(transferId)
                val secondRoute = transferRoutes.firstOrNull {
                    it.routeNo != null && destRouteNos.contains(it.routeNo) && it.routeCode != originRouteCode
                } ?: continue

                val secondNo = secondRoute.routeNo ?: continue
                val secondStops = getRouteStops(secondNo)
                val secondDirs = secondStops.mapNotNull { it.direction }.distinct()
                var found = false
                for (dir in secondDirs) {
                    val sLeg = secondStops.filter { it.direction == dir }.sortedBy { it.sequence }
                    val tIdx = sLeg.indexOfFirst { it.stopId == transferId }
                    val dIdx = sLeg.indexOfFirst { it.stopId == destStopId }
                    if (tIdx != -1 && dIdx != -1 && tIdx < dIdx) {
                        val firstCount = i - originIdx
                        val secondCount = dIdx - tIdx
                        val total = firstCount + secondCount
                        result.add(
                            RoutePlan(
                                originStop = firstLeg[originIdx],
                                destinationStop = sLeg[dIdx],
                                routeCode = "${originRouteCode} → ${secondRoute.routeCode}",
                                routeName = "",
                                totalStops = total,
                                estimatedDurationMinutes = max(total * 2 + 3, 10),
                                intermediateStops = firstLeg.subList(originIdx, i + 1) + sLeg.subList(tIdx, dIdx + 1),
                                isTransfer = true,
                                firstRouteCode = originRouteCode,
                                secondRouteCode = secondRoute.routeCode ?: "",
                                transferStop = transferCandidate
                            )
                        )
                        found = true
                        break
                    }
                }
                if (found) break
            }
            if (result.size >= 3) break
        }
        return result
    }

    // Mesafe Hesaplama (Haversine - Metre Cinsinden)
    companion object {
        fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadius = 6371000.0 // Metre
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return earthRadius * c
        }
    }
}
