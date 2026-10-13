package com.taha.goit.data.local

import android.content.Context

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("goit_prefs", Context.MODE_PRIVATE)

    var walkAccessMeters: Int
        get() = prefs.getInt("walk_access_meters", 800)
        set(value) = prefs.edit().putInt("walk_access_meters", value).apply()

    var walkTransferMeters: Int
        get() = prefs.getInt("walk_transfer_meters", 400)
        set(value) = prefs.edit().putInt("walk_transfer_meters", value).apply()

    var favoriteAlertsEnabled: Boolean
        get() = prefs.getBoolean("favorite_alerts_enabled", false)
        set(value) = prefs.edit().putBoolean("favorite_alerts_enabled", value).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system" // "system" | "light" | "dark" | "amoled"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var fareType: String
        get() = prefs.getString("fare_type", "TAM") ?: "TAM" // "TAM" | "İNDİRİMLİ" | "ÖĞRENCİ"
        set(value) = prefs.edit().putString("fare_type", value).apply()

    // Y-17: İniş alarmı ayarları (kalan durak ve kalan metre eşiği)
    var alightStopsThreshold: Int
        get() = prefs.getInt("alight_stops_threshold", 1)
        set(value) = prefs.edit().putInt("alight_stops_threshold", value).apply()

    var alightDistanceMeters: Int
        get() = prefs.getInt("alight_distance_meters", 250)
        set(value) = prefs.edit().putInt("alight_distance_meters", value).apply()

    // O-8: Yürüme hızı profili ("slow" | "normal" | "fast")
    var walkingSpeedProfile: String
        get() = prefs.getString("walking_speed_profile", "normal") ?: "normal"
        set(value) = prefs.edit().putString("walking_speed_profile", value).apply()

    fun resetOnboarding() {
        prefs.edit().remove("onboarding_done_v1").apply()
    }
}
