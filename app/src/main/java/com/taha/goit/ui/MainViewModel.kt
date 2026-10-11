package com.taha.goit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taha.goit.data.api.NominatimGeocoder
import com.taha.goit.data.api.TransitRepository
import com.taha.goit.data.local.FavoriteItem
import com.taha.goit.data.local.FavoritesManager
import com.taha.goit.data.local.RouteCatalog
import com.taha.goit.data.local.RouteHistoryManager
import com.taha.goit.data.local.JourneyHistoryEntry
import com.taha.goit.data.local.JourneyHistoryManager
import com.taha.goit.data.local.SavedPlace
import com.taha.goit.data.local.SavedPlacesManager
import com.taha.goit.data.local.StationDatabase
import com.taha.goit.data.model.*
import com.taha.goit.util.MapsLinkParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val journeyHistory: List<JourneyHistoryEntry> = emptyList()
)

class MainViewModel : ViewModel() {
    private val repository = TransitRepository()
    private val geocoder = NominatimGeocoder()

    private var favoritesManager: FavoritesManager? = null
    private var historyManager: RouteHistoryManager? = null
    private var savedPlacesManager: SavedPlacesManager? = null
    private var journeyHistoryManager: JourneyHistoryManager? = null

    private val _uiState = MutableStateFlow(MainUiState())
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
        _uiState.value = _uiState.value.copy(
            favorites = favorites.getFavorites(),
            recentRoutes = history.getRecent()
        )
    }

    fun setPlacesManager(manager: SavedPlacesManager) {
        savedPlacesManager = manager
        _uiState.value = _uiState.value.copy(savedPlaces = manager.getAll())
    }

    fun setJourneyHistoryManager(manager: JourneyHistoryManager) {
        journeyHistoryManager = manager
        _uiState.value = _uiState.value.copy(journeyHistory = manager.getHistory())
    }

    fun refreshSavedPlaces() {
        val manager = savedPlacesManager ?: return
        _uiState.value = _uiState.value.copy(savedPlaces = manager.getAll())
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

        _uiState.value = _uiState.value.copy(
            tripOrigin = origin,
            tripDestination = TripPoint(place.name, place.lat, place.lng),
            tripSearchField = null,
            tripSearchQuery = "",
            tripSearchResults = emptyList(),
            tripPlans = emptyList(),
            selectedPlan = null,
            errorMessage = null
        )
        if (origin != null) {
            planTrip()
        }
    }

    fun selectSavedPlaceAsOrigin(place: SavedPlace) {
        _uiState.value = _uiState.value.copy(
            tripOrigin = TripPoint(place.name, place.lat, place.lng),
            tripSearchField = null,
            tripSearchQuery = "",
            tripSearchResults = emptyList(),
            tripPlans = emptyList(),
            selectedPlan = null,
            errorMessage = null
        )
    }

    fun repeatJourney(entry: JourneyHistoryEntry) {
        _uiState.value = _uiState.value.copy(
            tripOrigin = TripPoint(entry.originName, entry.originLat, entry.originLng),
            tripDestination = TripPoint(entry.destName, entry.destLat, entry.destLng),
            tripSearchField = null,
            tripSearchQuery = "",
            tripSearchResults = emptyList(),
            tripPlans = emptyList(),
            selectedPlan = null,
            errorMessage = null
        )
        planTrip()
    }

    /** Gömülü veritabanları yüklendikten sonra çağrılır. */
    fun onLocalDataReady() {
        _uiState.value = _uiState.value.copy(allRoutes = RouteCatalog.all())
    }

    fun refreshFavorites() {
        val manager = favoritesManager ?: return
        _uiState.value = _uiState.value.copy(favorites = manager.getFavorites())
    }

    private fun refreshRecent() {
        _uiState.value = _uiState.value.copy(recentRoutes = historyManager?.getRecent() ?: emptyList())
    }

    // ---------- Yakındaki Duraklar ----------

    fun loadNearby(lat: Double, lng: Double) {
        val nearest = StationDatabase.nearestStations(lat, lng, 15)
        val prev = _uiState.value.nearbyStops.associateBy { it.station.i }
        val base = nearest.map { (s, d) ->
            NearbyStopUi(s, d, prev[s.i]?.buses ?: emptyList())
        }
        val firstStation = nearest.firstOrNull()?.first
        _uiState.value = _uiState.value.copy(
            userLat = lat,
            userLng = lng,
            locationAvailable = true,
            nearbyStops = base,
            isLoadingNearbyEtas = nearest.isNotEmpty(),
            nearestStopName = firstStation?.n,
            tripOrigin = _uiState.value.tripOrigin
                ?: TripPoint("Konumum", lat, lng, isUserLocation = true)
        )
        if (nearest.isNotEmpty()) {
            loadNearbyEtas(nearest)
            startNearestStopEtaLoop(nearest.first().first.i, nearest.first().first.n)
        }
    }

    fun markLocationMissing() {
        _uiState.value = _uiState.value.copy(locationAvailable = false, isLoadingNearbyEtas = false)
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
                    _uiState.value = _uiState.value.copy(
                        nearestStopEtas = sorted,
                        nearestStopName = stopName
                    )
                } catch (_: Exception) {
                }
                delay(15000)
            }
        }
    }

    fun toggleShowAllRoutes() {
        _uiState.value = _uiState.value.copy(showAllRoutes = !_uiState.value.showAllRoutes)
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
            _uiState.value = _uiState.value.copy(
                nearbyStops = _uiState.value.nearbyStops.map { ui ->
                    val rows = rowsByStop[ui.station.i]
                    if (rows != null) ui.copy(buses = rows) else ui
                },
                isLoadingNearbyEtas = false
            )
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
        _uiState.value = _uiState.value.copy(selectedStop = stop, stopLines = emptyList(), stopSchedule = emptyList(), showSchedule = false)
        val stopId = stop.stopId ?: return
        etaJob?.cancel()
        etaJob = viewModelScope.launch {
            while (true) {
                val eta = repository.getStationRemainingTime(stopId)
                _uiState.value = _uiState.value.copy(stopEtaList = eta)
                delay(10000)
            }
        }
        viewModelScope.launch {
            val lines = repository.getRoutesByStop(stopId)
            _uiState.value = _uiState.value.copy(stopLines = lines)
        }
    }

    fun clearStop() {
        etaJob?.cancel()
        _uiState.value = _uiState.value.copy(
            selectedStop = null,
            stopEtaList = emptyList(),
            stopSchedule = emptyList(),
            showSchedule = false,
            stopLines = emptyList()
        )
    }

    /** Durak tarifesini aç/kapat. */
    fun toggleSchedule() {
        val state = _uiState.value
        if (state.showSchedule) {
            _uiState.value = state.copy(showSchedule = false)
            return
        }
        val stopId = state.selectedStop?.stopId ?: return
        val cal = java.util.Calendar.getInstance()
        val calDow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        // API günleri: Pazartesi=1 ... Pazar=7 (Calendar: Pazar=1, Pazartesi=2 ... Cumartesi=7)
        val weekDay = when (calDow) {
            java.util.Calendar.SUNDAY -> 7
            else -> calDow - 1
        }
        _uiState.value = state.copy(showSchedule = true, stopSchedule = emptyList())
        viewModelScope.launch {
            val schedule = repository.getStopSchedule(stopId, weekDay)
            _uiState.value = _uiState.value.copy(stopSchedule = schedule)
        }
    }

    // ---------- Yol Tarifi (A'dan B'ye) ----------

    fun openTripSearch(field: String) {
        _uiState.value = _uiState.value.copy(
            tripSearchField = field,
            tripSearchQuery = "",
            tripSearchResults = emptyList()
        )
    }

    fun cancelTripSearch() {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            tripSearchField = null,
            tripSearchQuery = "",
            tripSearchResults = emptyList(),
            isTripSearching = false
        )
    }

    fun onTripSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(tripSearchQuery = query)
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _uiState.value = _uiState.value.copy(tripSearchResults = emptyList(), isTripSearching = false)
            return
        }
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTripSearching = true)
            val q = query.trim()

            // 1. Google Maps linki veya koordinat (40.1832, 29.0647) kontrolü
            val parsedLocation = MapsLinkParser.parse(q)
            if (parsedLocation != null) {
                val direct = PlaceResult(
                    name = parsedLocation.label ?: "Harita Konumu",
                    kind = "G",
                    stopId = null,
                    lat = parsedLocation.lat,
                    lng = parsedLocation.lng,
                    subtitle = String.format(java.util.Locale.US, "%.5f, %.5f", parsedLocation.lat, parsedLocation.lng)
                )
                _uiState.value = _uiState.value.copy(
                    tripSearchResults = listOf(direct),
                    isTripSearching = false
                )
                return@launch
            }

            delay(250)
            val local = StationDatabase.search(q, 30).map {
                PlaceResult(
                    name = it.n,
                    kind = if (it.t == 1) "S" else "P",
                    stopId = if (it.t == 1) it.i else null,
                    lat = it.la,
                    lng = it.lo,
                    subtitle = if (it.t == 1) "Durak" else "Mekan"
                )
            }
            var merged: List<PlaceResult> = local
            if (local.size < 8) {
                val osm = geocoder.searchPlaces(q, 6).map { o ->
                    PlaceResult(
                        name = o.name ?: o.displayName?.substringBefore(", ") ?: q,
                        kind = "O",
                        stopId = null,
                        lat = o.lat?.toDoubleOrNull(),
                        lng = o.lon?.toDoubleOrNull(),
                        subtitle = "OpenStreetMap"
                    )
                }
                merged = local + osm
            }
            _uiState.value = _uiState.value.copy(tripSearchResults = merged, isTripSearching = false)
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
                _uiState.value = state.copy(
                    tripOrigin = point,
                    tripSearchField = null,
                    tripSearchQuery = "",
                    tripSearchResults = emptyList(),
                    tripPlans = emptyList(),
                    selectedPlan = null,
                    errorMessage = null
                )
            }
            "dest" -> {
                _uiState.value = state.copy(
                    tripDestination = point,
                    tripSearchField = null,
                    tripSearchQuery = "",
                    tripSearchResults = emptyList(),
                    tripPlans = emptyList(),
                    selectedPlan = null,
                    errorMessage = null
                )
                // v1.2.0: Varış seçildiğinde otomatik planlama YAPILMAZ; "Rota Bul" butonuna basılması beklenir.
            }
        }
    }

    /** "Konumumu kullan" butonu: kalkış noktasını mevcut GPS koordinatına ayarlar. */
    fun setOriginToCurrentLocation() {
        val lat = _uiState.value.userLat
        val lng = _uiState.value.userLng
        if (lat != null && lng != null) {
            _uiState.value = _uiState.value.copy(
                tripOrigin = TripPoint("Konumum", lat, lng, isUserLocation = true),
                tripSearchField = null,
                tripSearchQuery = "",
                tripSearchResults = emptyList(),
                tripPlans = emptyList(),
                selectedPlan = null,
                errorMessage = null
            )
        }
    }

    /** Dışarıdan (Intent / Google Maps linki) gelen hedef konumu doğrudan varış noktası yapar. */
    fun setDestinationFromExternal(lat: Double, lng: Double, label: String?) {
        _uiState.value = _uiState.value.copy(
            tripDestination = TripPoint(label ?: "Google Haritalar", lat, lng),
            tripSearchField = null,
            tripSearchQuery = "",
            tripSearchResults = emptyList(),
            tripPlans = emptyList(),
            selectedPlan = null,
            errorMessage = null,
            navigateToTab = 0
        )
    }

    fun planTrip() {
        val o = _uiState.value.tripOrigin ?: return
        val d = _uiState.value.tripDestination ?: return

        val distance = TransitRepository.calculateDistanceMeters(o.lat, o.lng, d.lat, d.lng)
        if (distance < 30.0) {
            _uiState.value = _uiState.value.copy(
                tripPlans = emptyList(),
                errorMessage = "Kalkış ve varış aynı nokta. Farklı bir varış seçin."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTripPlanning = true, selectedPlan = null, errorMessage = null)
            val transitPlans = repository.planTrip(o.lat, o.lng, d.lat, d.lng)

            // "Sadece yürü" alternatifi (≤2 km)
            val finalPlans = if (distance <= 2000.0) {
                val walkPlan = repository.walkOnlyPlan(o.lat, o.lng, d.lat, d.lng)
                listOf(walkPlan) + transitPlans
            } else {
                transitPlans
            }

            _uiState.value = _uiState.value.copy(
                tripPlans = finalPlans,
                isTripPlanning = false,
                errorMessage = if (finalPlans.isEmpty()) "Bu iki nokta arasında uygun rota bulunamadı." else null
            )
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
                    _uiState.value = _uiState.value.copy(journeyHistory = mgr.getHistory())
                }
                startPlanEtaLoop()
            } else {
                planEtaJob?.cancel()
            }
        }
    }

    /** Plan seçildiğinde: sadece seçilen plan haritada çizilir, güzergah koordinatları getirilir. */
    fun selectRouteFromPlan(plan: RoutePlan) {
        _uiState.value = _uiState.value.copy(
            selectedPlan = plan,
            selectedRouteCode = null,
            selectedStop = null
        )
    }

    fun clearTrip() {
        planEtaJob?.cancel()
        _uiState.value = _uiState.value.copy(
            tripPlans = emptyList(),
            tripDestination = null,
            selectedPlan = null,
            planEtas = emptyMap(),
            isTripPlanning = false,
            errorMessage = null
        )
    }

    /** Seçili durağı yolculuk kalkış noktası yapar (Yol Tarifi sekmesine geçilir). */
    fun startTripFromSelectedStop() {
        val stop = _uiState.value.selectedStop ?: return
        val id = stop.stopId ?: return
        val ls = StationDatabase.findById(id)
        val la = ls?.la ?: stop.latitude?.toDoubleOrNull() ?: return
        val lo = ls?.lo ?: stop.longitude?.toDoubleOrNull() ?: return
        clearStop()
        _uiState.value = _uiState.value.copy(
            tripOrigin = TripPoint(stop.stopName ?: ls?.n ?: "Durak", la, lo),
            tripDestination = null,
            tripPlans = emptyList(),
            selectedPlan = null,
            errorMessage = null,
            navigateToTab = 0
        )
    }

    /** Sekme yönlendirme olayını temizler. */
    fun consumeNavigate() {
        _uiState.value = _uiState.value.copy(navigateToTab = null)
    }

    // ---------- Hat Seçimi ve Canlı Takip ----------

    fun selectRoute(routeCode: String, hatNo: Int?) {
        val resolvedHatNo = hatNo ?: RouteCatalog.findByCode(routeCode)?.hatNo
        _uiState.value = _uiState.value.copy(
            selectedRouteCode = routeCode,
            selectedRouteHatNo = resolvedHatNo,
            selectedStop = null,
            selectedBus = null
        )
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
                _uiState.value = _uiState.value.copy(
                    selectedRouteStops = stops,
                    selectedRouteDirections = dirs,
                    selectedDirection = defaultDir
                )
            }
            viewModelScope.launch {
                val coords = repository.getRouteCoordinates(no.toString())
                _uiState.value = _uiState.value.copy(selectedRouteCoordinates = coords)
            }
            viewModelScope.launch {
                val price = repository.getRoutePriceCached(routeCode)
                _uiState.value = _uiState.value.copy(routePrice = price)
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
        _uiState.value = _uiState.value.copy(selectedDirection = direction)
    }

    /** Kalkış noktasını konumun en yakın durağına geri alır. */
    fun resetOriginToNearest() {
        val lat = _uiState.value.userLat
        val lng = _uiState.value.userLng
        if (lat != null && lng != null) {
            _uiState.value = _uiState.value.copy(
                tripOrigin = TripPoint("Konumum", lat, lng, isUserLocation = true)
            )
        }
    }

    /** Kalkış ve varışı yer değiştirir. */
    fun swapTripPoints() {
        val s = _uiState.value
        if (s.tripOrigin != null && s.tripDestination != null) {
            _uiState.value = s.copy(
                tripOrigin = s.tripDestination,
                tripDestination = s.tripOrigin,
                tripPlans = emptyList(),
                selectedPlan = null,
                errorMessage = null
            )
        }
    }

    fun clearRoute() {
        trackingJob?.cancel()
        _uiState.value = _uiState.value.copy(
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
        )
    }

    fun selectBus(bus: BusRealtimeLocation) {
        val state = _uiState.value
        val distance = if (state.userLat != null && state.userLng != null && bus.enlem != null && bus.boylam != null) {
            TransitRepository.calculateDistanceMeters(state.userLat, state.userLng, bus.enlem, bus.boylam).toInt()
        } else null
        _uiState.value = state.copy(selectedBus = bus, selectedBusDistance = distance)
    }

    fun clearBus() {
        _uiState.value = _uiState.value.copy(selectedBus = null, selectedBusDistance = null)
    }

    private fun startRealtimeTracking(routeCode: String) {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTrackingActive = true)
            while (true) {
                val buses = repository.getRealtimeBuses(routeCode)
                _uiState.value = _uiState.value.copy(realtimeBuses = buses)
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
                for ((sId, code) in uniquePairs) {
                    val list = etasByStop[sId] ?: emptyList()
                    val match = list.firstOrNull { it.routeCode.equals(code, ignoreCase = true) }
                    val raw = match?.realTime ?: match?.passTime
                    val etaStr = if (!raw.isNullOrBlank()) {
                        formatEtaTime(raw)
                    } else null
                    newMap["$sId:$code"] = if (etaStr != null && etaStr != "-") "~$etaStr" else "–"
                }
                _uiState.value = _uiState.value.copy(planEtas = newMap)
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
