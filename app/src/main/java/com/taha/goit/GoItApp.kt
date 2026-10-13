package com.taha.goit

import android.app.Application
import com.taha.goit.data.api.NominatimGeocoder
import com.taha.goit.data.api.OsrmRouter
import com.taha.goit.data.api.TransitRepository
import com.taha.goit.data.local.FavoritesManager
import com.taha.goit.data.local.JourneyHistoryManager
import com.taha.goit.data.local.RouteHistoryManager
import com.taha.goit.data.local.SavedPlacesManager
import com.taha.goit.data.local.ScheduleCache
import com.taha.goit.data.local.SettingsManager
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import java.io.File
import java.util.concurrent.TimeUnit

class GoItApp : Application() {
    companion object {
        lateinit var instance: GoItApp
            private set
    }

    lateinit var okHttpClient: OkHttpClient
        private set
    lateinit var transitRepository: TransitRepository
        private set
    lateinit var nominatimGeocoder: NominatimGeocoder
        private set
    lateinit var osrmRouter: OsrmRouter
        private set

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        MapLibre.getInstance(this)

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        transitRepository = TransitRepository.getInstance(okHttpClient)
        nominatimGeocoder = NominatimGeocoder(okHttpClient)
        osrmRouter = OsrmRouter(File(filesDir, "osrm"), okHttpClient)

        favoritesManager = FavoritesManager(this)
        historyManager = RouteHistoryManager(this)
        savedPlacesManager = SavedPlacesManager(this)
        journeyHistoryManager = JourneyHistoryManager(this)
        settingsManager = SettingsManager(this)
        scheduleCache = ScheduleCache(this)

        Thread {
            com.taha.goit.data.local.StationDatabase.load(this)
            com.taha.goit.data.local.RouteCatalog.load(this)
        }.start()
    }
}
