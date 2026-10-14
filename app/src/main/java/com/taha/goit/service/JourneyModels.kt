package com.taha.goit.service

import kotlinx.serialization.Serializable

@Serializable
data class JourneyStop(
    val name: String,
    val lat: Double,
    val lng: Double
)

@Serializable
data class JourneyStage(
    val type: String,             // "walk" | "wait" | "ride"
    val routeCode: String? = null,
    val stopId: Int? = null,      // wait aşamalarında: biniş durağı ID'si (canlı ETA için)
    val title: String = "",
    val targetLat: Double = 0.0,
    val targetLng: Double = 0.0,
    val targetName: String = "",
    val stops: List<JourneyStop> = emptyList(),  // ride aşamalarında (sayım için)
    val path: List<JourneyStop> = emptyList()    // walk aşamalarında: adım adım yön tarifi için yol geometrisi
)

@Serializable
data class JourneyPayload(
    val destinationName: String,
    val destLat: Double,
    val destLng: Double,
    val stages: List<JourneyStage>
)
