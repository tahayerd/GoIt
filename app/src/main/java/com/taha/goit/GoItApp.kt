package com.taha.goit

import android.app.Application
import com.taha.goit.data.local.FavoritesManager
import com.taha.goit.data.local.JourneyHistoryManager
import com.taha.goit.data.local.RouteHistoryManager
import com.taha.goit.data.local.SavedPlacesManager
import org.maplibre.android.MapLibre

class GoItApp : Application() {
    lateinit var favoritesManager: FavoritesManager
        private set
    lateinit var historyManager: RouteHistoryManager
        private set
    lateinit var savedPlacesManager: SavedPlacesManager
        private set
    lateinit var journeyHistoryManager: JourneyHistoryManager
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        favoritesManager = FavoritesManager(this)
        historyManager = RouteHistoryManager(this)
        savedPlacesManager = SavedPlacesManager(this)
        journeyHistoryManager = JourneyHistoryManager(this)
    }
}
