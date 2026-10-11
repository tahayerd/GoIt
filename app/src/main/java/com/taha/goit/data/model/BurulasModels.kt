package com.taha.goit.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val version: Int? = null,
    val statusCode: Int? = null,
    val message: String? = null,
    val result: T? = null
)

@Serializable
data class SearchItem(
    val type: String? = null, // "R" (Route) or "S" (Station)
    val kod: String? = null,
    val aciklama: String? = null,
    val hatNo: Int? = null,
    val stationId: Int? = null,
    val stationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

@Serializable
data class BusRealtimeLocation(
    val state: Int? = null,
    val plaka: String? = null,
    val enlem: Double? = null,
    val boylam: Double? = null,
    val renk: String? = null,
    val hiz: Int? = null,
    val mesafe: Int? = null,
    val yon: Int? = null,
    val maxHiz: Int? = null,
    val surucu: String? = null,
    val seferYolcu: Int? = null,
    val istikamet: String? = null, // "G" (Gidiş) or "D" (Dönüş)
    val hatkodu: String? = null
)

@Serializable
data class RouteCoordinate(
    val latitude: String? = null,
    val logitude: String? = null,
    val sequence: Int? = null,
    val route: String? = null,
    val routeDirection: String? = null // "G" or "D"
)

@Serializable
data class RouteStop(
    val stopId: Int? = null,
    val stopName: String? = null,
    val sequence: Int? = null,
    val latitude: String? = null,
    val longitude: String? = null,
    val direction: String? = null // "G" or "D"
)

@Serializable
data class StopRemainingTime(
    val routeId: Int? = null,
    val routeCode: String? = null,
    val routeTitle: String? = null,
    val passTime: String? = null, // e.g. "00:07:00"
    val realTime: String? = null,
    val vehicleLat: Double? = null,
    val vehicleLng: Double? = null,
    val vehicleSpeed: Int? = null,
    val licencePlate: String? = null,
    val direction: String? = null,
    val isAirConditioner: Boolean? = null,
    val isSuitableForDisabled: Boolean? = null
)

@Serializable
data class RouteByStopItem(
    val routeNo: Int? = null,
    val routeCode: String? = null,
    val routeName: String? = null
)

// A-to-B Route Planning Models
data class RoutePlan(
    val originStop: RouteStop,
    val destinationStop: RouteStop,
    val routeCode: String,
    val routeName: String,
    val totalStops: Int,
    val estimatedDurationMinutes: Int,
    val intermediateStops: List<RouteStop>,
    // Aktarmalı planlar için (tek aktarmalı)
    val isTransfer: Boolean = false,
    val firstRouteCode: String = "",
    val secondRouteCode: String = "",
    val transferStop: RouteStop? = null
)

/** "Otobüsüm Nerede" yakındaki duraklar (activestations {enlem, boylam}). */
@Serializable
data class ActiveStationItem(
    val type: String? = null,
    val stationId: Int? = null,
    val stationName: String? = null,
    val longitude: Double? = null,
    val latitude: Double? = null
)

/** Durağa ait hareket saatleri (ScheduleByRoute {stopId, weekDay}). */
@Serializable
data class ScheduleEntry(
    val routeId: Boolean? = null,
    val routeCode: String? = null,
    val stopTime: String? = null,
    val information: String? = null
)

/** Hat ücreti (routeprice). */
@Serializable
data class CardPrice(
    val cardType: String? = null,
    val price: Double? = null
)

/**
 * assets/stations.json girdisi: yerel durak/mekan veritabanı (13.9k kayıt, gerçek koordinatlar).
 * t: 1 = durak, 2 = mekan/POI. Kısa alan adları APK boyutu için tercih edildi.
 */
@Serializable
data class LocalStation(
    val i: Int,      // stationId veya POI id
    val t: Int,      // 1 = durak, 2 = mekan/POI
    val n: String,   // ad
    val la: Double,  // enlem
    val lo: Double   // boylam
)

/** Nominatim (OSM) arama sonucu — izleyicisiz coğrafi kodlama yedeği. */
@Serializable
data class OsmPlace(
    val lat: String? = null,
    val lon: String? = null,
    @kotlinx.serialization.SerialName("display_name") val displayName: String? = null,
    val name: String? = null,
    val type: String? = null,
    val category: String? = null
)

/** Hat kataloğu girdisi (app/src/main/assets/routes.json). */
@Serializable
data class RouteCatalogEntry(
    val kod: String,
    val hatNo: Int,
    val aciklama: String = ""
)
