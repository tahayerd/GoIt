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
    val maxHiz: Int? = null
)

@Serializable
data class RouteCoordinate(
    val latitude: String? = null,
    val logitude: String? = null,
    val sequence: Int? = null,
    val route: String? = null,
    val routeDirection: String? = null
)

@Serializable
data class RouteStop(
    val stopId: Int? = null,
    val stopName: String? = null,
    val sequence: Int? = null,
    val latitude: String? = null,
    val longitude: String? = null,
    val direction: String? = null
)

@Serializable
data class StopRemainingTime(
    val lineCode: String? = null,
    val destination: String? = null,
    val remainingMinutes: Int? = null,
    val distanceMeters: Int? = null
)
