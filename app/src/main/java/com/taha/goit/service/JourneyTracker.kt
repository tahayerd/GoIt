package com.taha.goit.service

import kotlinx.coroutines.flow.MutableStateFlow

object JourneyTracker {
    data class RuntimeState(
        val stageIndex: Int,
        val stageType: String,        // "walk" | "wait" | "ride" | "arrived"
        val title: String,            // "38 hattını bekle"
        val detail: String,           // "Fatih Sultan Mehmet Blv. durağı • ~6 dk"
        val stopsRemaining: Int? = null,
        val distanceToNextMeters: Double? = null,
        val progress: Float = 0f      // 0..1 (aşama ilerlemesi)
    )
    val state = MutableStateFlow<RuntimeState?>(null)
}
