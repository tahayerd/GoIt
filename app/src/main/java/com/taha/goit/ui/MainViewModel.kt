package com.taha.goit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taha.goit.data.api.NominatimGeocoder
import com.taha.goit.data.api.OsrmRouter
import com.taha.goit.data.api.TransitRepository
import com.taha.goit.data.local.FavoriteItem
import com.taha.goit.data.local.FavoritesManager
import com.taha.goit.data.local.RouteCatalog
import com.taha.goit.data.local.RouteHistoryManager
import com.taha.goit.data.local.JourneyHistoryEntry
import com.taha.goit.data.local.JourneyHistoryManager
import com.taha.goit.data.local.SavedPlace
import com.taha.goit.data.local.SavedPlacesManager
import com.taha.goit.data.local.ScheduleCache
import com.taha.goit.data.local.SettingsManager
import com.taha.goit.data.local.StationDatabase
import com.taha.goit.data.local.VisitedPlace
import com.taha.goit.data.local.getBurulasWeekDay
import com.taha.goit.data.local.normalizeRouteCode
import com.taha.goit.data.model.*
import com.taha.goit.service.JourneyPayloadFactory
import com.taha.goit.util.MapsLinkParser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maplibre.android.geometry.LatLng
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Arama sonucu: durak ("S"), mekan ("P"), OSM yedeği ("O") veya Google linki ("G"). */
data class PlaceResult(
    val name: String,
    val kind: String,
    val stopId: Int?,
    val lat: Double?,
    val lng: Double?,
    val subtitle: String? = null
)

/** Yolculuk uç noktası: serbest nokta veya durak. */
data class TripPoint(
    val name: String,
    val lat: Double,
    val lng: Double,
    val isUserLocation: Boolean = false
)

/** Duraklar sekmesi: yaklaşan araç ve ücret bilgisi. */
data class NearbyBusEta(
    val routeCode: String,
    val minutesText: String,
    val timeSeconds: Int,
    val priceText: String? = null,
    val ac: Boolean? = null,
    val accessible: Boolean? = null
)

/** Duraklar sekmesi: durak + mesafe + yaklaşan araçlar. */
data class NearbyStopUi(
    val station: LocalStation,
    val distanceMeters: Double,
    val buses: List<NearbyBusEta> = emptyList()
)

data class MainUiState(
    // Hat seçimi ve canlı takip
    val selectedRouteCode: String? = null,
    val selectedRouteHatNo: Int? = null,
    val selectedRouteStops: List<RouteStop> = emptyList(),
    val selectedRouteCoordinates: List<RouteCoordinate> = emptyList(),
    val selectedRouteDirections: List<String> = emptyList(),
    val selectedDirection: String = "G",
    val routePrice: List<CardPrice> = emptyList(),
    val realtimeBuses: List<BusRealtimeLocation> = emptyList(),
    val isTrackingActive: Boolean = false,
    // Durak detay
    val selectedStop: RouteStop? = null,
    val stopEtaList: List<StopRemainingTime> = emptyList(),
    val stopSchedule: List<ScheduleEntry> = emptyList(),
    val showSchedule: Boolean = false,
    val stopLines: List<RouteByStopItem> = emptyList(),
    // Favoriler
    val favorites: List<FavoriteItem> = emptyList(),
    // Yol tarifi (A'dan B'ye)
    val tripSearchField: String? = null, // "origin" | "dest" | null
    val tripSearchQuery: String = "",
    val tripSearchResults: List<PlaceResult> = emptyList(),
    val isTripSearching: Boolean = false,
    val tripOrigin: TripPoint? = null,
    val tripDestination: TripPoint? = null,
    val tripPlans: List<RoutePlan> = emptyList(),
    val isTripPlanning: Boolean = false,
    val selectedPlan: RoutePlan? = null,
    val planEtas: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
    // Yakındaki duraklar
    val nearbyStops: List<NearbyStopUi> = emptyList(),
    val isLoadingNearbyEtas: Boolean = false,
    val locationAvailable: Boolean = true,
    val userLat: Double? = null,
    val userLng: Double? = null,
    // Araç seçimi
    val selectedBus: BusRealtimeLocation? = null,
    val selectedBusDistance: Int? = null,
    // Hatlar sekmesi
    val allRoutes: List<RouteCatalogEntry> = emptyList(),
    val recentRoutes: List<String> = emptyList(),
    val nearestStopEtas: List<NearbyBusEta> = emptyList(),
    val nearestStopName: String? = null,
    val showAllRoutes: Boolean = false,
    // Sekme yönlendirme olayı (örn. "Buradan Git" → Yol Tarifi sekmesi)
    val navigateToTab: Int? = null,
    // Kayıtlı Yerler ve Yolculuk Geçmişi
    val savedPlaces: List<SavedPlace> = emptyList(),
    val journeyHistory: List<JourneyHistoryEntry> = emptyList(),
    // Rota sıralama seçeneği ("onerilen" | "hizli" | "azyurume" | "azaktarma")
    val planSort: String = "onerilen",
    // Ayarlar (Yürüme limitleri & bildirimler)
    val walkAccessMeters: Int = 800,
    val walkTransferMeters: Int = 400,
    val favoriteAlertsEnabled: Boolean = false,
    val themeMode: String = "system", // "system" | "light" | "dark" | "amoled"
    // v1.6.0: Detaylı Rota & Tarife & Yollara Oturtma
    val fareType: String = "TAM", // "TAM" | "İNDİRİMLİ" | "ÖĞRENCİ"
    val mostVisitedPlaces: List<VisitedPlace> = emptyList(),
    val selectedPlanLiveBus: BusRealtimeLocation? = null,
    val selectedPlanUpcomingRuns: List<String> = emptyList(),
    val selectedPlanEstimatedArrival: String? = null,
    val planBusLegShapes: Map<Int, List<LatLng>> = emptyMap(),
    val planWalkLegShapes: Map<Int, List<LatLng>> = emptyMap(),
    val isUpdatingSchedule: Boolean = false,
    val scheduleLastUpdateText: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    constructor() : this(com.taha.goit.GoItApp.instance)

    private val app = application as com.taha.goit.GoItApp
    private val repository = app.transitRepository
    private val geocoder = app.nominatimGeocoder
    private var osrmRouter: OsrmRouter? = app.osrmRouter

    private var favoritesManager: FavoritesManager? = app.favoritesManager
    private var historyManager: RouteHistoryManager? = app.historyManager
    private var savedPlacesManager: SavedPlacesManager? = app.savedPlacesManager
    private var journeyHistoryManager: JourneyHistoryManager? = app.journeyHistoryManager
    private var settingsManager: SettingsManager? = app.settingsManager
    private var scheduleCache: ScheduleCache? = app.scheduleCache

    private var currentNearestStopId: Int? = null

    private val _uiState = MutableStateFlow(
        MainUiState(
            favorites = app.favoritesManager.getFavorites(),
            recentRoutes = app.historyManager.getRecent(),
            savedPlaces = app.savedPlacesManager.getAll(),
            journeyHistory = app.journeyHistoryManager.getHistory(),
            mostVisitedPlaces = app.journeyHistoryManager.getMostVisited(5),
            walkAccessMeters = app.settingsManager.walkAccessMeters,
            walkTransferMeters = app.settingsManager.walkTransferMeters,
            favoriteAlertsEnabled = app.settingsManager.favoriteAlertsEnabled,
            themeMode = app.settingsManager.themeMode,
            fareType = app.settingsManager.fareType
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var trackingJob: Job? = null
    private var etaJob: Job? = null
    private var searchJob: Job? = null
    private var nearbyJob: Job? = null
    private var nearestEtaLoopJob: Job? = null
    private var planEtaJob: Job? = null

    // ---------- Başlatma ----------

    fun setManagers(favorites: FavoritesManager, history: RouteHistoryManager) {
        favoritesManager = favorites
        historyManager = history
        _uiState.update { it.copy(
            favorites = favorites.getFavorites(),
            recentRoutes = history.getRecent()
        ) }
    }

    fun setPlacesManager(manager: SavedPlacesManager) {
        savedPlacesManager = manager
        _uiState.update { it.copy(savedPlaces = manager.getAll()) }
    }

    fun setJourneyHistoryManager(manager: JourneyHistoryManager) {
        journeyHistoryManager = manager
        _uiState.update { it.copy(
            journeyHistory = manager.getHistory(),
            mostVisitedPlaces = manager.getMostVisited(5)
        ) }
    }

    fun setSettingsManager(manager: SettingsManager) {
        settingsManager = manager
        _uiState.update { it.copy(
            walkAccessMeters = manager.walkAccessMeters,
            walkTransferMeters = manager.walkTransferMeters,
            favoriteAlertsEnabled = manager.favoriteAlertsEnabled,
            themeMode = manager.themeMode,
            fareType = manager.fareType
        ) }
    }

    fun setScheduleCache(cache: ScheduleCache) {
        scheduleCache = cache
        refreshScheduleStatus()
        viewModelScope.launch {
            cache.checkAndUpdateSchedule(force = false)
            refreshScheduleStatus()
        }
    }

    fun setOsrmRouter(router: OsrmRouter) {
        osrmRouter = router
    }

    fun updateFareType(type: String) {
        settingsManager?.fareType = type
        _uiState.update { it.copy(fareType = type) }
    }

    fun refreshScheduleStatus() {
        val cache = scheduleCache ?: return
        val ts = cache.getLastUpdateTimestamp()
        val text = if (ts > 0L) {
            val dt = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
            val formatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            "Son güncelleme: ${dt.format(formatter)}"
        } else {
            "Henüz indirilmedi (çevrimiçi kullanılır)"
        }
        _uiState.update { it.copy(scheduleLastUpdateText = text) }
    }

    fun updateScheduleData(force: Boolean = true) {
        val cache = scheduleCache ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingSchedule = true) }
            cache.checkAndUpdateSchedule(force = force)
            refreshScheduleStatus()
            _uiState.update { it.copy(isUpdatingSchedule = false) }
        }
    }

    fun updateThemeMode(mode: String) {
        settingsManager?.themeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun updateWalkAccess(meters: Int) {
        settingsManager?.walkAccessMeters = meters
        _uiState.update { it.copy(walkAccessMeters = meters) }
    }

    fun updateWalkTransfer(meters: Int) {
        settingsManager?.walkTransferMeters = meters
        _uiState.update { it.copy(walkTransferMeters = meters) }
    }

    fun toggleFavoriteAlerts(enabled: Boolean) {
        settingsManager?.favoriteAlertsEnabled = enabled
        _uiState.update { it.copy(favoriteAlertsEnabled = enabled) }
    }

    fun resetOnboarding() {
        settingsManager?.resetOnboarding()
    }

    fun refreshSavedPlaces() {
        val manager = savedPlacesManager ?: return
        _uiState.update { it.copy(savedPlaces = manager.getAll()) }
    }

    fun savePlace(place: SavedPlace) {
        savedPlacesManager?.save(place)
        refreshSavedPlaces()
    }

    fun removeSavedPlace(id: String) {
        savedPlacesManager?.remove(id)
        refreshSavedPlaces()
    }

    fun selectSavedPlaceAsDestination(place: SavedPlace) {
        val uLat = _uiState.value.userLat
        val uLng = _uiState.value.userLng
        val origin = _uiState.value.tripOrigin ?: if (uLat != null && uLng != null) {
            TripPoint("Konumum", uLat, uLng, isUserLocation = true)
        } else null

        _uiState.update { it.copy(
            tripOrigin = origin,
            tripDestination = TripPoint(place.name, place.lat, place.lng),
            tripSearchField = null,
            tripSearchQuery = "",
            tripSearchResults = emptyList(),
            tripPlans = emptyList(),
            selectedPlan = null,
            errorMessage = null
        ) }
        if (origin != null) {
            planTrip()
        }
    }

    fun selectSavedPlaceAsOrigin(place: SavedPlace) {
        _uiState.update { it.copy(
            tripOrigin = TripPoint(place.name, place.lat, place.lng),
            tripSearchField = null,
            tripSearchQuery = "",
            tripSearchResults = emptyList(),
            tripPlans = emptyList(),
            selectedPlan = null,
            errorMessage = null
        ) }
    }

    fun repeatJourney(entry: JourneyHistoryEntry) {
        _uiState.update { it.copy(
            tripOrigin = TripPoint(entry.originName, entry.originLat, entry.originLng),
            tripDestination = TripPoint(entry.destName, entry.destLat, entry.destLng),
            tripSearchField = null,
            tripSearchQuery = "",
            tripSearchResults = emptyList(),
            tripPlans = emptyList(),
            selectedPlan = null,
            errorMessage = null
        ) }
        planTrip()
    }

    fun setPlanSort(sort: String) {
        _uiState.update { it.copy(planSort = sort) }
    }

    /** Gömülü veritabanları yüklendikten sonra çağrılır. */
    fun onLocalDataReady() {
        _uiState.update { it.copy(allRoutes = RouteCatalog.all()) }
    }

    fun refreshFavorites() {
        val manager = favoritesManager ?: return
        _uiState.update { it.copy(favorites = manager.getFavorites()) }
    }

    private fun refreshRecent() {
        _uiState.update { it.copy(recentRoutes = historyManager?.getRecent() ?: emptyList()) }
    }

    private var lastNearbyLat: Double? = null
    private var lastNearbyLng: Double? = null

    /**
     * Madde 4: Sürekli konum güncellemeleri.
     * Marker her fix'te yumuşak güncellenir; API sorguları (loadNearby)
     * yalnızca anlamlı hareket (>25 m) olduğunda tetiklenir.
     */
    fun updateUserLocation(lat: Double, lng: Double) {
        _uiState.update { it.copy(
            userLat = lat,
            userLng = lng,
            locationAvailable = true
        ) }
        val lLat = lastNearbyLat
        val lLng = lastNearbyLng
        if (lLat == null || lLng == null || TransitRepository.calculateDistanceMeters(lat, lng, lLat, lLng) > 25.0) {
            lastNearbyLat = lat
            lastNearbyLng = lng
            loadNearby(lat, lng)
        }
    }

    // ---------- Yakındaki Duraklar ----------

    fun loadNearby(lat: Double, lng: Double) {
        val nearest = StationDatabase.nearestStations(lat, lng, 15)
        val prev = _uiState.value.nearbyStops.associateBy { it.station.i }
        val base = nearest.map { (s, d) ->
            NearbyStopUi(s, d, prev[s.i]?.buses ?: emptyList())
        }
        val firstStation = nearest.firstOrNull()?.first
        _uiState.update { it.copy(
            userLat = lat,
            userLng = lng,
            locationAvailable = true,
            nearbyStops = base,
            isLoadingNearbyEtas = nearest.isNotEmpty(),
            nearestStopName = firstStation?.n,
            tripOrigin = _uiState.value.tripOrigin
                ?: TripPoint("Konumum", lat, lng, isUserLocation = true)
        ) }
        if (nearest.isNotEmpty()) {
            val nearestStopId = nearest.first().first.i
            loadNearbyEtas(nearest)
            if (currentNearestStopId != nearestStopId) {
                currentNearestStopId = nearestStopId
                startNearestStopEtaLoop(nearestStopId, nearest.first().first.n)
            }
        }
    }

    fun markLocationMissing() {
        _uiState.update { it.copy(locationAvailable = false, isLoadingNearbyEtas = false) }
    }

    /** Hatlar sekmesi: kullanıcının en yakın durağına yaklaşan hatların periyodik ETA'sı (~15 s). */
    private fun startNearestStopEtaLoop(stopId: Int, stopName: String) {
        nearestEtaLoopJob?.cancel()
        nearestEtaLoopJob = viewModelScope.launch {
            while (true) {
                try {
                    val raw = repository.getStationRemainingTime(stopId)
                    val sorted = raw.mapNotNull { e ->
                        val code = e.routeCode ?: return@mapNotNull null
                        val t = e.realTime ?: e.passTime
                        NearbyBusEta(
                            routeCode = code,
                            minutesText = formatEtaTime(t),
                            timeSeconds = parseEtaSeconds(t),
                            ac = e.isAirConditioner,
                            accessible = e.isSuitableForDisabled
                        )
                    }
                        .sortedBy { it.timeSeconds }
                        .distinctBy { it.routeCode }
                    _uiState.update { it.copy(
                        nearestStopEtas = sorted,
                        nearestStopName = stopName
                    ) }
                } catch (_: Exception) {
                }
                delay(15000)
            }
        }
    }

    
    fun stopBackgroundPolling() {
        nearestEtaLoopJob?.cancel()
        nearestEtaLoopJob = null
        currentNearestStopId = null
    }

    fun resumeBackgroundPolling() {
        val lat = _uiState.value.userLat
        val lng = _uiState.value.userLng
        if (lat != null && lng != null) {
            loadNearby(lat, lng)
        }
    }

    fun toggleShowAllRoutes() {
        _uiState.update { it.copy(showAllRoutes = !_uiState.value.showAllRoutes) }
    }

    private fun loadNearbyEtas(nearest: List<Pair<LocalStation, Double>>) {
        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            val top = nearest.take(12)
            val etaSem = Semaphore(4)
            val etas = supervisorScope {
                top.map { (s, _) ->
                    async { etaSem.withPermit { repository.getStationRemainingTime(s.i) } }
                }.awaitAll()
            }
            val codes = etas.flatten().mapNotNull { it.routeCode }.distinct().take(24)
            val priceSem = Semaphore(3)
            val prices = mutableMapOf<String, String?>()
            supervisorScope {
                codes.map { c ->
                    async { priceSem.withPermit { c to repository.getRoutePriceCached(c) } }
                }.awaitAll().forEach { (c, list) ->
                    val p = list.firstOrNull { it.cardType?.contains("TAM") == true } ?: list.firstOrNull()
                    prices[c] = formatPriceText(p?.price)
                }
            }
            val rowsByStop = HashMap<Int, List<NearbyBusEta>>()
            top.forEachIndexed { idx, (s, _) ->
                val rows = etas[idx]
                    .mapNotNull { e ->
                        val code = e.routeCode ?: return@mapNotNull null
                        val raw = e.realTime ?: e.passTime
                        NearbyBusEta(
                            routeCode = code,
                            minutesText = formatEtaTime(raw),
                            timeSeconds = parseEtaSeconds(raw),
                            priceText = prices[code],
                            ac = e.isAirConditioner,
                            accessible = e.isSuitableForDisabled
                        )
                    }
                    .sortedBy { it.timeSeconds }
                    .distinctBy { it.routeCode }
                    .take(3)
                rowsByStop[s.i] = rows
            }
            _uiState.update { it.copy(
                nearbyStops = _uiState.value.nearbyStops.map { ui ->
                    val rows = rowsByStop[ui.station.i]
                    if (rows != null) ui.copy(buses = rows) else ui
                },
                isLoadingNearbyEtas = false
            ) }
        }
    }

    /** Yakındaki duraktan detay sayfasını açar. */
    fun selectNearbyStop(s: LocalStation) {
        val stop = RouteStop(
            stopId = s.i,
            stopName = s.n,
            latitude = s.la.toString(),
            longitude = s.lo.toString()
        )
        selectStop(stop)
    }

    /** Haritadan durak ID'siyle seçim (yerel veritabanından ad/koordinat çözülür). */
    fun selectNearbyStopById(stopId: Int) {
        val s = StationDatabase.findById(stopId)
        if (s != null) {
            selectNearbyStop(s)
        } else {
            val stop = RouteStop(stopId = stopId, stopName = "Durak $stopId")
            selectStop(stop)
        }
    }

    /** Haritadan plaka ile araç seçimi. */
    fun selectBusByPlate(plate: String) {
        val bus = _uiState.value.realtimeBuses.firstOrNull { it.plaka == plate } ?: return
        selectBus(bus)
    }

    /** Favori durağı açar (koordinat yerel veritabanından çözülür). */
    fun selectFavoriteStop(fav: FavoriteItem) {
        val ls = StationDatabase.findById(fav.refId)
        val stop = RouteStop(
            stopId = fav.refId,
            stopName = ls?.n ?: fav.title,
            latitude = (ls?.la)?.toString(),
            longitude = (ls?.lo)?.toString()
        )
        selectStop(stop)
    }

    // ---------- Durak Seçimi ve Canlı ETA ----------

    fun selectStop(stop: RouteStop) {
        _uiState.update { it.copy(selectedStop = stop, stopLines = emptyList(), stopSchedule = emptyList(), showSchedule = false) }
        val stopId = stop.stopId ?: return
        etaJob?.cancel()
        etaJob = viewModelScope.launch {
            while (true) {
                val eta = repository.getStationRemainingTime(stopId)
                _uiState.update { it.copy(stopEtaList = eta) }
                delay(10000)
            }
        }
        viewModelScope.launch {
            val lines = repository.getRoutesByStop(stopId)
            _uiState.update { it.copy(stopLines = lines) }
        }
    }

    fun clearStop() {
        etaJob?.cancel()
        _uiState.update { it.copy(
            selectedStop = null,
            stopEtaList = emptyList(),
            stopSchedule = emptyList(),
            showSchedule = false,
            stopLines = emptyList()
        ) }
    }

    /** Durak tarifesini aç/kapat. */
    fun toggleSchedule() {
        val state = _uiState.value
        if (state.showSchedule) {
            _uiState.update { it.copy(showSchedule = false) }
            return
        }
        val stopId = state.selectedStop?.stopId ?: return
        val weekDay = getBurulasWeekDay()
        _uiState.update { it.copy(showSchedule = true, stopSchedule = emptyList()) }
        viewModelScope.launch {
            val cached = scheduleCache?.getSchedule(stopId, weekDay)
            val schedule = if (!cached.isNullOrEmpty()) cached else repository.getStopSchedule(stopId, weekDay)
            _uiState.update { it.copy(stopSchedule = schedule) }
        }
    }

    // ---------- Yol Tarifi (A'dan B'ye) ----------

    fun openTripSearch(field: String) {
        _uiState.update { it.copy(
            tripSearchField = field,
            tripSearchQuery = "",
            tripSearchResults = emptyList()
        ) }
    }

    fun cancelTripSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(
            tripSearchField = null,
            tripSearchQuery = "",
            tripSearchResults = emptyList(),
            isTripSearching = false
        ) }
    }

    fun onTripSearchQueryChange(query: String) {
        _uiState.update { it.copy(tripSearchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _uiState.update { it.copy(tripSearchResults = emptyList(), isTripSearching = false) }
            return
        }

        // O-3: Yerel aramayı ANINDA çalıştır (yapay gecikme yok!)
        val local = StationDatabase.search(trimmed, 30).map {
            PlaceResult(
                name = it.n,
                kind = if (it.t == 1) "S" else "P",
                stopId = if (it.t == 1) it.i else null,
                lat = it.la,
                lng = it.lo,
                subtitle = if (it.t == 1) "Durak" else "Mekan"
            )
        }
        _uiState.update { it.copy(tripSearchResults = local, isTripSearching = true) }

        searchJob = viewModelScope.launch {
            // 1. Google Maps linki veya koordinat kontrolü
            val parsedLocation = MapsLinkParser.parse(trimmed)
            if (parsedLocation != null) {
                val direct = PlaceResult(
                    name = parsedLocation.label ?: "Harita Konumu",
                    kind = "G",
                    stopId = null,
                    lat = parsedLocation.lat,
                    lng = parsedLocation.lng,
                    subtitle = String.format(java.util.Locale.US, "%.5f, %.5f", parsedLocation.lat, parsedLocation.lng)
                )
                _uiState.update {
                    it.copy(
                        tripSearchResults = listOf(direct),
                        isTripSearching = false
                    )
                }
                return@launch
            }

            // Yalnızca yerel yetersizse Nominatim çağrısını debounce et
            if (local.size < 8) {
                delay(300)
                val osm = geocoder.searchPlaces(trimmed, 6).map { o ->
                    PlaceResult(
                        name = o.name ?: o.displayName?.substringBefore(", ") ?: trimmed,
                        kind = "O",
                        stopId = null,
                        lat = o.lat?.toDoubleOrNull(),
                        lng = o.lon?.toDoubleOrNull(),
                        subtitle = "OpenStreetMap"
                    )
                }
                val merged = local + osm
                _uiState.update { it.copy(tripSearchResults = merged, isTripSearching = false) }
            } else {
                _uiState.update { it.copy(isTripSearching = false) }
            }
        }
    }

    fun selectTripPlace(place: PlaceResult) {
        val state = _uiState.value
        val field = state.tripSearchField ?: return

        val lat = place.lat ?: return
        val lng = place.lng ?: return
        val point = TripPoint(name = place.name, lat = lat, lng = lng)

        when (field) {
            "origin" -> {
                _uiState.update { it.copy(
                    tripOrigin = point,
                    tripSearchField = null,
                    tripSearchQuery = "",
                    tripSearchResults = emptyList(),
                    tripPlans = emptyList(),
                    selectedPlan = null,
                    errorMessage = null
                ) }
            }
            "dest" -> {
                _uiState.update { it.copy(
                    tripDestination = point,
                    tripSearchField = null,
                    tripSearchQuery = "",
                    tripSearchResults = emptyList(),
                    tripPlans = emptyList(),
                    selectedPlan = null,
                    errorMessage = null
                ) }
                // v1.2.0: Varış seçildiğinde otomatik planlama YAPILMAZ; "Rota Bul" butonuna basılması beklenir.
            }
        }
    }

    /** "Konumumu kullan" butonu: kalkış noktasını mevcut GPS koordinatına ayarlar. */
    fun setOriginToCurrentLocation() {
        val lat = _uiState.value.userLat
        val lng = _uiState.value.userLng
        if (lat != null && lng != null) {
            _uiState.update { it.copy(
                tripOrigin = TripPoint("Konumum", lat, lng, isUserLocation = true),
                tripSearchField = null,
                tripSearchQuery = "",
                tripSearchResults = emptyList(),
                tripPlans = emptyList(),
                selectedPlan = null,
                errorMessage = null
            ) }
        }
    }

    /** Dışarıdan (Intent / Google Maps linki) gelen hedef konumu doğrudan varış noktası yapar. */
    fun setDestinationFromExternal(lat: Double, lng: Double, label: String?) {
        _uiState.update { it.copy(
            tripDestination = TripPoint(label ?: "Google Haritalar", lat, lng),
            tripSearchField = null,
            tripSearchQuery = "",
            tripSearchResults = emptyList(),
            tripPlans = emptyList(),
            selectedPlan = null,
            errorMessage = null,
            navigateToTab = 0
        ) }
    }

    fun planTrip() {
        val o = _uiState.value.tripOrigin ?: return
        val d = _uiState.value.tripDestination ?: return

        val distance = TransitRepository.calculateDistanceMeters(o.lat, o.lng, d.lat, d.lng)
        if (distance < 30.0) {
            _uiState.update { it.copy(
                tripPlans = emptyList(),
                errorMessage = "Kalkış ve varış aynı nokta. Farklı bir varış seçin."
            ) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isTripPlanning = true, selectedPlan = null, errorMessage = null) }
            val access = _uiState.value.walkAccessMeters.toDouble()
            val transfer = _uiState.value.walkTransferMeters.toDouble()
            val transitPlans = repository.planTrip(o.lat, o.lng, d.lat, d.lng, maxAccess = access, maxTransferWalk = transfer)

            // "Sadece yürü" alternatifi (≤2 km)
            val finalPlans = if (distance <= 2000.0) {
                val walkPlan = repository.walkOnlyPlan(o.lat, o.lng, d.lat, d.lng)
                listOf(walkPlan) + transitPlans
            } else {
                transitPlans
            }

            val errorMsg = when (repository.getLastPlanError()) {
                "TIMEOUT" -> "Sunucu yanıt vermedi (zaman aşımı). Lütfen tekrar deneyin."
                "NETWORK" -> "İnternet bağlantısı kurulamadı. Ağınızı kontrol edin."
                "NO_ROUTE" -> if (finalPlans.isEmpty()) "Bu iki nokta arasında uygun rota bulunamadı." else null
                else -> if (finalPlans.isEmpty()) "Bu iki nokta arasında uygun rota bulunamadı." else null
            }

            _uiState.update {
                it.copy(
                    tripPlans = finalPlans,
                    isTripPlanning = false,
                    errorMessage = errorMsg
                )
            }
            if (finalPlans.isNotEmpty()) {
                journeyHistoryManager?.let { mgr ->
                    mgr.add(
                        JourneyHistoryEntry(
                            originName = o.name,
                            originLat = o.lat,
                            originLng = o.lng,
                            destName = d.name,
                            destLat = d.lat,
                            destLng = d.lng,
                            ts = System.currentTimeMillis()
                        )
                    )
                    mgr.recordDestinationVisit(d.name, d.lat, d.lng)
                    _uiState.update { it.copy(
                        journeyHistory = mgr.getHistory(),
                        mostVisitedPlaces = mgr.getMostVisited(5)
                    ) }
                }
                startPlanEtaLoop()
            } else {
                planEtaJob?.cancel()
            }
        }
    }

    /**
     * Madde 3 & Madde 2: Plan seçildiğinde:
     * - Bacakların gerçek yol geometrileri yüklenir (bus shape kırpma + OSRM foot)
     * - Canlı araç aranır ve eşlenir
     * - Sonraki kalkış saatleri (dakika listesi) hesaplanır
     * - Hedefe tahmini varış saati (HH:mm) hesaplanır
     */
    fun selectRouteFromPlan(plan: RoutePlan) {
        _uiState.update { it.copy(
            selectedPlan = plan,
            selectedRouteCode = null,
            selectedStop = null,
            selectedPlanLiveBus = null,
            selectedPlanUpcomingRuns = emptyList(),
            selectedPlanEstimatedArrival = null
        ) }
        loadPlanLegShapes(plan)
        loadPlanDetailData(plan)
    }

    fun deselectPlan() {
        _uiState.update { it.copy(
            selectedPlan = null,
            selectedPlanLiveBus = null,
            selectedPlanUpcomingRuns = emptyList(),
            selectedPlanEstimatedArrival = null,
            routePrice = emptyList(),
            planBusLegShapes = emptyMap(),
            planWalkLegShapes = emptyMap()
        ) }
    }

    private fun loadPlanLegShapes(plan: RoutePlan) {
        viewModelScope.launch {
            val busShapes = mutableMapOf<Int, List<LatLng>>()
            val walkShapes = mutableMapOf<Int, List<LatLng>>()

            for ((idx, leg) in plan.legs.withIndex()) {
                if (leg.kind == "bus") {
                    val code = leg.routeCode ?: ""
                    val hatNo = leg.routeNo ?: RouteCatalog.findByCode(code)?.hatNo
                    if (hatNo != null) {
                        try {
                            val coords = repository.getRouteCoordinates(hatNo.toString())
                            val clipped = clipRouteCoordinatesToLeg(coords, leg.direction, leg.boardStop, leg.alightStop)
                            if (clipped.isNotEmpty()) {
                                busShapes[idx] = clipped
                            }
                        } catch (_: Exception) {}
                    }
                } else if (leg.kind == "walk") {
                    val (p1, p2) = getWalkEndpoints(plan, idx)
                    if (p1 != null && p2 != null) {
                        val footPath = osrmRouter?.footRoute(p1.first, p1.second, p2.first, p2.second)
                        if (footPath != null && footPath.isNotEmpty()) {
                            walkShapes[idx] = footPath
                        }
                    }
                }
            }

            _uiState.update { it.copy(
                planBusLegShapes = busShapes,
                planWalkLegShapes = walkShapes
            ) }
        }
    }

    private fun getWalkEndpoints(plan: RoutePlan, walkIdx: Int): Pair<Pair<Double, Double>?, Pair<Double, Double>?> {
        val o = _uiState.value.tripOrigin
        val d = _uiState.value.tripDestination
        val legs = plan.legs
        val leg = legs.getOrNull(walkIdx) ?: return null to null

        val p1 = if (walkIdx == 0) {
            if (o != null) o.lat to o.lng else null
        } else {
            val prevLeg = legs.getOrNull(walkIdx - 1)
            val stop = prevLeg?.alightStop
            val la = stop?.latitude?.toDoubleOrNull()
            val lo = stop?.longitude?.toDoubleOrNull()
            if (la != null && lo != null) la to lo else null
        }

        val p2 = when {
            leg.boardStop != null -> {
                val la = leg.boardStop.latitude?.toDoubleOrNull()
                val lo = leg.boardStop.longitude?.toDoubleOrNull()
                if (la != null && lo != null) la to lo else null
            }
            leg.alightStop != null -> {
                val la = leg.alightStop.latitude?.toDoubleOrNull()
                val lo = leg.alightStop.longitude?.toDoubleOrNull()
                if (la != null && lo != null) la to lo else null
            }
            else -> if (d != null) d.lat to d.lng else null
        }

        return p1 to p2
    }

    private fun clipRouteCoordinatesToLeg(
        coords: List<RouteCoordinate>,
        direction: String?,
        boardStop: RouteStop?,
        alightStop: RouteStop?
    ): List<LatLng> {
        if (coords.isEmpty()) return emptyList()
        val filtered = if (!direction.isNullOrBlank()) {
            coords.filter { it.routeDirection == direction }
        } else coords

        val sorted = (if (filtered.isNotEmpty()) filtered else coords).sortedBy { it.sequence }
        val points = sorted.mapNotNull { c ->
            val la = c.latitude?.toDoubleOrNull() ?: return@mapNotNull null
            val lo = c.logitude?.toDoubleOrNull() ?: return@mapNotNull null
            LatLng(la, lo)
        }
        if (points.size < 2) return points

        val bLat = boardStop?.latitude?.toDoubleOrNull()
        val bLng = boardStop?.longitude?.toDoubleOrNull()
        val aLat = alightStop?.latitude?.toDoubleOrNull()
        val aLng = alightStop?.longitude?.toDoubleOrNull()

        if (bLat == null || bLng == null || aLat == null || aLng == null) {
            return points
        }

        var bestStartIdx = 0
        var bestStartDist = Double.MAX_VALUE
        var bestEndIdx = points.size - 1
        var bestEndDist = Double.MAX_VALUE

        for (i in points.indices) {
            val p = points[i]
            val dStart = (p.latitude - bLat) * (p.latitude - bLat) + (p.longitude - bLng) * (p.longitude - bLng)
            if (dStart < bestStartDist) {
                bestStartDist = dStart
                bestStartIdx = i
            }
            val dEnd = (p.latitude - aLat) * (p.latitude - aLat) + (p.longitude - aLng) * (p.longitude - aLng)
            if (dEnd < bestEndDist) {
                bestEndDist = dEnd
                bestEndIdx = i
            }
        }

        return if (bestStartIdx <= bestEndIdx) {
            points.subList(bestStartIdx, bestEndIdx + 1)
        } else {
            points.subList(bestEndIdx, bestStartIdx + 1).reversed()
        }
    }

    private fun loadPlanDetailData(plan: RoutePlan) {
        viewModelScope.launch {
            val firstBusLeg = plan.legs.firstOrNull { it.kind == "bus" }
            val code = firstBusLeg?.routeCode
            val boardStop = firstBusLeg?.boardStop
            val bLat = boardStop?.latitude?.toDoubleOrNull()
            val bLng = boardStop?.longitude?.toDoubleOrNull()

            // 1. Canlı araç eşleme
            var liveBus: BusRealtimeLocation? = null
            if (!code.isNullOrBlank() && bLat != null && bLng != null) {
                try {
                    val buses = repository.getRealtimeBuses(code)
                    val matching = if (!firstBusLeg.direction.isNullOrBlank()) {
                        buses.filter { it.istikamet == firstBusLeg.direction }
                    } else buses

                    liveBus = matching.minByOrNull { bus ->
                        val la = bus.enlem ?: 0.0
                        val lo = bus.boylam ?: 0.0
                        TransitRepository.calculateDistanceMeters(bLat, bLng, la, lo)
                    }
                } catch (_: Exception) {}
            }

            if (!code.isNullOrBlank()) {
                try {
                    val price = repository.getRoutePriceCached(code)
                    _uiState.update { it.copy(routePrice = price) }
                } catch (_: Exception) {}
            }

            // 2. Sonraki kalkışlar (dakika listesi)
            val upcomingRuns = mutableListOf<String>()
            val cal = java.util.Calendar.getInstance()
            val nowMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val weekDay = getBurulasWeekDay()

            if (boardStop?.stopId != null && !code.isNullOrBlank()) {
                val normCode = normalizeRouteCode(code)
                val schedule = scheduleCache?.getSchedule(boardStop.stopId, weekDay)
                    ?: repository.getStopSchedule(boardStop.stopId, weekDay)
                val routeSchedule = schedule.filter { normalizeRouteCode(it.routeCode ?: "") == normCode }

                val futureRuns = routeSchedule.mapNotNull { item ->
                    val timeStr = item.stopTime ?: return@mapNotNull null
                    val parts = timeStr.split(":")
                    val h = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val m = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    val totalMin = h * 60 + m
                    totalMin to timeStr
                }.filter { it.first >= nowMinutes }
                    .sortedBy { it.first }

                for ((runMin, _) in futureRuns.take(4)) {
                    val diff = runMin - nowMinutes
                    upcomingRuns.add("$diff dk")
                }
            }

            // 3. Hedefe tahmini varış saati (HH:mm)
            var waitMinutes = 5
            val stopKey = "${boardStop?.stopId}:${code}"
            val etaStr = _uiState.value.planEtas[stopKey]
            if (etaStr != null) {
                val digits = etaStr.filter { it.isDigit() }.toIntOrNull()
                if (digits != null) waitMinutes = digits
            } else if (upcomingRuns.isNotEmpty()) {
                val firstUpcoming = upcomingRuns.first().filter { it.isDigit() }.toIntOrNull()
                if (firstUpcoming != null) waitMinutes = firstUpcoming
            }

            val totalDurationMin = waitMinutes + plan.estimatedDurationMinutes
            val arrivalCal = java.util.Calendar.getInstance()
            arrivalCal.add(java.util.Calendar.MINUTE, totalDurationMin)
            val arrivalH = String.format(java.util.Locale.ROOT, "%02d", arrivalCal.get(java.util.Calendar.HOUR_OF_DAY))
            val arrivalM = String.format(java.util.Locale.ROOT, "%02d", arrivalCal.get(java.util.Calendar.MINUTE))
            val arrivalText = "Hedefe Tahmini Varış: $arrivalH:$arrivalM"

            _uiState.update { it.copy(
                selectedPlanLiveBus = liveBus,
                selectedPlanUpcomingRuns = upcomingRuns,
                selectedPlanEstimatedArrival = arrivalText
            ) }
        }
    }

    /** Seçili planı canlı yolculuk yöneticisi (JourneyService) için aşamalandırılmış JSON payload'a çevirir. */
    fun buildJourneyPayloadJson(): String? {
        val plan = _uiState.value.selectedPlan ?: return null
        val dest = _uiState.value.tripDestination ?: return null
        val walkShapes = _uiState.value.planWalkLegShapes.mapValues { (_, pts) ->
            pts.map { com.taha.goit.service.GeoPoint(it.latitude, it.longitude) }
        }
        return runCatching {
            val payload = JourneyPayloadFactory.build(
                plan = plan,
                destName = dest.name,
                destLat = dest.lat,
                destLng = dest.lng,
                walkShapes = walkShapes
            )
            Json.encodeToString(payload)
        }.onFailure {
            android.util.Log.w("MainViewModel", "JourneyPayload üretilemedi", it)
        }.getOrNull()
    }

    fun clearTrip() {
        planEtaJob?.cancel()
        _uiState.update { it.copy(
            tripPlans = emptyList(),
            tripDestination = null,
            selectedPlan = null,
            planEtas = emptyMap(),
            isTripPlanning = false,
            errorMessage = null
        ) }
    }

    /** Seçili durağı yolculuk kalkış noktası yapar (Yol Tarifi sekmesine geçilir). */
    fun startTripFromSelectedStop() {
        val stop = _uiState.value.selectedStop ?: return
        val id = stop.stopId ?: return
        val ls = StationDatabase.findById(id)
        val la = ls?.la ?: stop.latitude?.toDoubleOrNull() ?: return
        val lo = ls?.lo ?: stop.longitude?.toDoubleOrNull() ?: return
        clearStop()
        _uiState.update { it.copy(
            tripOrigin = TripPoint(stop.stopName ?: ls?.n ?: "Durak", la, lo),
            tripDestination = null,
            tripPlans = emptyList(),
            selectedPlan = null,
            errorMessage = null,
            navigateToTab = 0
        ) }
    }

    /** Sekme yönlendirme olayını temizler. */
    fun consumeNavigate() {
        _uiState.update { it.copy(navigateToTab = null) }
    }

    // ---------- Hat Seçimi ve Canlı Takip ----------

    fun selectRoute(routeCode: String, hatNo: Int?) {
        val resolvedHatNo = hatNo ?: RouteCatalog.findByCode(routeCode)?.hatNo
        _uiState.update { it.copy(
            selectedRouteCode = routeCode,
            selectedRouteHatNo = resolvedHatNo,
            selectedStop = null,
            selectedBus = null
        ) }
        historyManager?.add(routeCode)
        refreshRecent()

        resolvedHatNo?.let { no ->
            viewModelScope.launch {
                val stops = repository.getRouteStops(no)
                val dirs = stops.mapNotNull { it.direction }.distinct().sorted()
                val defaultDir = when {
                    dirs.contains("G") -> "G"
                    dirs.isNotEmpty() -> dirs.first()
                    else -> "G"
                }
                _uiState.update { it.copy(
                    selectedRouteStops = stops,
                    selectedRouteDirections = dirs,
                    selectedDirection = defaultDir
                ) }
            }
            viewModelScope.launch {
                val coords = repository.getRouteCoordinates(no.toString())
                _uiState.update { it.copy(selectedRouteCoordinates = coords) }
            }
            viewModelScope.launch {
                val price = repository.getRoutePriceCached(routeCode)
                _uiState.update { it.copy(routePrice = price) }
            }
        }
        startRealtimeTracking(routeCode)
    }

    /** Hat koduyla hat açar (Hatlar sekmesi). */
    fun selectRouteByCode(code: String) {
        val entry = RouteCatalog.findByCode(code)
        selectRoute(entry?.kod ?: code, entry?.hatNo)
    }

    fun setDirection(direction: String) {
        _uiState.update { it.copy(selectedDirection = direction) }
    }

    /** Kalkış noktasını konumun en yakın durağına geri alır. */
    fun resetOriginToNearest() {
        val lat = _uiState.value.userLat
        val lng = _uiState.value.userLng
        if (lat != null && lng != null) {
            _uiState.update { it.copy(
                tripOrigin = TripPoint("Konumum", lat, lng, isUserLocation = true)
            ) }
        }
    }

    /** Kalkış ve varışı yer değiştirir. */
    fun swapTripPoints() {
        val s = _uiState.value
        if (s.tripOrigin != null && s.tripDestination != null) {
            _uiState.update { it.copy(
                tripOrigin = s.tripDestination,
                tripDestination = s.tripOrigin,
                tripPlans = emptyList(),
                selectedPlan = null,
                errorMessage = null
            ) }
        }
    }

    fun clearRoute() {
        trackingJob?.cancel()
        _uiState.update { it.copy(
            selectedRouteCode = null,
            selectedRouteHatNo = null,
            selectedRouteStops = emptyList(),
            selectedRouteCoordinates = emptyList(),
            selectedRouteDirections = emptyList(),
            routePrice = emptyList(),
            realtimeBuses = emptyList(),
            isTrackingActive = false,
            selectedStop = null,
            selectedPlan = null
        ) }
    }

    fun selectBus(bus: BusRealtimeLocation) {
        val state = _uiState.value
        val distance = if (state.userLat != null && state.userLng != null && bus.enlem != null && bus.boylam != null) {
            TransitRepository.calculateDistanceMeters(state.userLat, state.userLng, bus.enlem, bus.boylam).toInt()
        } else null
        _uiState.update { it.copy(selectedBus = bus, selectedBusDistance = distance) }
    }

    fun clearBus() {
        _uiState.update { it.copy(selectedBus = null, selectedBusDistance = null) }
    }

    private fun startRealtimeTracking(routeCode: String) {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            _uiState.update { it.copy(isTrackingActive = true) }
            while (true) {
                val buses = repository.getRealtimeBuses(routeCode)
                _uiState.update { it.copy(realtimeBuses = buses) }
                delay(8000)
            }
        }
    }

    private fun startPlanEtaLoop() {
        planEtaJob?.cancel()
        planEtaJob = viewModelScope.launch {
            while (isActive) {
                val plans = _uiState.value.tripPlans
                if (plans.isEmpty()) break
                val pairs = mutableListOf<Pair<Int, String>>()
                for (plan in plans) {
                    if (plan.isWalkOnly) continue
                    val firstBusLeg = plan.legs.firstOrNull { it.kind == "bus" } ?: continue
                    val stopId = firstBusLeg.boardStop?.stopId ?: continue
                    val code = firstBusLeg.routeCode ?: continue
                    pairs.add(stopId to code)
                }
                val uniquePairs = pairs.distinct()
                if (uniquePairs.isEmpty()) {
                    delay(30_000L)
                    continue
                }
                val stopIds = uniquePairs.map { it.first }.distinct()

                val etasByStop = mutableMapOf<Int, List<StopRemainingTime>>()
                val semaphore = Semaphore(3)
                coroutineScope {
                    stopIds.forEach { sId ->
                        launch {
                            semaphore.withPermit {
                                val list = repository.getStationRemainingTime(sId)
                                synchronized(etasByStop) {
                                    etasByStop[sId] = list
                                }
                            }
                        }
                    }
                }

                val newMap = mutableMapOf<String, String>()
                val cal = java.util.Calendar.getInstance()
                val currentWeekDay = getBurulasWeekDay(cal)
                val nowMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)

                for ((sId, code) in uniquePairs) {
                    val normCode = normalizeRouteCode(code)
                    val list = etasByStop[sId] ?: emptyList()
                    val match = list.firstOrNull { normalizeRouteCode(it.routeCode ?: "") == normCode }
                    val raw = match?.realTime ?: match?.passTime
                    var etaStr: String? = null
                    if (!raw.isNullOrBlank()) {
                        etaStr = formatEtaTime(raw)
                    }

                    if (etaStr != null && etaStr != "-") {
                        newMap["$sId:$code"] = "~$etaStr"
                    } else {
                        // Canlı GPS yoksa veya araç henüz hatta çıkmadıysa:
                        // 1) Önce offline ScheduleCache paketine bak
                        // 2) Yoksa tek-durak API isteğine düş
                        try {
                            val cachedSchedule = scheduleCache?.getSchedule(sId, currentWeekDay)
                            val schedule = if (!cachedSchedule.isNullOrEmpty()) {
                                cachedSchedule
                            } else {
                                repository.getStopSchedule(sId, currentWeekDay)
                            }
                            val routeSchedule = schedule.filter { normalizeRouteCode(it.routeCode ?: "") == normCode }
                            // stopTime formatı "HH:mm" veya "HH:mm:ss"
                            val futureRuns = routeSchedule.mapNotNull { item ->
                                val timeStr = item.stopTime ?: return@mapNotNull null
                                val parts = timeStr.split(":")
                                val h = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                                val m = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                                val totalMin = h * 60 + m
                                totalMin to timeStr
                            }.filter { it.first >= nowMinutes }
                                .sortedBy { it.first }

                            val nextRun = futureRuns.firstOrNull()
                            if (nextRun != null) {
                                val diff = nextRun.first - nowMinutes
                                newMap["$sId:$code"] = if (diff in 0..120) "~$diff dk (Planlı)" else "Planlı ${nextRun.second.take(5)}"
                            } else {
                                // Gün içindeki son sefer bitmiş olabilir, ilk sabah seferine bak
                                val firstRun = routeSchedule.mapNotNull { it.stopTime }.sorted().firstOrNull()
                                if (firstRun != null) {
                                    newMap["$sId:$code"] = "İlk: ${firstRun.take(5)}"
                                } else {
                                    newMap["$sId:$code"] = "Planlı"
                                }
                            }
                        } catch (_: Exception) {
                            newMap["$sId:$code"] = "Bekleniyor"
                        }
                    }
                }
                _uiState.update { it.copy(planEtas = newMap) }
                delay(30_000L)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        trackingJob?.cancel()
        etaJob?.cancel()
        searchJob?.cancel()
        nearbyJob?.cancel()
        nearestEtaLoopJob?.cancel()
        planEtaJob?.cancel()
    }
}
