package com.taha.goit.service

import com.taha.goit.data.api.TransitRepository
import com.taha.goit.ui.formatDistance
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Tek bir konum düzeltmesi (Android bağımsız gösterim). */
data class JourneyFix(
    val lat: Double,
    val lng: Double,
    val speedKmh: Float,
    val timeMs: Long,
    val accuracyMeters: Float = 0f
)

/** Durakta bekleme aşamasında canlı ETA bilgisi. */
data class LiveEta(
    val minutes: Int? = null,
    val text: String? = null,
    val updatedAtMs: Long = 0L,
    /** Yaklaşan aracın plaka / klima / erişilebilirlik notu (varsa). */
    val vehicleNote: String? = null
)

/** Kullanıcı ayarlarından gelen eşikler. */
data class JourneySettings(
    val alightStopsThreshold: Int = 1,
    val alightDistanceMeters: Int = 250,
    val walkingSpeedProfile: String = "normal"
)

/** Motorun servis katmanına döndürdüğü karar ve metinler. */
data class JourneyDecision(
    val state: JourneyTracker.RuntimeState,
    val notification: String,
    val advanceReason: String? = null,
    val alert: String? = null,
    val approachWarning: String? = null,
    val finished: Boolean = false
)

/** Enlem/boylam noktası. */
data class GeoPoint(val lat: Double, val lng: Double)

/** Konum geometrisi yardımcıları (saf Kotlin, JVM testlerinde doğrulanabilir). */
object Geo {
    private const val EARTH_RADIUS_M = 6371000.0

    /** Haversine mesafesi (metre). */
    fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** Çokgenin durak noktaları arası kümülatif mesafeleri (metre); ilk eleman 0'dır. */
    fun cumulativeMeters(points: List<GeoPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + meters(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
        }
        return out
    }

    /**
     * p noktasının a→b kenarına parametrik izdüşümü (0..1).
     * Yerel eşdikdörtgen (equirectangular) yaklaşımı kullanılır; kısa kenarlar için hatası ihmal edilebilir.
     */
    fun segmentParam(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val kx = 111320.0 * cos(Math.toRadians(a.lat))
        val ky = 110540.0
        val abx = (b.lng - a.lng) * kx
        val aby = (b.lat - a.lat) * ky
        val apx = (p.lng - a.lng) * kx
        val apy = (p.lat - a.lat) * ky
        val len2 = abx * abx + aby * aby
        if (len2 <= 1e-6) return 0.0
        return ((apx * abx + apy * aby) / len2).coerceIn(0.0, 1.0)
    }

    /** Noktanın polyline üzerindeki izdüşümü: hat boyunca kümülatif mesafe + hattan dik uzaklık. */
    fun projectOnPolyline(p: GeoPoint, points: List<GeoPoint>): Projection {
        if (points.isEmpty()) return Projection(0.0, Double.MAX_VALUE)
        if (points.size == 1) return Projection(0.0, meters(p.lat, p.lng, points[0].lat, points[0].lng))
        val cum = cumulativeMeters(points)
        var bestAlong = 0.0
        var bestPerp = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val t = segmentParam(p, points[i], points[i + 1])
            val segLen = cum[i + 1] - cum[i]
            val along = cum[i] + segLen * t
            val projLat = points[i].lat + (points[i + 1].lat - points[i].lat) * t
            val projLng = points[i].lng + (points[i + 1].lng - points[i].lng) * t
            val perp = meters(p.lat, p.lng, projLat, projLng)
            if (perp < bestPerp) {
                bestPerp = perp
                bestAlong = along
            }
        }
        return Projection(bestAlong, bestPerp)
    }

    data class Projection(val alongMeters: Double, val perpendicularMeters: Double)

    /** Yön tarifi sonucu: sıradaki dönüş metni ve dönüşe kalan mesafe (metre). */
    data class WalkGuidance(val hint: String, val turnDistanceMeters: Double)

    /**
     * Kullanıcı konumuna göre adım adım yürüyüş yönergesi üretir.
     * Yol geometrisi (OSRM yürüme rotası) üzerinden ilk belirgin dönüş noktasını bulur.
     */
    fun walkGuidance(p: GeoPoint, path: List<GeoPoint>): WalkGuidance? {
        if (path.size < 2) return null
        val proj = projectOnPolyline(p, path)
        val cum = cumulativeMeters(path)
        val total = cum.last()
        val remainingToEnd = (total - proj.alongMeters).coerceAtLeast(0.0)

        // Kullanıcının bulunduğu kenarı bul
        var segIdx = 0
        for (i in 0 until path.size - 1) {
            if (cum[i + 1] >= proj.alongMeters) {
                segIdx = i
                break
            }
            segIdx = i
        }

        // İleriye doğru ilk belirgin dönüşü ara
        for (i in (segIdx + 1) until path.size - 1) {
            val angle = turnAngle(path[i - 1], path[i], path[i + 1])
            if (Math.abs(angle) >= 30.0) {
                val dist = (cum[i] - proj.alongMeters).coerceAtLeast(0.0)
                val hint = if (Math.abs(angle) >= 120.0) {
                    if (angle > 0) "Keskin sola dön" else "Keskin sağa dön"
                } else {
                    if (angle > 0) "Sola dön" else "Sağa dön"
                }
                return WalkGuidance(hint, dist)
            }
        }
        return WalkGuidance("Hedefe doğru düz devam", remainingToEnd)
    }

    /** a→b ve b→c vektörleri arasındaki dönüş açısı (derece). Pozitif: sola, negatif: sağa. */
    private fun turnAngle(a: GeoPoint, b: GeoPoint, c: GeoPoint): Double {
        val kx = 111320.0 * cos(Math.toRadians(b.lat))
        val ky = 110540.0
        val ax = (b.lng - a.lng) * kx
        val ay = (b.lat - a.lat) * ky
        val bx = (c.lng - b.lng) * kx
        val by = (c.lat - b.lat) * ky
        val cross = ax * by - ay * bx
        val dot = ax * bx + ay * by
        return Math.toDegrees(Math.atan2(cross, dot))
    }
}

/**
 * Canlı yolculuk durum makinesi (V4).
 *
 * Android API'si kullanmaz; bu sayede gerçek GPS izleriyle JVM birim testlerinde
 * doğrulanabilir. Konum dinleme, bildirim ve canlı ETA sorgulama JourneyService'in
 * sorumluluğundadır; bu sınıf yalnızca "nerede olduğumuz, ne yapmalıyız" kararını verir.
 *
 * Aşamalar: walk (yürüyüş) → wait (durakta bekle, otomatik biniş algıla) →
 *           ride (araçta, tek yönlü durak sayacı, iniş alarmı) → arrived (varış).
 */
class JourneyEngine(
    private val payload: JourneyPayload,
    private val settings: JourneySettings = JourneySettings()
) {
    companion object {
        /** Yürüyüş aşaması bu yarıçapa girince durakta sayılır. */
        const val WALK_ARRIVE_RADIUS_M = 40.0

        /** Rota hattına yakınlık toleransı (biniş algılama koridoru). */
        const val CORRIDOR_TOLERANCE_M = 90.0

        /** GPS gürültüsüne karşı bir durağı "geçildi" sayma toleransı. */
        const val STOP_PASS_TOLERANCE_M = 35.0

        /** İniş: durağa bu mesafede kesin indin kabul edilir. */
        const val AUTO_DROP_NEAR_M = 100.0

        /** İniş: son durak sıradaysa bu mesafede inilmiş sayılır. */
        const val AUTO_DROP_LAST_STOP_M = 250.0

        /** Duraksız (bozuk veri) rota için mesafe tabanlı iniş. */
        const val AUTO_DROP_DISTANCE_ONLY_M = 150.0

        /** Bu doğruluğun üzerindeki fix'lerde aşama/iniş kararı verilmez, yalnızca HUD güncellenir. */
        const val LOW_ACCURACY_M = 120f

        private const val WALK_PROGRESS_MIN_SEPARATION_M = 1.0
    }

    private var stageIndex = 0

    // Yürüyüş aşaması
    private var walkStartDistance: Double? = null

    // Bekleme aşaması: otomatik biniş algılama sayaçları
    private var fastFarStreak = 0
    private var slowFarStreak = 0
    private var corridorStreak = 0

    // Araç aşaması: tek yönlü ilerleme (GPS zıplamasında geri saymaz)
    private var progressMeters = 0.0
    private var lastStopIdx = -1

    // Uyarı durumları
    private var approachWarned = false
    private var alightAlerted = false
    private var etaImminentWarned = false

    private var liveEta: LiveEta? = null
    private var lastFix: JourneyFix? = null
    private val advanceLog = mutableListOf<String>()

    val currentStageIndex: Int get() = stageIndex
    val currentStage: JourneyStage? get() = payload.stages.getOrNull(stageIndex)
    val isFinished: Boolean get() = stageIndex >= payload.stages.size

    fun updateEta(eta: LiveEta?) {
        liveEta = eta
    }

    /** Konum düzeltmesi geldiğinde çağrılır; HUD + bildirim kararını döndürür. */
    fun onFix(fix: JourneyFix): JourneyDecision {
        lastFix = fix
        val stage = payload.stages.getOrNull(stageIndex) ?: return arrivedFallback()
        advanceLog.clear()

        // Doğruluğu düşük fix'ler aşama atlatmaz; yalnızca ekrandaki metin güncellenir.
        val lowAccuracy = fix.accuracyMeters > 0f && fix.accuracyMeters > LOW_ACCURACY_M
        if (lowAccuracy) {
            val decision = when (stage.type) {
                "walk" -> walkStateOnly(stage, fix)
                "wait" -> waitStateOnly(stage, fix)
                "ride" -> handleRide(stage, fix, evaluateTransitions = false, updateProgress = false)
                else -> stageStartDecision(stage)
            }
            return finalize(decision)
        }

        val decision = when (stage.type) {
            "walk" -> handleWalk(stage, fix)
            "wait" -> handleWait(stage, fix)
            "ride" -> handleRide(stage, fix)
            else -> stageStartDecision(stage)
        }
        return finalize(decision)
    }

    /**
     * Konum değişmeden HUD/bildirim metnini yeniden üretir (canlı ETA güncellemesi,
     * aşama geçişi veya servis yeniden bağlandığında).
     */
    fun snapshot(): JourneyDecision {
        val stage = payload.stages.getOrNull(stageIndex) ?: return arrivedFallback()
        val fix = lastFix
        val decision = when {
            fix != null && stage.type == "wait" -> waitStateOnly(stage, fix)
            fix != null && stage.type == "ride" -> handleRide(stage, fix, evaluateTransitions = false, updateProgress = false)
            fix != null && stage.type == "walk" -> walkStateOnly(stage, fix)
            else -> stageStartDecision(stage)
        }
        return finalize(decision)
    }

    private fun finalize(decision: JourneyDecision): JourneyDecision {
        val reason = advanceLog.joinToString(" → ").ifEmpty { null }
        return if (reason != null) decision.copy(advanceReason = reason) else decision
    }

    // ---------------------------------------------------------------- Walk

    private fun handleWalk(stage: JourneyStage, fix: JourneyFix): JourneyDecision {
        val dist = Geo.meters(fix.lat, fix.lng, stage.targetLat, stage.targetLng)
        val start = walkStartDistance
        if (start == null || dist > start) walkStartDistance = dist

        if (dist <= WALK_ARRIVE_RADIUS_M) {
            advanceLog += "Yürüyüş tamamlandı (${formatDistance(dist)})"
            stageIndex++
            resetStageState()
            val next = payload.stages.getOrNull(stageIndex) ?: return arrivedFallback()
            return when (next.type) {
                "walk" -> handleWalk(next, fix)
                "wait" -> handleWait(next, fix)
                "ride" -> handleRide(next, fix)
                else -> stageStartDecision(next)
            }
        }
        return walkStateOnly(stage, fix)
    }

    private fun walkStateOnly(stage: JourneyStage, fix: JourneyFix): JourneyDecision {
        val dist = Geo.meters(fix.lat, fix.lng, stage.targetLat, stage.targetLng)
        val init = walkStartDistance ?: dist
        val progress = if (init > WALK_PROGRESS_MIN_SEPARATION_M) {
            ((init - dist) / init).toFloat().coerceIn(0f, 1f)
        } else 0f
        val minutes = TransitRepository.walkMinutes(dist, settings.walkingSpeedProfile)
        val distText = formatDistance(dist)

        // Adım adım yönerge: OSRM yürüme geometrisi varsa sıradaki dönüşü bildir
        val guidance = if (stage.path.size >= 2) {
            Geo.walkGuidance(GeoPoint(fix.lat, fix.lng), stage.path.map { GeoPoint(it.lat, it.lng) })
        } else null
        val hintLabel = guidance?.let { g ->
            if (g.turnDistanceMeters <= 25.0) g.hint else "${g.hint} • ${formatDistance(g.turnDistanceMeters)}"
        }
        val detail = if (hintLabel != null) {
            "$hintLabel • Hedefe $distText (~$minutes dk)"
        } else {
            "Kalan mesafe: $distText (~$minutes dk) • ${stage.targetName}"
        }

        return JourneyDecision(
            state = JourneyTracker.RuntimeState(
                stageIndex = stageIndex,
                stageType = "walk",
                title = stage.title.ifEmpty { "${stage.targetName} yürü" },
                detail = detail,
                distanceToNextMeters = dist,
                progress = progress,
                walkMinutes = minutes,
                walkHint = hintLabel,
                walkTurnDistanceMeters = guidance?.turnDistanceMeters
            ),
            notification = if (hintLabel != null) {
                "${stage.title} • $hintLabel • $distText"
            } else {
                "${stage.title} • $distText (~$minutes dk)"
            }
        )
    }

    // ---------------------------------------------------------------- Wait

    private fun handleWait(stage: JourneyStage, fix: JourneyFix): JourneyDecision {
        val distFromStop = Geo.meters(fix.lat, fix.lng, stage.targetLat, stage.targetLng)

        // Biniş algılama: hız + duraktan uzaklaşma + rota koridoru boyunca ilerleme
        val rideStops = nextRideStops()
        val corridorAlong = if (rideStops.size >= 2 && distFromStop > CORRIDOR_TOLERANCE_M) {
            val proj = Geo.projectOnPolyline(GeoPoint(fix.lat, fix.lng), rideStops)
            if (proj.perpendicularMeters <= CORRIDOR_TOLERANCE_M) proj.alongMeters else -1.0
        } else -1.0

        val fastFar = fix.speedKmh >= 12f && distFromStop >= 120
        val slowFar = fix.speedKmh >= 5f && distFromStop >= 80
        val onCorridor = corridorAlong >= 100.0 && fix.speedKmh >= 4f

        fastFarStreak = if (fastFar) fastFarStreak + 1 else 0
        slowFarStreak = if (slowFar) slowFarStreak + 1 else 0
        corridorStreak = if (onCorridor) corridorStreak + 1 else 0

        if (fix.speedKmh < 2f || distFromStop < 40) {
            fastFarStreak = 0
            slowFarStreak = 0
            corridorStreak = 0
        }

        val boarded = fastFarStreak >= 2 || slowFarStreak >= 4 || corridorStreak >= 2
        if (boarded) {
            val reason = when {
                fastFarStreak >= 2 -> "Araca bindin (hız ${fix.speedKmh.roundToInt()} km/h)"
                corridorStreak >= 2 -> "Araca bindin (rota üzerinde ilerleme)"
                else -> "Araca bindin"
            }
            advanceLog += reason
            stageIndex++
            resetStageState()
            val next = payload.stages.getOrNull(stageIndex) ?: return arrivedFallback()
            return when (next.type) {
                "walk" -> handleWalk(next, fix)
                "ride" -> handleRide(next, fix)
                "wait" -> handleWait(next, fix)
                else -> stageStartDecision(next)
            }
        }

        // Moovit "Your line is arriving!" davranışı: canlı ETA 1 dakikaya indiyse haber ver
        val etaMinutes = liveEta?.minutes
        var approach: String? = null
        if (!etaImminentWarned && etaMinutes != null && etaMinutes <= 1) {
            etaImminentWarned = true
            approach = "Hattın geliyor! • ${stage.targetName}"
        }
        return waitStateOnly(stage, fix).copy(approachWarning = approach)
    }

    private fun waitStateOnly(stage: JourneyStage, fix: JourneyFix): JourneyDecision {
        val route = stage.routeCode ?: "Otobüs"
        val etaText = liveEta?.text
        val hasEta = !etaText.isNullOrBlank() && etaText != "-"
        val etaPart = if (hasEta) "$route hattı ~$etaText içinde geliyor" else "$route hattı bekleniyor"
        val vehiclePart = liveEta?.vehicleNote?.takeIf { it.isNotBlank() }?.let { " • Araç: $it" } ?: ""
        val distFromStop = Geo.meters(fix.lat, fix.lng, stage.targetLat, stage.targetLng)
        val walkNote = if (distFromStop > WALK_ARRIVE_RADIUS_M) {
            " • Durağa ${formatDistance(distFromStop)}"
        } else ""
        return JourneyDecision(
            state = JourneyTracker.RuntimeState(
                stageIndex = stageIndex,
                stageType = "wait",
                title = stage.title.ifEmpty { "$route hattını bekle" },
                detail = "Durak: ${stage.targetName}$walkNote • $etaPart$vehiclePart",
                waitEtaMinutes = if (hasEta) etaText else null,
                distanceToTargetMeters = distFromStop
            ),
            notification = "$etaPart • Durak: ${stage.targetName}"
        )
    }

    // ---------------------------------------------------------------- Ride

    private fun handleRide(
        stage: JourneyStage,
        fix: JourneyFix,
        evaluateTransitions: Boolean = true,
        updateProgress: Boolean = true
    ): JourneyDecision {
        val stops = stage.stops
        val points = stops.map { GeoPoint(it.lat, it.lng) }
        val distToAlight = Geo.meters(fix.lat, fix.lng, stage.targetLat, stage.targetLng)
        val route = stage.routeCode ?: "Otobüs"

        var currentIdx = lastStopIdx.coerceAtLeast(0)
        var stopsRemaining = 0
        var progress = 0f
        var estMinutes: Int
        var progressRatio = 1.0

        if (stops.size >= 2) {
            val cum = Geo.cumulativeMeters(points)
            val total = cum.last()
            val proj = Geo.projectOnPolyline(GeoPoint(fix.lat, fix.lng), points)
            if (updateProgress) progressMeters = max(progressMeters, proj.alongMeters)

            var idx = 0
            for (i in stops.indices) {
                if (cum[i] <= progressMeters + STOP_PASS_TOLERANCE_M) idx = i
            }
            currentIdx = maxOf(currentIdx, idx)
            lastStopIdx = currentIdx
            stopsRemaining = (stops.size - 1 - currentIdx).coerceAtLeast(0)
            progress = (currentIdx.toFloat() / (stops.size - 1)).coerceIn(0f, 1f)
            progressRatio = progress.toDouble()
            val remainingMeters = (total - cum[currentIdx]).coerceAtLeast(0.0)
            estMinutes = estimateBusMinutes(remainingMeters)
        } else {
            stopsRemaining = if (distToAlight <= AUTO_DROP_DISTANCE_ONLY_M) 0 else 1
            estMinutes = estimateBusMinutes(distToAlight)
        }

        val nextStopName = if (stops.size >= 2) {
            stops.getOrNull((currentIdx + 1).coerceAtMost(stops.size - 1))?.name ?: stage.targetName
        } else stage.targetName

        val passedList = if (stops.size >= 2) stops.take(currentIdx).map { it.name } else emptyList()
        val upcomingList = if (stops.size >= 2) stops.drop(currentIdx + 1).map { it.name } else emptyList()
        val detail = if (stops.size >= 2) {
            "Sıradaki: $nextStopName • $stopsRemaining durak kaldı (~$estMinutes dk)"
        } else {
            "Kalan mesafe: ${formatDistance(distToAlight)} (~$estMinutes dk) • ${stage.targetName}"
        }

        var approach: String? = null
        var alert: String? = null
        if (evaluateTransitions) {
            // Erken uyarı: hedefe 2 durak kaldı
            if (stops.size >= 2 && stopsRemaining == 2 && !approachWarned) {
                approachWarned = true
                approach = "Yaklaşıyorsun • 2 durak kaldı • ${stage.targetName}"
            }

            // İniş alarmı (titreşimli): ayarlanan durak sayısı veya mesafe eşiği
            val stopsRule = stops.size >= 2 && stopsRemaining <= settings.alightStopsThreshold.coerceAtLeast(0)
            val distanceRule = distToAlight <= settings.alightDistanceMeters.toDouble() &&
                    (stops.size < 2 || progressRatio >= 0.4)
            if ((stopsRule || distanceRule) && !alightAlerted) {
                alightAlerted = true
                alert = if (stopsRemaining <= 1) {
                    "Hazır ol! Sıradaki durak iniş durağın • ${stage.targetName}"
                } else {
                    "İnişe $stopsRemaining durak kaldı • ${stage.targetName}"
                }
            }

            if (shouldAutoAlight(stops.size, stopsRemaining, progressRatio, distToAlight)) {
                advanceLog += "İndin: ${stage.targetName}"
                stageIndex++
                resetStageState()
                val next = payload.stages.getOrNull(stageIndex)
                val base = if (next == null) {
                    arrivedFallback()
                } else {
                    when (next.type) {
                        "walk" -> handleWalk(next, fix)
                        "wait" -> handleWait(next, fix)
                        else -> stageStartDecision(next)
                    }
                }
                // Aynı fix'te hem uyarı hem geçiş olduysa uyarıyı kaybetme
                return base.copy(
                    alert = alert ?: base.alert,
                    approachWarning = approach ?: base.approachWarning
                )
            }
        }

        return JourneyDecision(
            state = JourneyTracker.RuntimeState(
                stageIndex = stageIndex,
                stageType = "ride",
                title = stage.title.ifEmpty { "$route ile git" },
                detail = detail,
                stopsRemaining = stopsRemaining,
                distanceToNextMeters = distToAlight,
                progress = progress,
                passedStops = passedList,
                currentStopName = nextStopName,
                upcomingStops = upcomingList,
                estimatedMinutesRemaining = estMinutes
            ),
            notification = "$route • $detail",
            alert = alert,
            approachWarning = approach
        )
    }

    private fun shouldAutoAlight(
        stopCount: Int,
        stopsRemaining: Int,
        progressRatio: Double,
        distToAlight: Double
    ): Boolean {
        if (stopCount >= 2) {
            // Sıradaki durak iniş durağıysa ve yeterince yaklaşıldıysa
            if (stopsRemaining == 0 && distToAlight <= AUTO_DROP_LAST_STOP_M) return true
            // Rota ilerlemesinin yarısından sonrası için yakınlık yeterli
            if (progressRatio >= 0.5 && distToAlight <= 120.0) return true
        } else {
            if (distToAlight <= AUTO_DROP_DISTANCE_ONLY_M) return true
        }
        return distToAlight <= AUTO_DROP_NEAR_M
    }

    // ---------------------------------------------------------------- Ortak

    private fun stageStartDecision(stage: JourneyStage): JourneyDecision {
        val route = stage.routeCode ?: "Otobüs"
        return when (stage.type) {
            "walk" -> JourneyDecision(
                state = JourneyTracker.RuntimeState(
                    stageIndex = stageIndex,
                    stageType = "walk",
                    title = stage.title.ifEmpty { "${stage.targetName} yürü" },
                    detail = "Hedef: ${stage.targetName}",
                    walkMinutes = TransitRepository.walkMinutes(0.0, settings.walkingSpeedProfile)
                ),
                notification = stage.title.ifEmpty { "${stage.targetName} yürü" }
            )
            "wait" -> JourneyDecision(
                state = JourneyTracker.RuntimeState(
                    stageIndex = stageIndex,
                    stageType = "wait",
                    title = stage.title.ifEmpty { "$route hattını bekle" },
                    detail = "Durak: ${stage.targetName} • $route hattı bekleniyor"
                ),
                notification = "$route hattı bekleniyor • Durak: ${stage.targetName}"
            )
            else -> JourneyDecision(
                state = JourneyTracker.RuntimeState(
                    stageIndex = stageIndex,
                    stageType = "ride",
                    title = stage.title.ifEmpty { "$route ile git" },
                    detail = "İniş: ${stage.targetName}",
                    stopsRemaining = if (stage.stops.size >= 2) stage.stops.size - 1 else null
                ),
                notification = "${stage.title.ifEmpty { "$route ile git" }} • İniş: ${stage.targetName}"
            )
        }
    }

    private fun arrivedFallback(): JourneyDecision {
        val dest = payload.destinationName
        return JourneyDecision(
            state = JourneyTracker.RuntimeState(
                stageIndex = stageIndex,
                stageType = "arrived",
                title = "Hedefe ulaştın",
                detail = dest,
                progress = 1f
            ),
            notification = "İndin. Hedefe ulaştın • $dest",
            finished = true
        )
    }

    private fun resetStageState() {
        walkStartDistance = null
        fastFarStreak = 0
        slowFarStreak = 0
        corridorStreak = 0
        progressMeters = 0.0
        lastStopIdx = -1
        approachWarned = false
        alightAlerted = false
        etaImminentWarned = false
    }

    /** Bekleme aşamasından sonra gelen araç aşamasının durak listesi (koridor kontrolü için). */
    private fun nextRideStops(): List<GeoPoint> {
        val rideStage = payload.stages.drop(stageIndex + 1).firstOrNull { it.type == "ride" } ?: return emptyList()
        return rideStage.stops.map { GeoPoint(it.lat, it.lng) }
    }

    /** Kalan yol üzerinden dakika tahmini (saat dilimine göre dinamik hız). */
    private fun estimateBusMinutes(remainingMeters: Double): Int {
        if (remainingMeters <= 0.0) return 1
        val speed = TransitRepository.busSpeedKmh(hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
        return max(1, (remainingMeters / 1000.0 / speed * 60.0).roundToInt())
    }
}
