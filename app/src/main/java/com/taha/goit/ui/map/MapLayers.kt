package com.taha.goit.ui.map

import android.graphics.Bitmap
import com.taha.goit.data.model.BusRealtimeLocation
import com.taha.goit.data.model.RouteCoordinate
import com.taha.goit.data.model.RouteStop
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Harita katmanlarını (güzergah çizgisi, durak pinleri, canlı otobüs ikonları) yönetir.
 * Canlı araçlar SymbolLayer + iconRotate ile araç yönüne göre döndürülür.
 * Ring hatlar "R", normal hatlar "G"/"D" yönlerini kullanır.
 */
class MapLayers(private val map: MapLibreMap) {

    companion object {
        const val SRC_ROUTE = "goit-route-src"
        const val LAYER_ROUTE_LINE = "goit-route-line"
        const val SRC_STOPS = "goit-stops-src"
        const val LAYER_STOPS = "goit-stops-layer"
        const val LAYER_STOP_LABELS = "goit-stops-label-layer"
        const val SRC_BUSES = "goit-buses-src"
        const val LAYER_BUSES = "goit-buses-layer"
        const val SRC_USER = "goit-user-src"
        const val LAYER_USER = "goit-user-layer"
        const val SRC_PLAN = "goit-plan-src"
        const val LAYER_PLAN = "goit-plan-layer"

        // v1.2.0: Moovit tarzı çok bacaklı plan katmanları
        const val SRC_PLAN_LEGS = "goit-plan-legs-src"
        const val LAYER_PLAN_WALK = "goit-plan-walk-layer"
        const val LAYER_PLAN_BUS1 = "goit-plan-bus1-layer"
        const val LAYER_PLAN_BUS2 = "goit-plan-bus2-layer"
        const val LAYER_PLAN_TRANSFER = "goit-plan-transfer-layer"

        const val BUS_ICON_ID = "goit-bus-icon"
        const val STOP_ICON_ID = "goit-stop-icon"
        const val SUBTLE_STOP_ICON_ID = "goit-subtle-stop-icon"
        const val HERE_ICON_ID = "goit-here-icon"
        const val PIN_A_ICON_ID = "goit-pin-a"
        const val PIN_B_ICON_ID = "goit-pin-b"
        const val TRANSFER_ICON_ID = "goit-transfer-pin"
    }

    private var style: Style? = null

    // Yön hesabı için son çizilen güzergah noktaları (lat,lng çiftleri)
    private var lastRoutePoints: List<Pair<Double, Double>> = emptyList()
    private var lastRouteDirection: String? = null

    fun setup(
        style: Style,
        busIcon: Bitmap,
        stopIcon: Bitmap,
        userIcon: Bitmap,
        hereIcon: Bitmap? = null,
        pinAIcon: Bitmap? = null,
        pinBIcon: Bitmap? = null,
        transferIcon: Bitmap? = null,
        subtleStopIcon: Bitmap? = null
    ) {
        this.style = style
        runCatching {
            style.addImage(BUS_ICON_ID, busIcon)
            style.addImage(STOP_ICON_ID, stopIcon)
            if (subtleStopIcon != null) style.addImage(SUBTLE_STOP_ICON_ID, subtleStopIcon)
            style.addImage("goit-user-icon", userIcon)
            if (hereIcon != null) style.addImage(HERE_ICON_ID, hereIcon)
            if (pinAIcon != null) style.addImage(PIN_A_ICON_ID, pinAIcon)
            if (pinBIcon != null) style.addImage(PIN_B_ICON_ID, pinBIcon)
            if (transferIcon != null) style.addImage(TRANSFER_ICON_ID, transferIcon)

            // Güzergah çizgisi kaynağı (normal hat & dual-tone için renk filtreli)
            style.addSource(GeoJsonSource(SRC_ROUTE))
            style.addLayer(
                LineLayer(LAYER_ROUTE_LINE, SRC_ROUTE).withProperties(
                    PropertyFactory.lineColor(
                        Expression.match(
                            Expression.get("segment"),
                            Expression.literal("#1A73E8"),
                            Expression.stop("pastel", Expression.literal("#90CAF9")),
                            Expression.stop("passed", Expression.literal("#1A73E8")),
                            Expression.stop("remaining", Expression.literal("#B0BEC5"))
                        )
                    ),
                    PropertyFactory.lineWidth(
                        Expression.match(
                            Expression.get("segment"),
                            Expression.literal(6f),
                            Expression.stop("pastel", Expression.literal(7f)),
                            Expression.stop("passed", Expression.literal(6.5f)),
                            Expression.stop("remaining", Expression.literal(5f))
                        )
                    ),
                    PropertyFactory.lineOpacity(
                        Expression.match(
                            Expression.get("segment"),
                            Expression.literal(0.85f),
                            Expression.stop("pastel", Expression.literal(0.45f)),
                            Expression.stop("passed", Expression.literal(0.95f)),
                            Expression.stop("remaining", Expression.literal(0.60f))
                        )
                    ),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )

            // v1.2.0: Çok bacaklı plan çizgileri (Moovit tarzı)
            style.addSource(GeoJsonSource(SRC_PLAN_LEGS))
            // 1) Yürüme çizgisi (kesikli gri)
            style.addLayer(
                LineLayer(LAYER_PLAN_WALK, SRC_PLAN_LEGS)
                    .withFilter(Expression.eq(Expression.get("kind"), "walk"))
                    .withProperties(
                        PropertyFactory.lineColor("#757575"),
                        PropertyFactory.lineWidth(4.5f),
                        PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
            )
            // 2) 1. otobüs bacağı (mavi)
            style.addLayer(
                LineLayer(LAYER_PLAN_BUS1, SRC_PLAN_LEGS)
                    .withFilter(Expression.eq(Expression.get("kind"), "bus1"))
                    .withProperties(
                        PropertyFactory.lineColor("#1A73E8"),
                        PropertyFactory.lineWidth(6.5f),
                        PropertyFactory.lineOpacity(0.9f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
            )
            // 3) 2. otobüs bacağı (turuncu/kırmızı - aktarma)
            style.addLayer(
                LineLayer(LAYER_PLAN_BUS2, SRC_PLAN_LEGS)
                    .withFilter(Expression.eq(Expression.get("kind"), "bus2"))
                    .withProperties(
                        PropertyFactory.lineColor("#F29900"),
                        PropertyFactory.lineWidth(6.5f),
                        PropertyFactory.lineOpacity(0.9f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
            )
            // 4) Aktarma noktası çemberi / ikonu
            style.addLayer(
                org.maplibre.android.style.layers.CircleLayer(LAYER_PLAN_TRANSFER, SRC_PLAN_LEGS)
                    .withFilter(Expression.eq(Expression.get("kind"), "transfer_point"))
                    .withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor("#F29900"),
                        PropertyFactory.circleStrokeWidth(2.5f),
                        PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt())
                    )
            )

            // Durak kaynağı ve katmanı (pin + isim etiketi)
            style.addSource(GeoJsonSource(SRC_STOPS))
            style.addLayer(
                SymbolLayer(LAYER_STOPS, SRC_STOPS).withProperties(
                    PropertyFactory.iconImage(Expression.literal(STOP_ICON_ID)),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(0.5f)
                )
            )
            style.addLayer(
                SymbolLayer(LAYER_STOP_LABELS, SRC_STOPS).withProperties(
                    PropertyFactory.textField(Expression.get("stopLabel")),
                    PropertyFactory.textSize(10f),
                    PropertyFactory.textOffset(arrayOf(0f, -1.6f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.textColor("#174EA6"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.4f),
                    PropertyFactory.symbolSortKey(-100f)
                )
            )

            // Canlı otobüs kaynağı ve katmanı (yön açısıyla döner)
            style.addSource(GeoJsonSource(SRC_BUSES))
            style.addLayer(
                SymbolLayer(LAYER_BUSES, SRC_BUSES).withProperties(
                    PropertyFactory.iconImage(Expression.get("busIcon")),
                    PropertyFactory.iconRotate(Expression.get("bearing")),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(0.55f)
                )
            )

            // Kullanıcı konumu katmanı
            style.addSource(GeoJsonSource(SRC_USER))
            style.addLayer(
                SymbolLayer(LAYER_USER, SRC_USER).withProperties(
                    PropertyFactory.iconImage(Expression.literal("goit-user-icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(0.6f)
                )
            )

            // Yol tarifi uç noktaları (A/B pinleri)
            style.addSource(GeoJsonSource(SRC_PLAN))
            style.addLayer(
                SymbolLayer(LAYER_PLAN, SRC_PLAN).withProperties(
                    PropertyFactory.iconImage(Expression.get("pinIcon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(0.7f),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
                )
            )
        }
    }

    /**
     * Seçilen tek bir planın bacaklarını çizer (Moovit tarzı):
     *  - Yürüme bacakları: kesikli çizgi
     *  - 1. otobüs bacağı: mavi çizgi
     *  - 2. otobüs bacağı: turuncu çizgi
     *  - Aktarma noktası: turuncu çember
     */
    /**
     * Seçilen tek bir planın bacaklarını çizer (Moovit tarzı):
     *  - Yürüme bacakları: FOSSGIS foot rotası ile yol üzerinden kesikli çizgi
     *  - 1. otobüs bacağı: kırpılmış gerçek hat şekli (routecoordinate) ile mavi çizgi
     *  - 2. otobüs bacağı: aktarma hattı gerçek şekli ile turuncu çizgi
     *  - Aktarma noktası: turuncu çember
     */
    fun drawPlanLegs(
        originLat: Double?,
        originLng: Double?,
        destLat: Double?,
        destLng: Double?,
        plan: com.taha.goit.data.model.RoutePlan,
        busLegShapes: Map<Int, List<org.maplibre.android.geometry.LatLng>> = emptyMap(),
        walkLegShapes: Map<Int, List<org.maplibre.android.geometry.LatLng>> = emptyMap()
    ) {
        val features = mutableListOf<Feature>()

        // 1. Sadece yürüme planı ise
        if (plan.isWalkOnly) {
            val walkShape = walkLegShapes[0]
            if (walkShape != null && walkShape.size >= 2) {
                val pts = walkShape.map { Point.fromLngLat(it.longitude, it.latitude) }
                features.add(Feature.fromGeometry(LineString.fromLngLats(pts)).apply { addStringProperty("kind", "walk") })
            } else if (originLat != null && originLng != null && destLat != null && destLng != null) {
                val line = LineString.fromLngLats(
                    listOf(Point.fromLngLat(originLng, originLat), Point.fromLngLat(destLng, destLat))
                )
                features.add(Feature.fromGeometry(line).apply { addStringProperty("kind", "walk") })
            }
            style?.getSourceAs<GeoJsonSource>(SRC_PLAN_LEGS)?.setGeoJson(FeatureCollection.fromFeatures(features))
            return
        }

        var busIndex = 0
        var prevPoint: Pair<Double, Double>? = if (originLat != null && originLng != null) originLat to originLng else null

        for ((legIdx, leg) in plan.legs.withIndex()) {
            when (leg.kind) {
                "walk" -> {
                    val walkPoints = walkLegShapes[legIdx]
                    if (walkPoints != null && walkPoints.size >= 2) {
                        val pts = walkPoints.map { Point.fromLngLat(it.longitude, it.latitude) }
                        features.add(Feature.fromGeometry(LineString.fromLngLats(pts)).apply { addStringProperty("kind", "walk") })
                        val last = walkPoints.last()
                        prevPoint = last.latitude to last.longitude
                    } else {
                        val p1 = prevPoint
                        val p2 = when {
                            leg.boardStop != null -> {
                                val la = leg.boardStop.latitude?.toDoubleOrNull()
                                val lo = leg.boardStop.longitude?.toDoubleOrNull()
                                if (la != null && lo != null) la to lo else null
                            }
                            leg.alightStop != null -> {
                                val la = leg.alightStop.latitude?.toDoubleOrNull()
                                val lo = leg.alightStop.longitude?.toDoubleOrNull()
                                if (la != null && lo != null) la to lo else null
                            }
                            else -> if (destLat != null && destLng != null) destLat to destLng else null
                        }
                        if (p1 != null && p2 != null) {
                            val line = LineString.fromLngLats(
                                listOf(Point.fromLngLat(p1.second, p1.first), Point.fromLngLat(p2.second, p2.first))
                            )
                            features.add(Feature.fromGeometry(line).apply { addStringProperty("kind", "walk") })
                            prevPoint = p2
                        }
                    }
                }
                "bus" -> {
                    busIndex++
                    val busKind = if (busIndex == 1) "bus1" else "bus2"
                    val busShape = busLegShapes[legIdx]

                    if (busShape != null && busShape.size >= 2) {
                        val pts = busShape.map { Point.fromLngLat(it.longitude, it.latitude) }
                        features.add(
                            Feature.fromGeometry(LineString.fromLngLats(pts)).apply {
                                addStringProperty("kind", busKind)
                            }
                        )
                        val last = busShape.last()
                        prevPoint = last.latitude to last.longitude
                    } else {
                        val pts = leg.stops.mapNotNull { s ->
                            val la = s.latitude?.toDoubleOrNull() ?: return@mapNotNull null
                            val lo = s.longitude?.toDoubleOrNull() ?: return@mapNotNull null
                            Point.fromLngLat(lo, la)
                        }
                        if (pts.size >= 2) {
                            features.add(
                                Feature.fromGeometry(LineString.fromLngLats(pts)).apply {
                                    addStringProperty("kind", busKind)
                                }
                            )
                            val last = leg.stops.lastOrNull()
                            val la = last?.latitude?.toDoubleOrNull()
                            val lo = last?.longitude?.toDoubleOrNull()
                            if (la != null && lo != null) prevPoint = la to lo
                        }
                    }
                }
            }
        }

        // Aktarma noktası işareti
        if (plan.isTransfer && plan.transferStop != null) {
            val tla = plan.transferStop.latitude?.toDoubleOrNull()
            val tlo = plan.transferStop.longitude?.toDoubleOrNull()
            if (tla != null && tlo != null) {
                features.add(
                    Feature.fromGeometry(Point.fromLngLat(tlo, tla)).apply {
                        addStringProperty("kind", "transfer_point")
                    }
                )
            }
        }

        style?.getSourceAs<GeoJsonSource>(SRC_PLAN_LEGS)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Plan bacakları katmanını temizler. */
    fun clearPlanLegs() {
        runCatching {
            style?.getSourceAs<GeoJsonSource>(SRC_PLAN_LEGS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        }
    }

    /** Yol tarifi A (yeşil) ve B (kırmızı) uç noktalarını çizer. */
    fun drawPlanEndpoints(originLat: Double?, originLng: Double?, destLat: Double?, destLng: Double?) {
        val features = mutableListOf<Feature>()
        if (originLat != null && originLng != null) {
            features.add(
                Feature.fromGeometry(Point.fromLngLat(originLng, originLat)).apply {
                    addStringProperty("pinIcon", PIN_A_ICON_ID)
                }
            )
        }
        if (destLat != null && destLng != null) {
            features.add(
                Feature.fromGeometry(Point.fromLngLat(destLng, destLat)).apply {
                    addStringProperty("pinIcon", PIN_B_ICON_ID)
                }
            )
        }
        style?.getSourceAs<GeoJsonSource>(SRC_PLAN)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Güzergah koordinatlarını tek renk çizer. */
    fun drawRoute(coords: List<RouteCoordinate>, direction: String) {
        val distinctDirs = coords.mapNotNull { it.routeDirection }.distinct()
        val useDir = when {
            distinctDirs.contains(direction) -> direction
            distinctDirs.size == 1 -> distinctDirs.first()
            else -> direction
        }
        lastRouteDirection = useDir
        val points = coords
            .filter { it.routeDirection == useDir }
            .sortedBy { it.sequence }
            .mapNotNull { c ->
                val lat = c.latitude?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = c.logitude?.toDoubleOrNull() ?: return@mapNotNull null
                Point.fromLngLat(lng, lat)
            }
        lastRoutePoints = points.map { it.latitude() to it.longitude() }
        val src = style?.getSourceAs<GeoJsonSource>(SRC_ROUTE) ?: return
        if (points.isEmpty()) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }
        val feature = Feature.fromGeometry(LineString.fromLngLats(points)).apply {
            addStringProperty("segment", "full")
        }
        src.setGeoJson(FeatureCollection.fromFeatures(listOf(feature)))
    }

    /**
     * Madde 7: Çift tonlu güzergah görselleştirmesi:
     *  - Tam hat: pastel mavi arka plan
     *  - Geçilen bölüm: belirgin canlı mavi
     *  - Kalan bölüm: silik gri
     */
    fun drawRouteDualTone(
        coords: List<RouteCoordinate>,
        direction: String,
        currentStopSequence: Int? = null,
        totalStops: Int? = null
    ) {
        val distinctDirs = coords.mapNotNull { it.routeDirection }.distinct()
        val useDir = when {
            distinctDirs.contains(direction) -> direction
            distinctDirs.size == 1 -> distinctDirs.first()
            else -> direction
        }
        lastRouteDirection = useDir
        val points = coords
            .filter { it.routeDirection == useDir }
            .sortedBy { it.sequence }
            .mapNotNull { c ->
                val lat = c.latitude?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = c.logitude?.toDoubleOrNull() ?: return@mapNotNull null
                Point.fromLngLat(lng, lat)
            }
        lastRoutePoints = points.map { it.latitude() to it.longitude() }
        val src = style?.getSourceAs<GeoJsonSource>(SRC_ROUTE) ?: return
        if (points.isEmpty()) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }

        val features = mutableListOf<Feature>()
        // 1. Arka plan pastel hat çizgisi
        features.add(
            Feature.fromGeometry(LineString.fromLngLats(points)).apply {
                addStringProperty("segment", "pastel")
            }
        )

        // 2. Canlı ilerleme varsa geçilen ve kalan segmentleri çiz
        if (currentStopSequence != null && totalStops != null && totalStops > 1 && points.size >= 2) {
            val progressFraction = (currentStopSequence.toDouble() / totalStops.toDouble()).coerceIn(0.0, 1.0)
            val splitIdx = ((points.size - 1) * progressFraction).toInt().coerceIn(0, points.size - 1)

            val passedPoints = points.subList(0, (splitIdx + 1).coerceAtMost(points.size))
            if (passedPoints.size >= 2) {
                features.add(Feature.fromGeometry(LineString.fromLngLats(passedPoints)).apply {
                    addStringProperty("segment", "passed")
                })
            }

            val remainingPoints = points.subList(splitIdx, points.size)
            if (remainingPoints.size >= 2) {
                features.add(Feature.fromGeometry(LineString.fromLngLats(remainingPoints)).apply {
                    addStringProperty("segment", "remaining")
                })
            }
        }

        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Durakları pin + isim etiketi olarak çizer (Madde 7: subtle seçeneği ile küçük noktalar). */
    fun drawStops(stops: List<RouteStop>, direction: String, subtle: Boolean = false) {
        val distinctDirs = stops.mapNotNull { it.direction }.distinct()
        val iconId = if (subtle) SUBTLE_STOP_ICON_ID else STOP_ICON_ID
        val features = stops
            .filter {
                it.direction == direction ||
                it.direction == null ||
                (distinctDirs.size == 1 && distinctDirs.first() != "G" && distinctDirs.first() != "D")
            }
            .mapNotNull { s ->
                val lat = s.latitude?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = s.longitude?.toDoubleOrNull() ?: return@mapNotNull null
                Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply {
                    addStringProperty("stopName", s.stopName ?: "")
                    addStringProperty("stopLabel", if (subtle) "" else (s.stopName ?: ""))
                    addNumberProperty("stopId", s.stopId ?: 0)
                }
            }
        val src = style?.getSourceAs<GeoJsonSource>(SRC_STOPS) ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /**
     * Madde 2 A: Canlı otobüsleri güzergah polylinesına snap ederek ve yön açısıyla çizer.
     */
    fun drawBuses(buses: List<BusRealtimeLocation>) {
        val features = buses.mapNotNull { bus ->
            val lat = bus.enlem ?: return@mapNotNull null
            val lng = bus.boylam ?: return@mapNotNull null
            val (snapLat, snapLng) = snapToPolyline(lat, lng, bus.istikamet)
            val bearing = when {
                (bus.yon ?: 0) != 0 -> (bus.yon ?: 0).toDouble()
                else -> computeBearingFromRoute(snapLat, snapLng)
            }
            Feature.fromGeometry(Point.fromLngLat(snapLng, snapLat)).apply {
                addStringProperty("busIcon", BUS_ICON_ID)
                addNumberProperty("bearing", bearing)
                addStringProperty("plate", bus.plaka ?: "")
                addNumberProperty("speed", (bus.hiz ?: 0).toDouble())
                addNumberProperty("passengers", (bus.seferYolcu ?: 0).toDouble())
                addStringProperty("heading", bus.istikamet ?: "")
            }
        }
        val src = style?.getSourceAs<GeoJsonSource>(SRC_BUSES) ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /**
     * Otobüsün yüklü polyline üzerindeki en yakın segmente snap edilmesi (Madde 2 A).
     * Yalnızca aynı istikamet ("G"/"D") ve <= 120m mesafedeyse projekte edilir.
     */
    fun snapToPolyline(lat: Double, lng: Double, busDirection: String? = null): Pair<Double, Double> {
        val pts = lastRoutePoints
        if (pts.size < 2) return lat to lng
        if (busDirection != null && lastRouteDirection != null && busDirection != lastRouteDirection) {
            return lat to lng
        }

        var bestDistSq = Double.MAX_VALUE
        var bestProjLat = lat
        var bestProjLng = lng

        for (i in 0 until pts.size - 1) {
            val (la1, lo1) = pts[i]
            val (la2, lo2) = pts[i + 1]
            val (projLat, projLng, distSq) = projectPointToSegment(lat, lng, la1, lo1, la2, lo2)
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestProjLat = projLat
                bestProjLng = projLng
            }
        }

        // ~120 metre ≈ 0.0011 derece (karesi ≈ 1.2e-6)
        val maxDistDegreesSq = 0.0011 * 0.0011
        return if (bestDistSq <= maxDistDegreesSq) {
            bestProjLat to bestProjLng
        } else {
            lat to lng
        }
    }

    private fun projectPointToSegment(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): Triple<Double, Double, Double> {
        val dx = bLng - aLng
        val dy = bLat - aLat
        if (dx == 0.0 && dy == 0.0) {
            val dSq = (pLat - aLat) * (pLat - aLat) + (pLng - aLng) * (pLng - aLng)
            return Triple(aLat, aLng, dSq)
        }
        val t = (((pLng - aLng) * dx + (pLat - aLat) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val projLat = aLat + t * dy
        val projLng = aLng + t * dx
        val distSq = (pLat - projLat) * (pLat - projLat) + (pLng - projLng) * (pLng - projLng)
        return Triple(projLat, projLng, distSq)
    }

    /** Kullanıcı konumunu işaretler. */
    fun drawUserLocation(lat: Double, lng: Double) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_USER) ?: return
        val feature = Feature.fromGeometry(Point.fromLngLat(lng, lat))
        src.setGeoJson(FeatureCollection.fromFeatures(listOf(feature)))
    }

    /** Otobüsün güzergah üzerindeki en yakın segmentinden yön açısını hesaplar (0-360°). */
    private fun computeBearingFromRoute(lat: Double, lng: Double): Double {
        val pts = lastRoutePoints
        if (pts.size < 2) return 0.0
        var bestDist = Double.MAX_VALUE
        var bestBearing = 0.0
        for (i in 0 until pts.size - 1) {
            val (la1, lo1) = pts[i]
            val (la2, lo2) = pts[i + 1]
            val segDist = pointToSegmentDistSq(lat, lng, la1, lo1, la2, lo2)
            if (segDist < bestDist) {
                bestDist = segDist
                bestBearing = bearingBetween(la1, lo1, la2, lo2)
            }
        }
        return bestBearing
    }

    private fun pointToSegmentDistSq(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): Double {
        val dx = bLng - aLng
        val dy = bLat - aLat
        if (dx == 0.0 && dy == 0.0) {
            return (pLat - aLat) * (pLat - aLat) + (pLng - aLng) * (pLng - aLng)
        }
        val t = (((pLng - aLng) * dx + (pLat - aLat) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val projLat = aLat + t * dy
        val projLng = aLng + t * dx
        return (pLat - projLat) * (pLat - projLat) + (pLng - projLng) * (pLng - projLng)
    }

    /** (lat1,lng1)'den (lat2,lng2)'ye pusula yön açısı. */
    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lng2 - lng1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val theta = atan2(y, x)
        return (Math.toDegrees(theta) + 360.0) % 360.0
    }

    fun clear() {
        runCatching {
            lastRoutePoints = emptyList()
            style?.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            style?.getSourceAs<GeoJsonSource>(SRC_STOPS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            style?.getSourceAs<GeoJsonSource>(SRC_BUSES)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            style?.getSourceAs<GeoJsonSource>(SRC_PLAN)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            style?.getSourceAs<GeoJsonSource>(SRC_PLAN_LEGS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        }
    }

    /** Yalnızca durak/hat katmanlarını temizler; canlı araçlar kalır. */
    fun clearRouteOnly() {
        runCatching {
            lastRoutePoints = emptyList()
            style?.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            style?.getSourceAs<GeoJsonSource>(SRC_STOPS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        }
    }

    /** Yalnızca plan uç noktalarını temizler. */
    fun clearPlanEndpoints() {
        runCatching {
            style?.getSourceAs<GeoJsonSource>(SRC_PLAN)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        }
    }
}
