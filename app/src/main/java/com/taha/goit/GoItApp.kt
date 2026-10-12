package com.taha.goit

import android.app.Application
import com.taha.goit.data.local.FavoritesManager
import com.taha.goit.data.local.JourneyHistoryManager
import com.taha.goit.data.local.RouteHistoryManager
import com.taha.goit.data.local.SavedPlacesManager
import com.taha.goit.data.local.ScheduleCache
import com.taha.goit.data.local.SettingsManager
import com.taha.goit.data.local.VisitedPlacesManager
import com.taha.goit.util.OsrmRouter
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
    lateinit var settingsManager: SettingsManager
        private set
    lateinit var scheduleCache: ScheduleCache
        private set
    lateinit var visitedPlacesManager: VisitedPlacesManager
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        OsrmRouter.init(this)
        favoritesManager = FavoritesManager(this)
        historyManager = RouteHistoryManager(this)
        savedPlacesManager = SavedPlacesManager(this)
        journeyHistoryManager = JourneyHistoryManager(this)
        settingsManager = SettingsManager(this)
        scheduleCache = ScheduleCache(this)
        visitedPlacesManager = VisitedPlacesManager(this)
    }
}
