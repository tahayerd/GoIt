package com.taha.goit.data.api

import android.util.Log
import com.taha.goit.BuildConfig
import com.taha.goit.data.local.StationDatabase
import com.taha.goit.data.model.LocalStation
import com.taha.goit.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Planlamada yürüme adayı durak: noktaya yürüme mesafesiyle birlikte. */
data class StopCandidate(val stop: LocalStation, val walkMeters: Double)

/** O-21: Aktarma planı oluşturma girdi parametreleri */
data class TransferPlanInput(
    val firstStops: List<RouteStop>,
    val secondStops: List<RouteStop>,
    val firstCode: String,
    val firstNo: Int,
    val firstRouteName: String?,
    val secondCode: String,
    val secondNo: Int,
    val secondRouteName: String?,
    val firstDir: String,
    val secondDir: String,
    val transferStop: RouteStop,
    val walkToM: Double,
    val walkFromM: Double,
    val transferWalkM: Double
)

class TransitRepository(
    private val client: OkHttpClient = defaultClient
) {
    companion object {
        private const val TAG = "TransitRepository"

        // Planlama sabitleri
        const val INITIAL_WAIT_MIN = 3
        const val TRANSFER_WAIT_MIN = 5
        const val MIN_WALK_LEG_M = 40.0
        const val MAX_TRANSFER_WALK_M = 400.0

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        }

        @Volatile
        private var instance: TransitRepository? = null

        fun getInstance(client: OkHttpClient = defaultClient): TransitRepository {
            return instance ?: synchronized(this) {
                instance ?: TransitRepository(client).also { instance = it }
            }
        }

        // Mesafe Hesaplama (Haversine - Metre Cinsinden)
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

        /** O-8: Yürüme süresi hesabı (yavaş/normal/hızlı profil desteği) */
        fun walkMinutes(meters: Double, profile: String = "normal"): Int {
            if (meters <= 0.0) return 0
            val factor = when (profile) {
                "slow" -> 15.0 // ~4.0 km/h
                "fast" -> 10.0 // ~6.0 km/h
                else -> 12.0   // ~5.0 km/h (normal)
            }
            return max(1, (meters / 1000.0 * factor).roundToInt())
        }

        /** Y-13: Yoğun saate göre dinamik otobüs hız profili (km/h). */
        fun busSpeedKmh(hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Double {
            return when (hourOfDay) {
                7, 8, 9, 17, 18, 19 -> 17.0
                in 0..5, 23 -> 26.0
                else -> 22.0
            }
        }

        /** Y-13: Otobüs bacağı süresi (yoğun saate göre dinamik hız modeli) */
        fun busLegMinutes(
            stops: List<RouteStop>,
            hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        ): Int {
            if (stops.size < 2) return 3
            var meters = 0.0
            for (i in 0 until stops.size - 1) {
                val a = stops[i]; val b = stops[i + 1]
                val la1 = a.latitude?.toDoubleOrNull() ?: continue
                val lo1 = a.longitude?.toDoubleOrNull() ?: continue
                val la2 = b.latitude?.toDoubleOrNull() ?: continue
                val lo2 = b.longitude?.toDoubleOrNull() ?: continue
                meters += calculateDistanceMeters(la1, lo1, la2, lo2)
            }
            val drive = meters / 1000.0 / busSpeedKmh(hourOfDay) * 60.0
            val dwell = (stops.size - 1) * 0.33
            return max(3, (drive + dwell).roundToInt())
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val baseUrl = BuildConfig.BURULAS_API_BASE_URL
    private val originHeader = BuildConfig.BURULAS_ORIGIN_HEADER
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    // Y-6: Thread-safe LRU Cache + TTL (15 dakika)
    private data class CacheEntry(val data: Any, val timestamp: Long)
    private val cacheLock = Any()
    private val maxCacheSize = 200
    private val defaultTtlMs = 15L * 60 * 1000L

    private val memoryCache = object : LinkedHashMap<String, CacheEntry>(maxCacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>?): Boolean {
            return size > maxCacheSize
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> cacheGet(key: String, ttlMs: Long = defaultTtlMs): T? = synchronized(cacheLock) {
        val entry = memoryCache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            memoryCache.remove(key)
            return null
        }
        return entry.data as? T
    }

    private fun <T : Any> cachePut(key: String, value: T) = synchronized(cacheLock) {
        memoryCache[key] = CacheEntry(value, System.currentTimeMillis())
    }

    // O-7: Hata ayrımı için durum kaydı
    @Volatile
    private var lastPlanError: String? = null

    fun getLastPlanError(): String? = lastPlanError

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
                if (!resp.isSuccessful) {
                    Log.w(TAG, "searchRouteAndStation HTTP ${resp.code}")
                    return@withContext cacheGet<List<SearchItem>>(cacheKey) ?: emptyList()
                }
                val str = resp.body?.string() ?: return@withContext cacheGet<List<SearchItem>>(cacheKey) ?: emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<SearchItem>>>(str)
                val result = parsed.result ?: emptyList()
                cachePut(cacheKey, result)
                return@withContext result
            }
        } catch (e: Exception) {
            Log.w(TAG, "searchRouteAndStation failed: ${e.message}", e)
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
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getRealtimeBuses HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<BusRealtimeLocation>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRealtimeBuses failed: ${e.message}", e)
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
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getRouteStops HTTP ${resp.code}")
                    return@withContext cacheGet<List<RouteStop>>(cacheKey) ?: emptyList()
                }
                val str = resp.body?.string() ?: return@withContext cacheGet<List<RouteStop>>(cacheKey) ?: emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<RouteStop>>>(str)
                val result = parsed.result ?: emptyList()
                cachePut(cacheKey, result)
                return@withContext result
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRouteStops failed: ${e.message}", e)
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
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getRouteCoordinates HTTP ${resp.code}")
                    return@withContext cacheGet<List<RouteCoordinate>>(cacheKey) ?: emptyList()
                }
                val str = resp.body?.string() ?: return@withContext cacheGet<List<RouteCoordinate>>(cacheKey) ?: emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<RouteCoordinate>>>(str)
                val result = parsed.result ?: emptyList()
                cachePut(cacheKey, result)
                return@withContext result
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRouteCoordinates failed: ${e.message}", e)
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
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getStationRemainingTime HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<StopRemainingTime>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "getStationRemainingTime failed: ${e.message}", e)
            emptyList()
        }
    }

    // 6. Bir Duraktan Geçen Tüm Hatlar
    suspend fun getRoutesByStop(stationId: Int): List<RouteByStopItem> = withContext(Dispatchers.IO) {
        val cacheKey = "rbs:$stationId"
        try {
            val bodyObj = buildJsonObject { put("keyword", stationId) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/RouteByStop")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getRoutesByStop HTTP ${resp.code}")
                    return@withContext cacheGet<List<RouteByStopItem>>(cacheKey) ?: emptyList()
                }
                val str = resp.body?.string() ?: return@withContext cacheGet<List<RouteByStopItem>>(cacheKey) ?: emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<RouteByStopItem>>>(str)
                val result = parsed.result ?: emptyList()
                cachePut(cacheKey, result)
                return@withContext result
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRoutesByStop failed: ${e.message}", e)
            cacheGet<List<RouteByStopItem>>(cacheKey) ?: emptyList()
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
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getNearbyStations HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<ActiveStationItem>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "getNearbyStations failed: ${e.message}", e)
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
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getStopSchedule HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<ScheduleEntry>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "getStopSchedule failed: ${e.message}", e)
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
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getRoutePrice HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<CardPrice>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRoutePrice failed: ${e.message}", e)
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
    suspend fun planTrip(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        maxAccess: Double = 800.0,
        maxTransferWalk: Double = MAX_TRANSFER_WALK_M,
        walkProfile: String = "normal"
    ): List<RoutePlan> = withContext(Dispatchers.IO) {
        lastPlanError = null
        try {
            val originCandidates = StationDatabase.walkCandidates(originLat, originLng, maxAccess)
                .map { StopCandidate(it.first, it.second) }
            val destCandidates = StationDatabase.walkCandidates(destLat, destLng, maxAccess)
                .map { StopCandidate(it.first, it.second) }

            if (originCandidates.isEmpty() || destCandidates.isEmpty()) {
                lastPlanError = "NO_ROUTE"
                return@withContext emptyList()
            }

            val directPlans = mutableListOf<RoutePlan>()
            val transferPlans = mutableListOf<RoutePlan>()

            // Doğrudan (aktarmasız) planlar
            for (o in originCandidates) {
                for (d in destCandidates) {
                    if (o.stop.i == d.stop.i) continue
                    directPlans.addAll(
                        findDirectPlans(
                            originStopId = o.stop.i,
                            destStopId = d.stop.i,
                            walkToMeters = o.walkMeters,
                            walkFromMeters = d.walkMeters,
                            walkProfile = walkProfile
                        )
                    )
                }
            }

            val bestDirect = directPlans
                .sortedBy { it.estimatedDurationMinutes }
                .distinctBy { it.routeCode }
                .take(4)

            // Aktarma seçenekleri
            val transferResults = findTransferPlans(
                originCandidates = originCandidates,
                destCandidates = destCandidates,
                excludeRouteCodes = bestDirect.map { it.routeCode }.toSet(),
                maxTransferWalk = maxTransferWalk,
                walkProfile = walkProfile
            )

            val bestTransfer = transferResults
                .sortedBy { it.estimatedDurationMinutes }
                .distinctBy { "${it.firstRouteCode}|${it.secondRouteCode}" }
                .take(3)
            transferPlans.addAll(bestTransfer)

            // Doğrudan hatlar daima aktarmalıların üstünde; kendi içinde süre sıralı
            val plans = bestDirect + transferPlans

            if (plans.isEmpty()) {
                lastPlanError = "NO_ROUTE"
                return@withContext emptyList()
            }
            plans
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "planTrip socket timeout", e)
            lastPlanError = "TIMEOUT"
            emptyList()
        } catch (e: UnknownHostException) {
            Log.w(TAG, "planTrip network error (unknown host)", e)
            lastPlanError = "NETWORK"
            emptyList()
        } catch (e: IOException) {
            Log.w(TAG, "planTrip IO error", e)
            lastPlanError = "NETWORK"
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "planTrip unexpected failure: ${e.message}", e)
            lastPlanError = "NO_ROUTE"
            emptyList()
        }
    }

    /** Tek binişli (aktarmasız) planlar: aynı hat üzerinde origin -> dest. */
    private suspend fun findDirectPlans(
        originStopId: Int,
        destStopId: Int,
        walkToMeters: Double,
        walkFromMeters: Double,
        walkProfile: String = "normal"
    ): List<RoutePlan> {
        val originRoutes = getRoutesByStop(originStopId)
        val destRoutes = getRoutesByStop(destStopId)
        val originRouteNos = originRoutes.mapNotNull { it.routeNo }.toSet()
        val commonRoutes = destRoutes.filter { originRouteNos.contains(it.routeNo) }

        val plans = mutableListOf<RoutePlan>()
        val usedRouteNos = mutableSetOf<Int>()

        for (common in commonRoutes) {
            val routeNo = common.routeNo ?: continue
            if (!usedRouteNos.add(routeNo)) continue
            val stops = getRouteStops(routeNo)
            val directions = stops.mapNotNull { it.direction }.distinct()
            for (dir in directions) {
                if (dir != "G" && dir != "D" && dir != "R") continue
                val dirStops = stops.filter { it.direction == dir }.sortedBy { it.sequence }
                val originIdx = dirStops.indexOfFirst { it.stopId == originStopId }
                val destIdx = dirStops.indexOfFirst { it.stopId == destStopId }
                if (originIdx == -1 || destIdx == -1 || originIdx == destIdx) continue
                val tripStops: List<RouteStop>
                val stopCount: Int
                if (originIdx < destIdx) {
                    tripStops = dirStops.subList(originIdx, destIdx + 1)
                    stopCount = tripStops.size - 1
                } else if (dir == "R") {
                    tripStops = dirStops.subList(originIdx, dirStops.size) + dirStops.subList(1, destIdx + 1)
                    stopCount = tripStops.size - 1
                } else {
                    continue
                }
                val busMinutes = busLegMinutes(tripStops)
                val walkToMin = walkMinutes(walkToMeters, walkProfile)
                val walkFromMin = walkMinutes(walkFromMeters, walkProfile)
                val totalMin = INITIAL_WAIT_MIN + busMinutes + walkToMin + walkFromMin

                val legs = buildList {
                    if (walkToMeters >= MIN_WALK_LEG_M) {
                        add(
                            PlanLeg(
                                kind = "walk",
                                walkMeters = walkToMeters,
                                minutes = walkToMin,
                                alightStop = tripStops.firstOrNull()
                            )
                        )
                    }
                    add(
                        PlanLeg(
                            kind = "bus",
                            routeCode = common.routeCode,
                            routeNo = routeNo,
                            routeTitle = common.routeName,
                            direction = dir,
                            boardStop = tripStops.firstOrNull(),
                            alightStop = tripStops.lastOrNull(),
                            stops = tripStops,
                            minutes = busMinutes
                        )
                    )
                    if (walkFromMeters >= MIN_WALK_LEG_M) {
                        add(
                            PlanLeg(
                                kind = "walk",
                                walkMeters = walkFromMeters,
                                minutes = walkFromMin,
                                boardStop = tripStops.lastOrNull()
                            )
                        )
                    }
                }

                plans.add(
                    RoutePlan(
                        originStop = tripStops.first(),
                        destinationStop = tripStops.last(),
                        routeCode = common.routeCode ?: "",
                        routeName = common.routeName ?: "",
                        totalStops = stopCount,
                        estimatedDurationMinutes = totalMin,
                        intermediateStops = tripStops,
                        legs = legs,
                        walkToMeters = walkToMeters,
                        walkFromMeters = walkFromMeters,
                        firstLegDirection = dir
                    )
                )
            }
        }
        return plans
    }

    /** İki binişli (1 aktarmalı) planlar: Hat 1 → Ortak Durak / Yakın Durak → Hat 2. */
    private suspend fun findTransferPlans(
        originCandidates: List<StopCandidate>,
        destCandidates: List<StopCandidate>,
        excludeRouteCodes: Set<String>,
        maxTransferWalk: Double,
        walkProfile: String = "normal"
    ): List<RoutePlan> = supervisorScope {
        val destStopIds = destCandidates.map { it.stop.i }.toSet()
        val destRoutesMap = mutableMapOf<Int, List<RouteByStopItem>>()
        val destSemaphore = Semaphore(4)
        val destJobs = destCandidates.take(6).map { cand ->
            async {
                destSemaphore.withPermit {
                    val r = getRoutesByStop(cand.stop.i)
                    synchronized(destRoutesMap) { destRoutesMap[cand.stop.i] = r }
                }
            }
        }
        destJobs.awaitAll()

        val destRouteNos = destRoutesMap.values.flatten()
            .mapNotNull { it.routeNo }
            .toSet()

        val result = mutableListOf<RoutePlan>()
        val tried = mutableSetOf<String>()

        val topOrigins = originCandidates.take(4)
        for (cand in topOrigins) {
            val originStop = cand.stop
            val walkToM = cand.walkMeters
            val firstRoutes = getRoutesByStop(originStop.i)

            for (firstRoute in firstRoutes) {
                val firstNo = firstRoute.routeNo ?: continue
                val firstCode = firstRoute.routeCode ?: continue
                if (excludeRouteCodes.contains(firstCode)) continue
                if (destRouteNos.contains(firstNo)) continue

                val stops1 = getRouteStops(firstNo)
                val directions1 = stops1.mapNotNull { it.direction }.distinct()

                for (dir in directions1) {
                    if (dir != "G" && dir != "D" && dir != "R") continue
                    val dirStops = stops1.filter { it.direction == dir }.sortedBy { it.sequence }
                    val originIdx = dirStops.indexOfFirst { it.stopId == originStop.i }
                    if (originIdx == -1) continue

                    val scanLimit = minOf(dirStops.size, originIdx + 30)
                    for (i in (originIdx + 1) until scanLimit) {
                        val hopStop = dirStops[i]
                        val hopStopId = hopStop.stopId ?: continue
                        if (destStopIds.contains(hopStopId)) continue

                        val hopRoutes = getRoutesByStop(hopStopId)
                        val candidateSecond = hopRoutes.filter {
                            val rNo = it.routeNo
                            rNo != null && rNo != firstNo && destRouteNos.contains(rNo)
                        }

                        for (secondRoute in candidateSecond) {
                            val secondNo = secondRoute.routeNo ?: continue
                            val secondCode = secondRoute.routeCode ?: continue
                            if (excludeRouteCodes.contains(secondCode)) continue

                            val key = "$firstCode|$secondCode"
                            if (tried.contains(key)) continue

                            val stops2 = getRouteStops(secondNo)
                            val directions2 = stops2.mapNotNull { it.direction }.distinct()

                            for (sdir in directions2) {
                                if (sdir != "G" && sdir != "D" && sdir != "R") continue
                                val sLeg = stops2.filter { it.direction == sdir }.sortedBy { it.sequence }
                                val hopIdx = sLeg.indexOfFirst { it.stopId == hopStopId }
                                if (hopIdx == -1) continue

                                val directDest = destCandidates.firstOrNull { d ->
                                    val di = sLeg.indexOfFirst { it.stopId == d.stop.i }
                                    di != -1 && di > hopIdx
                                }
                                if (directDest != null) {
                                    val di = sLeg.indexOfFirst { it.stopId == directDest.stop.i }
                                    val p = buildTransferPlan(
                                        TransferPlanInput(
                                            firstStops = dirStops.subList(originIdx, i + 1),
                                            secondStops = sLeg.subList(hopIdx, di + 1),
                                            firstCode = firstCode,
                                            firstNo = firstNo,
                                            firstRouteName = firstRoute.routeName,
                                            secondCode = secondCode,
                                            secondNo = secondNo,
                                            secondRouteName = secondRoute.routeName,
                                            firstDir = dir,
                                            secondDir = sdir,
                                            transferStop = hopStop,
                                            walkToM = walkToM,
                                            walkFromM = directDest.walkMeters,
                                            transferWalkM = 0.0
                                        ),
                                        walkProfile = walkProfile
                                    )
                                    result.add(p)
                                    tried.add(key)
                                    break
                                }

                                val alightScan = minOf(sLeg.size, hopIdx + 40)
                                var found = false
                                for (j in (hopIdx + 1) until alightScan) {
                                    val alight = sLeg[j]
                                    val ala = alight.latitude?.toDoubleOrNull() ?: continue
                                    val alo = alight.longitude?.toDoubleOrNull() ?: continue

                                    val walkFromCandidate = destCandidates.firstOrNull { d ->
                                        calculateDistanceMeters(ala, alo, d.stop.la, d.stop.lo) <= maxTransferWalk
                                    }
                                    if (walkFromCandidate != null) {
                                        val transferWalk = if (alight.stopId != walkFromCandidate.stop.i) {
                                            calculateDistanceMeters(
                                                ala, alo,
                                                walkFromCandidate.stop.la, walkFromCandidate.stop.lo
                                            )
                                        } else 0.0

                                        val p = buildTransferPlan(
                                            TransferPlanInput(
                                                firstStops = dirStops.subList(originIdx, i + 1),
                                                secondStops = sLeg.subList(hopIdx, j + 1),
                                                firstCode = firstCode,
                                                firstNo = firstNo,
                                                firstRouteName = firstRoute.routeName,
                                                secondCode = secondCode,
                                                secondNo = secondNo,
                                                secondRouteName = secondRoute.routeName,
                                                firstDir = dir,
                                                secondDir = sdir,
                                                transferStop = hopStop,
                                                walkToM = walkToM,
                                                walkFromM = walkFromCandidate.walkMeters,
                                                transferWalkM = transferWalk
                                            ),
                                            walkProfile = walkProfile
                                        )
                                        result.add(p)
                                        tried.add(key)
                                        found = true
                                        break
                                    }
                                }
                                if (found) break
                            }
                            if (tried.contains(key)) break
                        }
                        if (tried.contains("$firstCode|*")) break
                    }
                    if (result.size >= 4) break
                }
                if (result.size >= 4) break
            }
            if (result.size >= 4) break
        }
        result
    }

    private fun buildTransferPlan(
        input: TransferPlanInput,
        walkProfile: String = "normal"
    ): RoutePlan {
        val firstCount = input.firstStops.size - 1
        val secondCount = input.secondStops.size - 1
        val total = firstCount + secondCount
        val firstBusMin = busLegMinutes(input.firstStops)
        val secondBusMin = busLegMinutes(input.secondStops)
        val busMinutes = firstBusMin + secondBusMin
        val walkToMin = walkMinutes(input.walkToM, walkProfile)
        val walkFromMin = walkMinutes(input.walkFromM, walkProfile)
        val transferWalkMin = walkMinutes(input.transferWalkM, walkProfile)
        val totalMin = INITIAL_WAIT_MIN + busMinutes + walkToMin + walkFromMin +
                transferWalkMin + TRANSFER_WAIT_MIN

        val legs = buildList {
            if (input.walkToM >= MIN_WALK_LEG_M) {
                add(PlanLeg(kind = "walk", walkMeters = input.walkToM, minutes = walkToMin, alightStop = input.firstStops.firstOrNull()))
            }
            add(
                PlanLeg(
                    kind = "bus",
                    routeCode = input.firstCode,
                    routeNo = input.firstNo,
                    routeTitle = input.firstRouteName,
                    direction = input.firstDir,
                    boardStop = input.firstStops.firstOrNull(),
                    alightStop = input.firstStops.lastOrNull(),
                    stops = input.firstStops,
                    minutes = firstBusMin
                )
            )
            if (input.transferWalkM >= MIN_WALK_LEG_M) {
                add(PlanLeg(kind = "walk", walkMeters = input.transferWalkM, minutes = transferWalkMin, boardStop = input.secondStops.firstOrNull()))
            }
            add(
                PlanLeg(
                    kind = "bus",
                    routeCode = input.secondCode,
                    routeNo = input.secondNo,
                    routeTitle = input.secondRouteName,
                    direction = input.secondDir,
                    boardStop = input.secondStops.firstOrNull(),
                    alightStop = input.secondStops.lastOrNull(),
                    stops = input.secondStops,
                    minutes = secondBusMin
                )
            )
            if (input.walkFromM >= MIN_WALK_LEG_M) {
                add(PlanLeg(kind = "walk", walkMeters = input.walkFromM, minutes = walkFromMin, boardStop = input.secondStops.lastOrNull()))
            }
        }

        return RoutePlan(
            originStop = input.firstStops.first(),
            destinationStop = input.secondStops.last(),
            routeCode = "${input.firstCode} → ${input.secondCode}",
            routeName = "",
            totalStops = total,
            estimatedDurationMinutes = totalMin,
            intermediateStops = input.firstStops + input.secondStops.drop(1),
            isTransfer = true,
            firstRouteCode = input.firstCode,
            secondRouteCode = input.secondCode,
            transferStop = input.transferStop,
            legs = legs,
            walkToMeters = input.walkToM,
            walkFromMeters = input.walkFromM,
            transferWalkMeters = input.transferWalkM,
            secondLegBoardStop = input.secondStops.firstOrNull(),
            firstLegDirection = input.firstDir,
            secondLegDirection = input.secondDir
        )
    }

    /** "Sadece yürü" planı (≤2 km). */
    fun walkOnlyPlan(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        walkProfile: String = "normal"
    ): RoutePlan {
        val meters = calculateDistanceMeters(originLat, originLng, destLat, destLng)
        val minutes = walkMinutes(meters, walkProfile)
        return RoutePlan(
            originStop = RouteStop(stopName = "Kalkış"),
            destinationStop = RouteStop(stopName = "Varış"),
            routeCode = "Yürüyüş",
            routeName = "",
            totalStops = 0,
            estimatedDurationMinutes = minutes,
            intermediateStops = emptyList(),
            legs = listOf(PlanLeg(kind = "walk", walkMeters = meters, minutes = minutes)),
            isWalkOnly = true,
            walkOnlyMeters = meters
        )
    }
}
