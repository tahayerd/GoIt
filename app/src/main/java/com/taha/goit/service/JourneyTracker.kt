package com.taha.goit.service

import kotlinx.coroutines.flow.MutableStateFlow

object JourneyTracker {
    data class RuntimeState(
        val stageIndex: Int,
        val stageType: String,        // "walk" | "wait" | "ride" | "arrived"
        val title: String,            // "38 hattını bekle"
        val detail: String,           // "Fatih Sultan Mehmet Blv. durağı"
        val stopsRemaining: Int? = null,
        val distanceToNextMeters: Double? = null,
        val progress: Float = 0f,      // 0..1 (aşama ilerlemesi)
        // V1.5.0 Ek Bilgiler:
        val waitEtaMinutes: String? = null,            // Durakta beklerken otobüsün gelmesine kalan dakika
        val walkMinutes: Int? = null,                  // Yürüyüş mesafesinin kaç dakika süreceği
        val passedStops: List<String> = emptyList(),   // Geçilen durakların listesi
        val currentStopName: String? = null,           // Sıradaki / şu anki durak
        val upcomingStops: List<String> = emptyList(), // Kalan durakların listesi
        val estimatedMinutesRemaining: Int? = null     // Araçta inişe kalan tahmini dakika
    )
    val state = MutableStateFlow<RuntimeState?>(null)
}
