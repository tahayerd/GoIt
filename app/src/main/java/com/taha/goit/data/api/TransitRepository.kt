package com.taha.goit.data.api

import com.taha.goit.data.local.StationDatabase
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
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Planlamada yürüme adayı durak: noktaya yürüme mesafesiyle birlikte. */
data class StopCandidate(val stop: LocalStation, val walkMeters: Double)

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
                if (!resp.isSuccessful) return@withContext cacheGet<List<RouteByStopItem>>(cacheKey) ?: emptyList()
                val str = resp.body?.string() ?: return@withContext cacheGet<List<RouteByStopItem>>(cacheKey) ?: emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<RouteByStopItem>>>(str)
                val result = parsed.result ?: emptyList()
                cachePut(cacheKey, result)
                return@withContext result
            }
        } catch (e: Exception) {
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

    // 7. A'dan B'ye Rota Planlama Motoru (Multimodal Trip Planner, Moovit tarzı)
    // Nokta tabanlı: kalkış/varış serbest koordinat; yürüme aday durakları çözülür.
    // Doğrudan hatlar HER ZAMAN aktarmalıların üstünde sıralanır (süreye göre).
    suspend fun planTrip(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ): List<RoutePlan> = withContext(Dispatchers.IO) {
        try {
            val originCandidates = StationDatabase.walkCandidates(originLat, originLng)
            val destCandidates = StationDatabase.walkCandidates(destLat, destLng)

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
                            originStop = o.stop,
                            destStop = d.stop,
                            originLat = originLat,
                            originLng = originLng,
                            destLat = destLat,
                            destLng = destLng,
                            walkToMeters = o.walkMeters,
                            walkFromMeters = d.walkMeters
                        )
                    )
                }
            }

            val bestDirect = directPlans
                .sortedBy { it.estimatedDurationMinutes }
                .distinctBy { it.routeCode }
                .take(4)

            // Aktarma seçenekleri (varış durağı, aktarmadan sonra yürüme ile de çözülebilir)
            val transferResults = findTransferPlans(
                originLat = originLat,
                originLng = originLng,
                destLat = destLat,
                destLng = destLng,
                originCandidates = originCandidates,
                destCandidates = destCandidates,
                excludeRouteCodes = bestDirect.map { it.routeCode }.toSet()
            )

            val bestTransfer = transferResults
                .sortedBy { it.estimatedDurationMinutes }
                .distinctBy { "${it.firstRouteCode}|${it.secondRouteCode}" }
                .take(3)
            transferPlans.addAll(bestTransfer)

            // Doğrudan hatlar daima aktarmalıların üstünde; kendi içinde süre sıralı
            val plans = bestDirect + transferPlans

            if (plans.isEmpty()) {
                return@withContext emptyList()
            }
            plans
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Tek binişli (aktarmasız) planlar: aynı hat üzerinde origin -> dest. */
    private suspend fun findDirectPlans(
        originStopId: Int,
        destStopId: Int,
        originStop: LocalStation,
        destStop: LocalStation,
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        walkToMeters: Double,
        walkFromMeters: Double
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
                val busMinutes = max(stopCount * 2, 5)
                val walkToMin = walkMinutes(walkToMeters)
                val walkFromMin = walkMinutes(walkFromMeters)
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
                break // aynı hat için ilk geçerli yön yeterli
            }
        }
        return plans
    }

    /**
     * Tek aktarmalı planlar: yürüme erişimi -> hat 1 -> (yürüme) aktarma -> hat 2 -> varış.
     * Varış durağı, son binişten sonra yürüme ile de çözülebilir.
     */
    private suspend fun findTransferPlans(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        originCandidates: List<Pair<LocalStation, Double>>,
        destCandidates: List<Pair<LocalStation, Double>>,
        excludeRouteCodes: Set<String>
    ): List<RoutePlan> {
        val result = mutableListOf<RoutePlan>()
        val destStopIds = destCandidates.map { it.stop.i }.toSet()
        val destStopById = destCandidates.associateBy { it.stop.i }
        val destRouteNos = mutableSetOf<Int>()
        for (d in destCandidates) {
            destRouteNos.addAll(getRoutesByStop(d.stop.i).mapNotNull { it.routeNo })
        }
        if (destRouteNos.isEmpty()) return result

        // 1) İlk bacağın adayları: origin'e yürünebilir duraklardan geçen hatlar
        val firstLegCandidates = mutableListOf<Triple<LocalStation, Double, RouteByStopItem>>()
        for (o in originCandidates) {
            val routes = getRoutesByStop(o.stop.i)
            for (r in routes) {
                if (r.routeNo == null) continue
                val code = r.routeCode ?: continue
                if (excludeRouteCodes.contains(code)) continue
                firstLegCandidates.add(Triple(o.stop, o.walkMeters, r))
            }
        }
        // En umut verici adayları önce dene (çok fazla ağ çağrısı yapmamak için sınır)
        val tried = mutableSetOf<String>()

        for ((originStop, walkToM, firstRoute) in firstLegCandidates.take(14)) {
            val firstNo = firstRoute.routeNo ?: continue
            val firstCode = firstRoute.routeCode ?: continue
            val firstStops = getRouteStops(firstNo)
            val firstDirs = firstStops.mapNotNull { it.direction }.distinct()
            for (dir in firstDirs) {
                if (dir != "G" && dir != "D" && dir != "R") continue
                val dirStops = firstStops.filter { it.direction == dir }.sortedBy { it.sequence }
                val originIdx = dirStops.indexOfFirst { it.stopId == originStop.i }
                if (originIdx == -1) continue

                // İlk bacağın sonraki duraklarını tara (en fazla 28 durak ileri)
                val scanLimit = minOf(dirStops.size, originIdx + 29)
                for (i in (originIdx + 1) until scanLimit) {
                    val hopStop = dirStops[i]
                    val hopId = hopStop.stopId ?: continue
                    // Dest durağına ulaştık mı? (doğrudan binilebilir) -> atla (doğrudan planlarda var)
                    val hopRoutes = getRoutesByStop(hopId)
                    val secondRoute = hopRoutes.firstOrNull {
                        it.routeNo != null &&
                            destRouteNos.contains(it.routeNo) &&
                            it.routeCode != firstCode
                    } ?: continue

                    val secondNo = secondRoute.routeNo ?: continue
                    val secondCode = secondRoute.routeCode ?: continue
                    val key = "$firstCode|$secondCode"
                    if (tried.contains(key)) break

                    val secondStops = getRouteStops(secondNo)
                    val secondDirs = secondStops.mapNotNull { it.direction }.distinct()
                    for (sdir in secondDirs) {
                        val sLeg = secondStops.filter { it.direction == sdir }.sortedBy { it.sequence }
                        val hopIdx = sLeg.indexOfFirst { it.stopId == hopId }
                        if (hopIdx == -1) continue

                        // 2a) Son durak adayı doğrudan binişte: hop -> destStop
                        val directDest = destCandidates.firstOrNull { d ->
                            val di = sLeg.indexOfFirst { it.stopId == d.stop.i }
                            di != -1 && di > hopIdx
                        }
                        if (directDest != null) {
                            val di = sLeg.indexOfFirst { it.stopId == directDest.stop.i }
                            val p = buildTransferPlan(
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
                                originStop = originStop,
                                destStop = directDest.stop,
                                transferStop = hopStop,
                                walkToM = walkToM,
                                walkFromM = directDest.walkMeters,
                                transferWalkM = 0.0,
                                originLat = originLat,
                                originLng = originLng,
                                destLat = destLat,
                                destLng = destLng
                            )
                            result.add(p)
                            tried.add(key)
                            break
                        }

                        // 2b) Varış durağı yürüme ile: ikinci hataktan inilecek durağı tara
                        // (varış adayına 400 m içinde bir durak bul)
                        val alightScan = minOf(sLeg.size, hopIdx + 40)
                        var found = false
                        for (j in (hopIdx + 1) until alightScan) {
                            val alight = sLeg[j]
                            val alightId = alight.stopId ?: continue
                            // Bu durak bir varış adayına yürüme mesafesinde mi?
                            var walkFromCandidate: Pair<LocalStation, Double>? = null
                            if (destStopIds.contains(alightId)) {
                                walkFromCandidate = destStopById[alightId]
                            } else {
                                // Yürüme ile varış: en yakın varış adayına mesafe
                                for (d in destCandidates) {
                                    val dist = calculateDistanceMeters(
                                        alight.latitude?.toDoubleOrNull() ?: continue,
                                        alight.longitude?.toDoubleOrNull() ?: continue,
                                        d.stop.la, d.stop.lo
                                    )
                                    if (dist <= MAX_TRANSFER_WALK_M) {
                                        walkFromCandidate = d
                                        break
                                    }
                                }
                            }
                            if (walkFromCandidate == null) continue

                            val between = mutableListOf<Pair<Double, Double>>()
                            val alightLat = alight.latitude?.toDoubleOrNull()
                            val alightLng = alight.longitude?.toDoubleOrNull()
                            if (alightLat != null && alightLng != null) {
                                between.add(alightLat to alightLng)
                            }
                            val transferWalk = if (between.isNotEmpty()) {
                                calculateDistanceMeters(
                                    between.first().first, between.first().second,
                                    walkFromCandidate.stop.la, walkFromCandidate.stop.lo
                                )
                            } else 0.0

                            val p = buildTransferPlan(
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
                                originStop = originStop,
                                destStop = walkFromCandidate.stop,
                                transferStop = hopStop,
                                walkToM = walkToM,
                                walkFromM = walkFromCandidate.second,
                                transferWalkM = transferWalk,
                                originLat = originLat,
                                originLng = originLng,
                                destLat = destLat,
                                destLng = destLng
                            )
                            result.add(p)
                            tried.add(key)
                            found = true
                            break
                        }
                        if (found) break
                    }
                    if (tried.contains(key)) break
                }
                if (tried.contains("$firstCode|*")) break
            }
            if (result.size >= 4) break
        }
        return result
    }

    private fun buildTransferPlan(
        firstStops: List<RouteStop>,
        secondStops: List<RouteStop>,
        firstCode: String,
        firstNo: Int,
        firstRouteName: String?,
        secondCode: String,
        secondNo: Int,
        secondRouteName: String?,
        firstDir: String,
        secondDir: String,
        originStop: LocalStation,
        destStop: LocalStation,
        transferStop: RouteStop,
        walkToM: Double,
        walkFromM: Double,
        transferWalkM: Double,
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ): RoutePlan {
        val firstCount = firstStops.size - 1
        val secondCount = secondStops.size - 1
        val total = firstCount + secondCount
        val busMinutes = max(total * 2, 8)
        val walkToMin = walkMinutes(walkToM)
        val walkFromMin = walkMinutes(walkFromM)
        val transferWalkMin = walkMinutes(transferWalkM)
        val totalMin = INITIAL_WAIT_MIN + busMinutes + walkToMin + walkFromMin +
                transferWalkMin + TRANSFER_WAIT_MIN

        val legs = buildList {
            if (walkToM >= MIN_WALK_LEG_M) {
                add(PlanLeg(kind = "walk", walkMeters = walkToM, minutes = walkToMin, alightStop = firstStops.firstOrNull()))
            }
            add(
                PlanLeg(
                    kind = "bus",
                    routeCode = firstCode,
                    routeNo = firstNo,
                    routeTitle = firstRouteName,
                    direction = firstDir,
                    boardStop = firstStops.firstOrNull(),
                    alightStop = firstStops.lastOrNull(),
                    stops = firstStops,
                    minutes = firstCount * 2
                )
            )
            if (transferWalkM >= MIN_WALK_LEG_M) {
                add(PlanLeg(kind = "walk", walkMeters = transferWalkM, minutes = transferWalkMin, boardStop = secondStops.firstOrNull()))
            }
            add(
                PlanLeg(
                    kind = "bus",
                    routeCode = secondCode,
                    routeNo = secondNo,
                    routeTitle = secondRouteName,
                    direction = secondDir,
                    boardStop = secondStops.firstOrNull(),
                    alightStop = secondStops.lastOrNull(),
                    stops = secondStops,
                    minutes = secondCount * 2
                )
            )
            if (walkFromM >= MIN_WALK_LEG_M) {
                add(PlanLeg(kind = "walk", walkMeters = walkFromM, minutes = walkFromMin, boardStop = secondStops.lastOrNull()))
            }
        }

        return RoutePlan(
            originStop = firstStops.first(),
            destinationStop = secondStops.last(),
            routeCode = "$firstCode → $secondCode",
            routeName = "",
            totalStops = total,
            estimatedDurationMinutes = totalMin,
            intermediateStops = firstStops + secondStops.drop(1),
            isTransfer = true,
            firstRouteCode = firstCode,
            secondRouteCode = secondCode,
            transferStop = transferStop,
            legs = legs,
            walkToMeters = walkToM,
            walkFromMeters = walkFromM,
            transferWalkMeters = transferWalkM,
            secondLegBoardStop = secondStops.firstOrNull(),
            firstLegDirection = firstDir,
            secondLegDirection = secondDir
        )
    }

    /** "Sadece yürü" planı (≤2 km). */
    fun walkOnlyPlan(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ): RoutePlan {
        val meters = calculateDistanceMeters(originLat, originLng, destLat, destLng)
        val minutes = walkMinutes(meters)
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

    /** Yürüme süresi: 12 dk/km (Moovit benzeri). */
    private fun walkMinutes(meters: Double): Int {
        if (meters <= 0.0) return 0
        return max(1, Math.round(meters / 1000.0 * 12.0).toInt())
    }

    // Mesafe Hesaplama (Haversine - Metre Cinsinden)
    companion object {
        // Planlama sabitleri (Moovit benzeri varsayılanlar)
        private const val INITIAL_WAIT_MIN = 3
        private const val TRANSFER_WAIT_MIN = 5
        private const val MIN_WALK_LEG_M = 40.0
        private const val MAX_TRANSFER_WALK_M = 400.0

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
