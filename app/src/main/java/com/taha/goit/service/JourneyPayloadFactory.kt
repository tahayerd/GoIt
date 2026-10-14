package com.taha.goit.service

import com.taha.goit.data.model.PlanLeg
import com.taha.goit.data.model.RoutePlan
import com.taha.goit.data.model.RouteStop

/**
 * RoutePlan → JourneyPayload dönüşümü.
 *
 * Android API'si kullanmaz; bu yüzden JVM birim testleriyle doğrulanır. Servis ve
 * ViewModel aynı dönüşümü kullanır, böylece "boş payload → sessiz çalışmayan yolculuk"
 * hatası yapısal olarak engellenir: üretilen payload her zaman en az bir aşama içerir.
 */
object JourneyPayloadFactory {

    /** Bursa bölgesi için geçersiz sayılan (0,0) ve sonsuz/NaN koordinatları eler. */
    private fun Double.isUsableCoordinate(): Boolean = isFinite() && this != 0.0

    private fun RouteStop?.lat(): Double? = this?.latitude?.toDoubleOrNull()?.takeIf { it.isUsableCoordinate() }

    private fun RouteStop?.lng(): Double? = this?.longitude?.toDoubleOrNull()?.takeIf { it.isUsableCoordinate() }

    private fun RouteStop?.nameOr(fallback: String): String =
        this?.stopName?.takeIf { it.isNotBlank() } ?: fallback

    fun build(
        plan: RoutePlan,
        destName: String,
        destLat: Double,
        destLng: Double,
        walkShapes: Map<Int, List<GeoPoint>> = emptyMap()
    ): JourneyPayload {
        val name = destName.ifBlank { "Hedef" }
        val safeLat = if (destLat.isUsableCoordinate()) destLat else 0.0
        val safeLng = if (destLng.isUsableCoordinate()) destLng else 0.0

        var stages = mutableListOf<JourneyStage>()

        if (plan.isWalkOnly) {
            stages += walkStage("Hedefe yürü", safeLat, safeLng, name, walkShapes[0].orEmpty())
            return JourneyPayload(name, safeLat, safeLng, stages)
        }

        plan.legs.forEachIndexed { idx, leg ->
            when (leg.kind) {
                "walk" -> {
                    val nextBus = plan.legs.drop(idx + 1).firstOrNull { it.kind == "bus" }
                    val target = nextBus?.boardStop
                    val lat = target.lat()
                    val lng = target.lng()
                    val useDest = lat == null || lng == null
                    val tLat = lat ?: safeLat
                    val tLng = lng ?: safeLng
                    val label = if (useDest) name else target.nameOr(name)
                    val title = if (useDest) "Hedefe yürü" else "$label yürü"
                    stages += walkStage(title, tLat, tLng, label, walkShapes[idx].orEmpty())
                }
                "bus" -> {
                    stages += busStages(leg)
                }
            }
        }

        // Bacak verisi eksik/bozuk ise durak listesinden tek bacaklı plan türet.
        if (stages.isEmpty()) {
            stages = fallbackStages(plan, safeLat, safeLng, name)
        }

        // Yinelenen ardışık durakları temizle: aynı durakta iki kez "bekle" olmaz.
        stages = stages.deduplicateConsecutive()

        return JourneyPayload(name, safeLat, safeLng, stages)
    }

    private fun busStages(leg: PlanLeg): List<JourneyStage> {
        val out = mutableListOf<JourneyStage>()
        val board = leg.boardStop
        val boardLat = board.lat()
        val boardLng = board.lng()
        val route = leg.routeCode ?: "Otobüs"

        if (boardLat != null && boardLng != null) {
            out += JourneyStage(
                type = "wait",
                routeCode = leg.routeCode,
                stopId = board?.stopId,
                title = "$route hattını bekle",
                targetLat = boardLat,
                targetLng = boardLng,
                targetName = board.nameOr("")
            )
        }

        val alight = leg.alightStop
        val alightLat = alight.lat()
        val alightLng = alight.lng()
        if (alightLat != null && alightLng != null) {
            val stops = leg.stops.mapNotNull { s ->
                val la = s.lat() ?: return@mapNotNull null
                val lo = s.lng() ?: return@mapNotNull null
                JourneyStop(s.stopName ?: "", la, lo)
            }
            out += JourneyStage(
                type = "ride",
                routeCode = leg.routeCode,
                title = "$route ile git → ${alight.nameOr("")}",
                targetLat = alightLat,
                targetLng = alightLng,
                targetName = alight.nameOr(""),
                stops = stops
            )
        }
        return out
    }

    /**
     * Bacak yoksa (eski/eksik plan verisi) durak listesinden güvenli bir aşama akışı üretir.
     * Her zaman önce yürüyüş, sonra bekleme, sonra araç aşaması eklenir; böylece yolculuk
     * hiçbir durumda "boş aşama listesi" ile başlamaz.
     */
    private fun fallbackStages(
        plan: RoutePlan,
        destLat: Double,
        destLng: Double,
        destName: String
    ): MutableList<JourneyStage> {
        val out = mutableListOf<JourneyStage>()
        val board = plan.originStop
        val alight = plan.destinationStop
        val boardLat = board.lat()
        val boardLng = board.lng()
        val alightLat = alight.lat()
        val alightLng = alight.lng()
        val route = plan.routeCode.ifBlank { "Otobüs" }

        if (boardLat != null && boardLng != null && alightLat != null && alightLng != null) {
            out += walkStage(
                "${board.nameOr(destName)} yürü",
                boardLat, boardLng, board.nameOr("Durak"), emptyList()
            )
            out += JourneyStage(
                type = "wait",
                routeCode = plan.routeCode.takeIf { it.isNotBlank() },
                stopId = board.stopId,
                title = "$route hattını bekle",
                targetLat = boardLat,
                targetLng = boardLng,
                targetName = board.nameOr("")
            )
            val stops = plan.intermediateStops.mapNotNull { s ->
                val la = s.lat() ?: return@mapNotNull null
                val lo = s.lng() ?: return@mapNotNull null
                JourneyStop(s.stopName ?: "", la, lo)
            }
            out += JourneyStage(
                type = "ride",
                routeCode = plan.routeCode.takeIf { it.isNotBlank() },
                title = "$route ile git → ${alight.nameOr(destName)}",
                targetLat = alightLat,
                targetLng = alightLng,
                targetName = alight.nameOr(destName),
                stops = stops
            )
        } else {
            out += walkStage("Hedefe yürü", destLat, destLng, destName, emptyList())
        }
        return out
    }

    private fun walkStage(
        title: String,
        lat: Double,
        lng: Double,
        targetName: String,
        path: List<GeoPoint>
    ): JourneyStage = JourneyStage(
        type = "walk",
        title = title,
        targetLat = lat,
        targetLng = lng,
        targetName = targetName,
        path = path.map { JourneyStop("", it.lat, it.lng) }
    )

    /** Aynı durağa işaret eden ardışık bekleme/biniş aşamalarını eler. */
    private fun List<JourneyStage>.deduplicateConsecutive(): MutableList<JourneyStage> {
        val out = mutableListOf<JourneyStage>()
        for (stage in this) {
            val last = out.lastOrNull()
            val duplicate = last != null &&
                    last.type == stage.type &&
                    last.targetLat == stage.targetLat &&
                    last.targetLng == stage.targetLng &&
                    last.routeCode == stage.routeCode
            if (!duplicate) out += stage
        }
        return out
    }
}
