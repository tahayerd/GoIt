package com.taha.goit

import android.app.Application
import org.maplibre.android.MapLibre

class GoItApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // MapLibre FOSS initialization without proprietary telemetry/keys
        MapLibre.getInstance(this)
    }
}
