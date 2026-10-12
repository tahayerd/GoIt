package com.taha.goit.ui

import android.content.Context
import android.widget.Toast
import com.taha.goit.service.JourneyService
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import com.taha.goit.GoItApp
import com.taha.goit.data.api.TransitRepository
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taha.goit.data.local.FavoriteItem
import com.taha.goit.data.local.FavoritesManager
import com.taha.goit.data.local.JourneyHistoryEntry
import com.taha.goit.data.local.RouteCatalog
import com.taha.goit.data.local.RouteHistoryManager
import com.taha.goit.data.local.SavedPlace
import com.taha.goit.data.local.StationDatabase
import com.taha.goit.data.model.RoutePlan
import com.taha.goit.data.model.RouteStop
import com.taha.goit.location.LocationHelper
import com.taha.goit.ui.map.MapLayers
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val STYLE_LIGHT = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"
private const val STYLE_DARK = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
private const val NEAR_STOP_METERS = 150.0

private fun Modifier.panelDragGestures(
    isCollapsed: Boolean,
    dragDownToCollapse: Boolean,
    onToggleCollapse: () -> Unit,
    onSetCollapse: ((Boolean) -> Unit)? = null
): Modifier = this.pointerInput(isCollapsed) {
    var accumulatedDragY = 0f
    detectVerticalDragGestures(
        onDragStart = {
            accumulatedDragY = 0f
        },
        onDragEnd = {
            val threshold = 36.dp.toPx()
            if (dragDownToCollapse) {
                // Alt panel: aşağı sürüklenince kapanır, yukarı sürüklenince açılır
                if (accumulatedDragY > threshold) {
                    if (onSetCollapse != null) onSetCollapse(true) else if (!isCollapsed) onToggleCollapse()
                } else if (accumulatedDragY < -threshold) {
                    if (onSetCollapse != null) onSetCollapse(false) else if (isCollapsed) onToggleCollapse()
                }
            } else {
                // Üst kart: yukarı sürüklenince kapanır, aşağı sürüklenince açılır
                if (accumulatedDragY < -threshold) {
                    if (onSetCollapse != null) onSetCollapse(true) else if (!isCollapsed) onToggleCollapse()
                } else if (accumulatedDragY > threshold) {
                    if (onSetCollapse != null) onSetCollapse(false) else if (isCollapsed) onToggleCollapse()
                }
            }
            accumulatedDragY = 0f
        },
        onDragCancel = {
            accumulatedDragY = 0f
        },
        onVerticalDrag = { change, dragAmount ->
            change.consume()
            accumulatedDragY += dragAmount
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    favoritesManager: FavoritesManager,
    historyManager: RouteHistoryManager,
    onRequestLocationPermission: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onStartJourney: (Double, Double, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (uiState.themeMode) {
        "amoled", "dark" -> true
        "light" -> false
        else -> isSystemDark
    }
    val styleUrl = if (isDark) STYLE_DARK else STYLE_LIGHT

    val prefs = remember { context.getSharedPreferences("goit_prefs", Context.MODE_PRIVATE) }
    var showOnboarding by rememberSaveable {
        mutableStateOf(!prefs.getBoolean("onboarding_done_v1", false))
    }

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var mapLayers by remember { mutableStateOf<MapLayers?>(null) }
    var mapEpoch by remember { mutableIntStateOf(0) }
    var mapView by remember { mutableStateOf<MapView?>(null) }

    // MapView yaşam döngüsü (onStart/onStop) — ilk onStart burada garanti edilir,
    // sonraki yaşam döngüsü olaylarında tekrar çağrıyı önlemek için bayrak tutulur.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(mapView) {
        val mv = mapView
        if (mv == null) {
            onDispose { }
        } else {
            var started = true
            runCatching { mv.onStart() }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> if (!started) {
                        started = true
                        runCatching { mv.onStart() }
                    }
                    Lifecycle.Event.ON_STOP -> if (started) {
                        started = false
                        runCatching { mv.onStop() }
                    }
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // Yerel veritabanlarını yükle (tek seferlik) ve yöneticileri bağla
    LaunchedEffect(Unit) {
        StationDatabase.load(context)
        RouteCatalog.load(context)
        viewModel.setManagers(favoritesManager, historyManager)
        viewModel.onLocalDataReady()
        val last = LocationHelper.getLastKnownLocation(context)
        if (last != null) viewModel.loadNearby(last.latitude, last.longitude)
        val fresh = LocationHelper.getFreshLocation(context)
        if (fresh != null) {
            viewModel.loadNearby(fresh.latitude, fresh.longitude)
        } else if (last == null) {
            viewModel.markLocationMissing()
        }
    }

    // Sekme yönlendirme olayı (ViewModel -> UI)
    LaunchedEffect(uiState.navigateToTab) {
        uiState.navigateToTab?.let { tab ->
            selectedTab = tab
            viewModel.consumeNavigate()
        }
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val tabAnim = remember { Animatable(selectedTab.toFloat()) }

    // Harita etkileşim ilerlemesi: 0f = kartlar açık, 1f = harita odakta (kartlar küçültülmüş)
    val mapInteractionProgress = remember { Animatable(0f) }
    var restoreJob by remember { mutableStateOf<Job?>(null) }

    val onMapGestureChanged: (Boolean) -> Unit = remember(scope) {
        { isInteracting ->
            if (isInteracting) {
                // Dokunulduğu / sürüklendiği an: bekleyen geri dönüşü iptal et ve anında küçült
                restoreJob?.cancel()
                restoreJob = null
                scope.launch {
                    mapInteractionProgress.animateTo(
                        1f,
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 800f)
                    )
                }
            } else {
                // Sürükleme bittiği an: 1 saniyelik toplam zamanlayıcı
                // İlk 0.5 sn (500 ms) küçülmüş kalır, 0.5. sn'den itibaren 500 ms boyunca pürüzsüzce tam açılır
                restoreJob?.cancel()
                restoreJob = scope.launch {
                    delay(500L)
                    mapInteractionProgress.animateTo(
                        0f,
                        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
                    )
                }
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (abs(tabAnim.targetValue - selectedTab.toFloat()) > 0.01f) {
            tabAnim.animateTo(
                selectedTab.toFloat(),
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f)
            )
        }
    }

    fun Modifier.swipeableTabs(): Modifier = this.pointerInput(Unit) {
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onDragEnd = {
                val current = tabAnim.value
                val target = when {
                    totalDrag < -screenWidthPx * 0.18f && selectedTab < 2 -> selectedTab + 1
                    totalDrag > screenWidthPx * 0.18f && selectedTab > 0 -> selectedTab - 1
                    else -> kotlin.math.round(current).toInt().coerceIn(0, 2)
                }
                selectedTab = target
                scope.launch {
                    tabAnim.animateTo(
                        target.toFloat(),
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f)
                    )
                }
            },
            onDragCancel = {
                scope.launch {
                    tabAnim.animateTo(
                        selectedTab.toFloat(),
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f)
                    )
                }
            },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                totalDrag += dragAmount
                val newVal = (tabAnim.value - dragAmount / screenWidthPx).coerceIn(0f, 2f)
                scope.launch {
                    tabAnim.snapTo(newVal)
                }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar(modifier = Modifier.swipeableTabs()) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.AltRoute, null) },
                    label = { Text("Yol Tarifi") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Place, null) },
                    label = { Text("Duraklar") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.DirectionsBus, null) },
                    label = { Text("Hatlar") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Harita daima compose'da kalır (sekme değişiminde yeniden yüklenmez).
            key(styleUrl) {
                TripMap(
                    styleUrl = styleUrl,
                    onMapViewCreated = { mapView = it },
                    onLayersReady = {
                        mapLayers = it
                        mapEpoch++
                    },
                    onStopTapped = { stopId -> viewModel.selectNearbyStopById(stopId) },
                    onBusTapped = { plate -> viewModel.selectBusByPlate(plate) },
                    onMapGestureChanged = onMapGestureChanged
                )
            }

            // Sekme 0: Yol Tarifi (Harita arkada, kartlar önde)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = (0 - tabAnim.value) * screenWidthPx
                        alpha = if (tabAnim.value > 1.05f) 0f else 1f
                    }
            ) {
                TripTab(
                    uiState = uiState,
                    viewModel = viewModel,
                    favoritesManager = favoritesManager,
                    mapInteractionProgress = mapInteractionProgress.value,
                    onStartJourney = onStartJourney,
                    onOpenSettings = { showSettings = true },
                    modifier = Modifier
                )
            }

            // Sekme 1: Duraklar
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = (1 - tabAnim.value) * screenWidthPx
                        alpha = if (abs(tabAnim.value - 1f) > 1.05f) 0f else 1f
                    }
                    .background(MaterialTheme.colorScheme.background)
                    .swipeableTabs()
            ) {
                StopsTab(
                    uiState = uiState,
                    viewModel = viewModel,
                    favoritesManager = favoritesManager,
                    onStartJourney = onStartJourney,
                    onOpenSettings = { showSettings = true },
                    onLocate = {
                        scope.launch {
                            val loc = LocationHelper.getFreshLocation(context)
                            if (loc != null) viewModel.loadNearby(loc.latitude, loc.longitude)
                        }
                    }
                )
            }

            // Sekme 2: Hatlar
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = (2 - tabAnim.value) * screenWidthPx
                        alpha = if (tabAnim.value < 0.95f) 0f else 1f
                    }
                    .background(MaterialTheme.colorScheme.background)
                    .swipeableTabs()
            ) {
                RoutesTab(
                    uiState = uiState,
                    viewModel = viewModel,
                    favoritesManager = favoritesManager,
                    onStartJourney = onStartJourney,
                    onOpenSettings = { showSettings = true },
                    onStop = { viewModel.selectStop(it) }
                )
            }

            // Canlı Yolculuk HUD Kartı: Scaffold content sınırları içinde, alt sekmelerin üzerinde düzgünce yerleşir
            JourneyScreen(
                onStopJourney = {
                    JourneyService.stop(context)
                },
                mapInteractionProgress = mapInteractionProgress.value,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    val layers = mapLayers

    // Rota çizimi (plan seçili DEĞİLSE normal hat çizimi yapılır - Madde 7: dual-tone ve subtle stops)
    LaunchedEffect(
        uiState.selectedRouteCode, uiState.selectedDirection,
        uiState.selectedRouteStops, uiState.selectedRouteCoordinates,
        uiState.selectedPlan, mapEpoch
    ) {
        val l = layers ?: return@LaunchedEffect
        if (mapEpoch == 0) return@LaunchedEffect
        if (uiState.selectedPlan != null) {
            // Plan seçiliyse normal hat çizimi temizlenir (çakışma önlenir)
            l.clearRouteOnly()
        } else if (uiState.selectedRouteCode != null) {
            l.drawRouteDualTone(uiState.selectedRouteCoordinates, uiState.selectedDirection)
            l.drawStops(uiState.selectedRouteStops, uiState.selectedDirection, subtle = true)
        } else {
            l.clearRouteOnly()
        }
    }

    // Çok bacaklı plan çizimi (kesikli yürüme + mavi/turuncu otobüs + OSRM/snap shapes)
    LaunchedEffect(
        uiState.selectedPlan, uiState.tripOrigin, uiState.tripDestination,
        uiState.planBusLegShapes, uiState.planWalkLegShapes, mapEpoch
    ) {
        val l = layers ?: return@LaunchedEffect
        if (mapEpoch == 0) return@LaunchedEffect
        val plan = uiState.selectedPlan
        if (plan != null) {
            l.drawPlanLegs(
                uiState.tripOrigin?.lat, uiState.tripOrigin?.lng,
                uiState.tripDestination?.lat, uiState.tripDestination?.lng,
                plan,
                uiState.planBusLegShapes,
                uiState.planWalkLegShapes
            )
        } else {
            l.clearPlanLegs()
        }
    }

    // Canlı otobüsler
    LaunchedEffect(uiState.realtimeBuses, mapEpoch) {
        val l = layers ?: return@LaunchedEffect
        if (mapEpoch == 0) return@LaunchedEffect
        l.drawBuses(uiState.realtimeBuses)
    }

    // Kullanıcı konumu
    LaunchedEffect(uiState.userLat, uiState.userLng, mapEpoch) {
        val l = layers ?: return@LaunchedEffect
        val lat = uiState.userLat ?: return@LaunchedEffect
        val lng = uiState.userLng ?: return@LaunchedEffect
        if (mapEpoch == 0) return@LaunchedEffect
        l.drawUserLocation(lat, lng)
    }

    // Plan uç noktaları
    LaunchedEffect(uiState.tripOrigin, uiState.tripDestination, mapEpoch) {
        val l = layers ?: return@LaunchedEffect
        if (mapEpoch == 0) return@LaunchedEffect
        l.drawPlanEndpoints(
            uiState.tripOrigin?.lat, uiState.tripOrigin?.lng,
            uiState.tripDestination?.lat, uiState.tripDestination?.lng
        )
    }

    // Seçili plan varsa: canlı otobüs + biniş durağı veya uç noktalara sığdır
    LaunchedEffect(uiState.selectedPlan, uiState.selectedPlanLiveBus, mapEpoch) {
        val mv = mapView ?: return@LaunchedEffect
        val plan = uiState.selectedPlan ?: return@LaunchedEffect
        if (mapEpoch == 0) return@LaunchedEffect
        selectedTab = 0
        val pts = mutableListOf<LatLng>()
        val liveBus = uiState.selectedPlanLiveBus
        val firstBus = plan.legs.firstOrNull { it.kind == "bus" }
        val bStop = firstBus?.boardStop ?: plan.originStop
        val bLat = bStop.latitude?.toDoubleOrNull()
        val bLng = bStop.longitude?.toDoubleOrNull()

        if (liveBus?.enlem != null && liveBus.boylam != null && bLat != null && bLng != null) {
            pts.add(LatLng(liveBus.enlem, liveBus.boylam))
            pts.add(LatLng(bLat, bLng))
        } else {
            uiState.tripOrigin?.let { o -> pts.add(LatLng(o.lat, o.lng)) }
            uiState.tripDestination?.let { d -> pts.add(LatLng(d.lat, d.lng)) }
            plan.intermediateStops.forEach { s ->
                MapUtils.latLng(s.latitude, s.longitude)?.let { pts.add(it) }
            }
        }
        if (pts.isNotEmpty()) {
            mv.getMapAsync { map ->
                runCatching {
                    val builder = org.maplibre.android.geometry.LatLngBounds.Builder()
                    pts.forEach { builder.include(it) }
                    map.easeCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 96))
                }
            }
        }
    }

    // Hat seçilince (plan dışı): durak listesine sığdır
    LaunchedEffect(uiState.selectedRouteCode, uiState.selectedRouteStops, uiState.selectedPlan, mapEpoch) {
        val mv = mapView ?: return@LaunchedEffect
        if (mapEpoch == 0) return@LaunchedEffect
        if (uiState.selectedPlan != null) return@LaunchedEffect
        if (uiState.selectedRouteStops.isNotEmpty() && uiState.selectedRouteCode != null) {
            val pts = uiState.selectedRouteStops.mapNotNull { MapUtils.latLng(it.latitude, it.longitude) }
            if (pts.isNotEmpty()) {
                mv.getMapAsync { map ->
                    runCatching {
                        val builder = org.maplibre.android.geometry.LatLngBounds.Builder()
                        pts.forEach { builder.include(it) }
                        map.easeCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 64))
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsScreen(
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { showSettings = false }
        )
    }

    if (showOnboarding) {
        OnboardingScreen(
            onRequestLocationPermission = onRequestLocationPermission,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onComplete = {
                prefs.edit().putBoolean("onboarding_done_v1", true).apply()
                showOnboarding = false
            }
        )
    }
}

// =========================================================
//  YOL TARİFİ SEKMESİ (harita üzerine bindirilmiş kartlar)
// =========================================================

@Composable
private fun TripTab(
    uiState: MainUiState,
    viewModel: MainViewModel,
    favoritesManager: FavoritesManager,
    mapInteractionProgress: Float = 0f,
    onStartJourney: (Double, Double, String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val journeyActive by com.taha.goit.service.JourneyTracker.state.collectAsState()
    if (journeyActive != null) return

    var isTopCollapsed by rememberSaveable { mutableStateOf(false) }
    var isBottomCollapsed by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        TripSearchCard(
            uiState = uiState,
            viewModel = viewModel,
            isCollapsed = isTopCollapsed,
            mapInteractionProgress = mapInteractionProgress,
            onToggleCollapse = { isTopCollapsed = !isTopCollapsed },
            onSetCollapse = { isTopCollapsed = it },
            onOpenSettings = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(10.dp)
        )
        TripBottomPanel(
            uiState = uiState,
            viewModel = viewModel,
            favoritesManager = favoritesManager,
            isCollapsed = isBottomCollapsed,
            mapInteractionProgress = mapInteractionProgress,
            onToggleCollapse = { isBottomCollapsed = !isBottomCollapsed },
            onSetCollapse = { isBottomCollapsed = it },
            onStartJourney = onStartJourney,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun TripSearchCard(
    uiState: MainUiState,
    viewModel: MainViewModel,
    isCollapsed: Boolean = false,
    mapInteractionProgress: Float = 0f,
    onToggleCollapse: () -> Unit = {},
    onSetCollapse: (Boolean) -> Unit = {},
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var placeToSave by remember { mutableStateOf<PlaceResult?>(null) }
    var saveKind by remember { mutableStateOf("home") }
    var customName by remember { mutableStateOf("") }
    var placeToManage by remember { mutableStateOf<SavedPlace?>(null) }

    val targetScale = 1f - (0.12f * mapInteractionProgress)
    val targetAlpha = 1f - (0.65f * mapInteractionProgress)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(targetScale)
            .alpha(targetAlpha)
            .animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        if (isCollapsed && uiState.tripSearchField == null) {
            // Daraltılmış Kompakt Görünüm
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .panelDragGestures(
                        isCollapsed = true,
                        dragDownToCollapse = false,
                        onToggleCollapse = onToggleCollapse,
                        onSetCollapse = onSetCollapse
                    )
                    .clickable(onClick = onToggleCollapse)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Directions, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${uiState.tripOrigin?.name ?: "Kalkış"} → ${uiState.tripDestination?.name ?: "Varış"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(width = 24.dp, height = 3.dp)
                ) {}
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .panelDragGestures(
                        isCollapsed = false,
                        dragDownToCollapse = false,
                        onToggleCollapse = onToggleCollapse,
                        onSetCollapse = onSetCollapse
                    )
            ) {
            if (uiState.tripSearchField != null) {
                // ---- Arama modu ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.cancelTripSearch() }) {
                        Icon(Icons.Default.ArrowBack, "Geri")
                    }
                    Text(
                        if (uiState.tripSearchField == "origin") "Kalkış seç" else "Varış seç",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // Madde 8: Kompakt tek satır [MyLocation ikon butonu] + [arama alanı]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.userLat != null && uiState.userLng != null) {
                        IconButton(
                            onClick = {
                                if (uiState.tripSearchField == "origin") {
                                    viewModel.setOriginToCurrentLocation()
                                } else {
                                    viewModel.selectTripPlace(PlaceResult("Konumum", "P", null, uiState.userLat, uiState.userLng))
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.MyLocation, "Konumum", tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    OutlinedTextField(
                        value = uiState.tripSearchQuery,
                        onValueChange = { viewModel.onTripSearchQueryChange(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Durak, mekan, link...") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                    )
                }

                // Madde 8: Varış ekranında boş aramada Favori Konumlar + En Çok Gidilen
                if (uiState.tripSearchField == "dest" && uiState.tripSearchQuery.isEmpty()) {
                    if (uiState.savedPlaces.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Kayıtlı Yerler",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(uiState.savedPlaces) { place ->
                                SavedPlaceChip(
                                    place = place,
                                    onClick = { viewModel.selectSavedPlaceAsDestination(place) },
                                    onLongClick = { placeToManage = place }
                                )
                            }
                        }
                    }
                    if (uiState.mostVisitedPlaces.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "En Çok Gidilen",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(uiState.mostVisitedPlaces) { vp ->
                                SuggestionChip(
                                    onClick = {
                                        viewModel.selectTripPlace(PlaceResult(vp.name, "P", null, vp.lat, vp.lng))
                                    },
                                    label = { Text(vp.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    icon = { Icon(Icons.Default.TrendingUp, null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                }
                if (uiState.isTripSearching) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Aranıyor...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (uiState.tripSearchResults.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(uiState.tripSearchResults) { place ->
                            PlaceRow(
                                place = place,
                                onSelect = { viewModel.selectTripPlace(place) },
                                onSavePlace = {
                                    placeToSave = it
                                    saveKind = "home"
                                    customName = it.name
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                } else if (uiState.tripSearchQuery.length >= 2 && !uiState.isTripSearching) {
                    Text(
                        "Sonuç bulunamadı. Durak adı, mekan adı veya koordinat deneyin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                // ---- Kalkış / Varış özeti ----
                TripPointRow(
                    icon = Icons.Default.TripOrigin,
                    iconTint = Color(0xFF0F9D58),
                    label = "Kalkış",
                    value = uiState.tripOrigin?.name,
                    hint = "Kalkış noktası seçin",
                    onClick = { viewModel.openTripSearch("origin") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.userLat != null && uiState.userLng != null) {
                        TextButton(onClick = { viewModel.setOriginToCurrentLocation() }) {
                            Icon(Icons.Default.MyLocation, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Konumumu kullan", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, "Ayarlar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { viewModel.swapTripPoints() }) {
                            Icon(Icons.Default.SwapVert, "Değiştir", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                TripPointRow(
                    icon = Icons.Default.Place,
                    iconTint = Color(0xFFD93025),
                    label = "Varış",
                    value = uiState.tripDestination?.name,
                    hint = "Nereye gitmek istiyorsunuz?",
                    onClick = { viewModel.openTripSearch("dest") }
                )

                // v1.2.0: Otomatik planlama yerine açık "Rota Bul" butonu
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.planTrip() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.tripOrigin != null && uiState.tripDestination != null && !uiState.isTripPlanning,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (uiState.isTripPlanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Rota Aranıyor...")
                    } else {
                        Icon(Icons.Default.AltRoute, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Rota Bul", fontWeight = FontWeight.Bold)
                    }
                }

                // Kayıtlı Yerler (F2)
                if (uiState.savedPlaces.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Kayıtlı Yerler",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(uiState.savedPlaces.take(6)) { place ->
                            SavedPlaceChip(
                                place = place,
                                onClick = { viewModel.selectSavedPlaceAsDestination(place) },
                                onLongClick = { placeToManage = place }
                            )
                        }
                    }
                }
            }

            // Alt Drag Handle (Açıkken yukarı itmek veya tıklayarak kapatmak için zarif tutamaç)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleCollapse)
                    .padding(top = 8.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(width = 36.dp, height = 4.dp)
                ) {}
            }
        }
    }
}

    // Dialog: Yer Kaydet (F2)
    if (placeToSave != null) {
        val place = placeToSave!!
        AlertDialog(
            onDismissRequest = { placeToSave = null },
            title = { Text("Yeri Kaydet", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(place.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text("Kategori:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = saveKind == "home",
                            onClick = { saveKind = "home" },
                            leadingIcon = { Icon(Icons.Default.Home, null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Ev") }
                        )
                        FilterChip(
                            selected = saveKind == "work",
                            onClick = { saveKind = "work" },
                            leadingIcon = { Icon(Icons.Default.Work, null, modifier = Modifier.size(16.dp)) },
                            label = { Text("İş") }
                        )
                        FilterChip(
                            selected = saveKind == "custom",
                            onClick = { saveKind = "custom" },
                            leadingIcon = { Icon(Icons.Default.Star, null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Özel") }
                        )
                    }
                    if (saveKind == "custom") {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customName,
                            onValueChange = { customName = it },
                            label = { Text("Yer Adı") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val finalName = when (saveKind) {
                        "home" -> "Ev"
                        "work" -> "İş"
                        else -> customName.ifBlank { place.name }
                    }
                    val id = when (saveKind) {
                        "home" -> "home"
                        "work" -> "work"
                        else -> java.util.UUID.randomUUID().toString()
                    }
                    val lat = place.lat ?: return@TextButton
                    val lng = place.lng ?: return@TextButton
                    viewModel.savePlace(SavedPlace(id = id, name = finalName, lat = lat, lng = lng, kind = saveKind))
                    placeToSave = null
                }) {
                    Text("Kaydet", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { placeToSave = null }) {
                    Text("İptal")
                }
            }
        )
    }

    // Dialog: Kayıtlı Yeri Yönet (F2 - Uzun Basma)
    if (placeToManage != null) {
        val place = placeToManage!!
        AlertDialog(
            onDismissRequest = { placeToManage = null },
            title = { Text(place.name, fontWeight = FontWeight.Bold) },
            text = { Text("Bu kayıtlı yer için bir işlem seçin:") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.selectSavedPlaceAsOrigin(place)
                    placeToManage = null
                }) {
                    Text("Kalkış Yap")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.removeSavedPlace(place.id)
                        placeToManage = null
                    }) {
                        Text("Kaldır", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { placeToManage = null }) {
                        Text("İptal")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedPlaceChip(
    place: SavedPlace,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val (icon, label) = when (place.kind) {
        "home" -> Icons.Default.Home to (if (place.name.isNotBlank()) place.name else "Ev")
        "work" -> Icons.Default.Work to (if (place.name.isNotBlank()) place.name else "İş")
        else -> Icons.Default.Star to place.name
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TripPointRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    value: String?,
    hint: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value ?: hint,
                fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
                color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaceRow(
    place: PlaceResult,
    onSelect: () -> Unit,
    onSavePlace: ((PlaceResult) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (icon, tint) = when (place.kind) {
            "S" -> Icons.Default.Place to Color(0xFF174EA6)
            "P" -> Icons.Default.Storefront to Color(0xFFF9AB00)
            "G" -> Icons.Default.Map to Color(0xFFD93025)
            else -> Icons.Default.Public to Color(0xFF0F9D58)
        }
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                place.name,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            place.subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onSavePlace != null && place.lat != null && place.lng != null) {
            IconButton(
                onClick = { onSavePlace(place) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.StarBorder,
                    contentDescription = "Kaydet",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun TripBottomPanel(
    uiState: MainUiState,
    viewModel: MainViewModel,
    favoritesManager: FavoritesManager,
    isCollapsed: Boolean = false,
    mapInteractionProgress: Float = 0f,
    onToggleCollapse: () -> Unit = {},
    onSetCollapse: (Boolean) -> Unit = {},
    onStartJourney: (Double, Double, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val targetScale = 1f - (0.12f * mapInteractionProgress)
    val targetAlpha = 1f - (0.65f * mapInteractionProgress)

    Column(modifier = modifier.fillMaxWidth()) {
        if (uiState.tripPlans.isNotEmpty() || uiState.isTripPlanning || uiState.errorMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .scale(targetScale)
                    .alpha(targetAlpha)
                    .animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f))
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.55f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                // Üst Tutamaç Çubuğu (Drag Handle)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .panelDragGestures(
                            isCollapsed = isCollapsed,
                            dragDownToCollapse = true,
                            onToggleCollapse = onToggleCollapse,
                            onSetCollapse = onSetCollapse
                        )
                        .clickable(onClick = onToggleCollapse)
                        .padding(top = 6.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(width = 36.dp, height = 4.dp)
                    ) {}
                }

                if (isCollapsed && uiState.tripPlans.isNotEmpty()) {
                    // Daraltılmış Kompakt Görünüm
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .panelDragGestures(
                                isCollapsed = true,
                                dragDownToCollapse = true,
                                onToggleCollapse = onToggleCollapse,
                                onSetCollapse = onSetCollapse
                            )
                            .clickable(onClick = onToggleCollapse)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AltRoute, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Rota Alternatifleri (${uiState.tripPlans.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (uiState.selectedPlan != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "• Seçili: ${uiState.selectedPlan!!.routeCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else if (uiState.selectedPlan != null) {
                    // Madde 3: Rota Alternatifinde Genişleyen Otobüs Detayı
                    Box(modifier = Modifier.padding(12.dp)) {
                        ExpandedPlanDetailView(
                            plan = uiState.selectedPlan!!,
                            uiState = uiState,
                            viewModel = viewModel,
                            onStartJourney = {
                                val json = viewModel.buildJourneyPayloadJson()
                                if (json != null) {
                                    if (LocationHelper.hasPermission(context)) {
                                        JourneyService.startJourney(context, json)
                                    } else {
                                        Toast.makeText(context, "Canlı rehberlik için konum izni gerekli", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                } else {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .panelDragGestures(
                                    isCollapsed = false,
                                    dragDownToCollapse = true,
                                    onToggleCollapse = onToggleCollapse,
                                    onSetCollapse = onSetCollapse
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Rota Alternatifleri",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(onClick = onToggleCollapse)
                            )
                            if (uiState.isTripPlanning) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            IconButton(onClick = { viewModel.clearTrip() }) {
                                Icon(Icons.Default.Close, "Kapat")
                            }
                        }

                    val sortedPlans = remember(uiState.tripPlans, uiState.planSort) {
                        when (uiState.planSort) {
                            "hizli" -> {
                                uiState.tripPlans.sortedWith(
                                    compareBy<RoutePlan> { if (it.isWalkOnly && it.estimatedDurationMinutes <= 0) 0 else 1 }
                                        .thenBy { it.estimatedDurationMinutes }
                                )
                            }
                            "azyurume" -> {
                                uiState.tripPlans.sortedWith(
                                    compareBy<RoutePlan> { if (it.isWalkOnly) 0 else 1 }
                                        .thenBy { it.walkToMeters + it.walkFromMeters + it.transferWalkMeters }
                                )
                            }
                            "azaktarma" -> {
                                uiState.tripPlans.sortedWith(
                                    compareBy<RoutePlan> { it.isTransfer }
                                        .thenBy { it.estimatedDurationMinutes }
                                )
                            }
                            else -> uiState.tripPlans
                        }
                    }

                    if (uiState.tripPlans.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = uiState.planSort == "onerilen",
                                    onClick = { viewModel.setPlanSort("onerilen") },
                                    label = { Text("Önerilen", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = uiState.planSort == "hizli",
                                    onClick = { viewModel.setPlanSort("hizli") },
                                    label = { Text("En Hızlı", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = uiState.planSort == "azyurume",
                                    onClick = { viewModel.setPlanSort("azyurume") },
                                    label = { Text("Az Yürüme", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = uiState.planSort == "azaktarma",
                                    onClick = { viewModel.setPlanSort("azaktarma") },
                                    label = { Text("Az Aktarma", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    if (uiState.tripPlans.isEmpty() && !uiState.isTripPlanning) {
                        Text(
                            uiState.errorMessage ?: "Rota bulunamadı.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 230.dp)) {
                            items(sortedPlans) { plan ->
                                val firstBus = plan.legs.firstOrNull { it.kind == "bus" }
                                val etaKey = if (firstBus != null && firstBus.boardStop?.stopId != null && firstBus.routeCode != null) {
                                    "${firstBus.boardStop!!.stopId}:${firstBus.routeCode}"
                                } else null
                                val etaText = etaKey?.let { uiState.planEtas[it] }
                                PlanRow(
                                    plan = plan,
                                    etaText = etaText,
                                    onClick = { viewModel.selectRouteFromPlan(plan) }
                                )
                                HorizontalDivider(thickness = 0.5.dp)
                            }
                        }
                    }
                }
                }
            }
        }

        if (uiState.selectedStop != null) {
            StopDetailCard(
                uiState = uiState,
                viewModel = viewModel,
                favoritesManager = favoritesManager,
                onStartJourney = onStartJourney,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
            )
        }

        if (uiState.selectedBus != null && uiState.selectedStop == null && uiState.tripPlans.isEmpty()) {
            BusDetailCard(uiState, viewModel)
        }

        if (uiState.selectedRouteCode != null && uiState.selectedStop == null &&
            uiState.tripPlans.isEmpty() && uiState.selectedBus == null
        ) {
            RouteDetailCard(uiState, viewModel)
        }
    }
}

@Composable
private fun ExpandedPlanDetailView(
    plan: RoutePlan,
    uiState: MainUiState,
    viewModel: MainViewModel,
    onStartJourney: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { (context.applicationContext as GoItApp).settingsManager }
    val fareType = settingsManager.fareType // "TAM", "İNDİRİMLİ", "ÖĞRENCİ"

    val firstBusLeg = plan.legs.firstOrNull { it.kind == "bus" }
    val boardingStop = firstBusLeg?.boardStop ?: plan.originStop
    val alightStop = firstBusLeg?.alightStop ?: plan.destinationStop

    val farePriceText = remember(uiState.routePrice, fareType) {
        if (uiState.routePrice.isEmpty()) null
        else {
            val target = fareType.uppercase(java.util.Locale.ROOT)
            val match = uiState.routePrice.firstOrNull { cp ->
                val ct = cp.cardType?.uppercase(java.util.Locale.ROOT) ?: ""
                when (target) {
                    "TAM" -> ct.contains("TAM")
                    "İNDİRİMLİ" -> ct.contains("İNDİRİM") || ct.contains("INDIRIM")
                    "ÖĞRENCİ" -> ct.contains("ÖĞRENC") || ct.contains("OGRENC")
                    else -> false
                }
            } ?: uiState.routePrice.firstOrNull()
            match?.let { formatPriceText(it.price) }
        }
    }

    val busStops = remember(firstBusLeg, plan) {
        if (firstBusLeg != null && firstBusLeg.stops.isNotEmpty()) {
            firstBusLeg.stops
        } else if (plan.intermediateStops.isNotEmpty()) {
            val list = mutableListOf<RouteStop>()
            list.add(plan.originStop)
            list.addAll(plan.intermediateStops)
            list.add(plan.destinationStop)
            list
        } else {
            listOfNotNull(plan.originStop, plan.destinationStop)
        }
    }

    val liveBus = uiState.selectedPlanLiveBus
    val closestStopId = remember(liveBus, busStops) {
        if (liveBus?.enlem != null && liveBus.boylam != null && busStops.isNotEmpty()) {
            busStops.minByOrNull { s ->
                val la = s.latitude?.toDoubleOrNull() ?: 0.0
                val lo = s.longitude?.toDoubleOrNull() ?: 0.0
                TransitRepository.calculateDistanceMeters(liveBus.enlem, liveBus.boylam, la, lo)
            }?.stopId
        } else null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Üst Başlık & Kapatma
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.deselectPlan() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (plan.isWalkOnly) "Yürüyüş Rotası" else plan.routeCode,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (!plan.isWalkOnly && plan.routeName.isNotBlank()) {
                    Text(
                        text = plan.routeName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (plan.isTransfer) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFE37400).copy(alpha = 0.15f),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        "1 Aktarma",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE37400),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            IconButton(onClick = { viewModel.clearTrip() }) {
                Icon(Icons.Default.Close, contentDescription = "Kapat")
            }
        }

        Spacer(Modifier.height(8.dp))

        if (plan.isWalkOnly) {
            // Yürüyüş Planı
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.DirectionsWalk,
                    null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Sadece yürüme ile ulaşım", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${formatDistance(plan.walkOnlyMeters)} • ~${plan.estimatedDurationMinutes} dk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.selectedPlanEstimatedArrival != null) {
                        Text(
                            uiState.selectedPlanEstimatedArrival!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        } else {
            // 2 Sütunlu Görünüm: Sol Dikey Durak Zaman Çizelgesi, Sağ Detaylar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
            ) {
                // Sol Sütun: Dikey Duraklar
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight()
                ) {
                    Text(
                        "Duraklar (${busStops.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        itemsIndexed(busStops) { index, stop ->
                            val isBoarding = stop.stopId == boardingStop.stopId
                            val isAlight = stop.stopId == alightStop.stopId
                            val isBusHere = stop.stopId == closestStopId

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Durak göstergesi
                                when {
                                    isBusHere -> {
                                        Icon(
                                            Icons.Default.DirectionsBus,
                                            contentDescription = "Otobüs Konumu",
                                            tint = Color(0xFF1A73E8),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    isBoarding -> {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(Color(0xFF0F9D58), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(modifier = Modifier.size(6.dp).background(Color.White, CircleShape))
                                        }
                                    }
                                    isAlight -> {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(Color(0xFFD93025), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(modifier = Modifier.size(6.dp).background(Color.White, CircleShape))
                                        }
                                    }
                                    else -> {
                                        Box(
                                            modifier = Modifier
                                                .padding(horizontal = 4.dp)
                                                .size(6.dp)
                                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
                                        )
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                Text(
                                    text = when {
                                        isBoarding -> "Biniş: ${stop.stopName ?: ""}"
                                        isAlight -> "İniş: ${stop.stopName ?: ""}"
                                        else -> stop.stopName ?: ""
                                    },
                                    style = if (isBoarding || isAlight) MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                    else MaterialTheme.typography.bodySmall,
                                    color = when {
                                        isBoarding -> Color(0xFF0F9D58)
                                        isAlight -> Color(0xFFD93025)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                // Sağ Sütun: Fiyat, Durağa Varış, Sonraki Seferler, Hedefe Varış
                Column(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        // Fiyat
                        if (farePriceText != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Payments, null, tint = Color(0xFFF9AB00), modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "$fareType: $farePriceText",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        // Otobüsün durağa ulaşma zamanı
                        val stopKey = "${boardingStop.stopId}:${firstBusLeg?.routeCode ?: plan.routeCode}"
                        val liveEta = uiState.planEtas[stopKey]
                        val displayEta = if (!liveEta.isNullOrBlank() && liveEta != "–") liveEta else "Tarifeye göre"
                        val isScheduled = liveEta?.contains("Planlı", true) == true || displayEta == "Tarifeye göre"
                        val etaBadgeColor = if (isScheduled) Color(0xFF1976D2) else Color(0xFF0F9D58)

                        Column {
                            Text("Durağa Varış", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isScheduled) Icons.Default.Schedule else Icons.Default.DirectionsBus,
                                    null,
                                    tint = etaBadgeColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    displayEta,
                                    color = etaBadgeColor,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Sonraki seferler
                        if (uiState.selectedPlanUpcomingRuns.isNotEmpty()) {
                            Column {
                                Text("Sonraki Seferler", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    uiState.selectedPlanUpcomingRuns.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    // En altta: Hedefe Tahmini Varış
                    if (uiState.selectedPlanEstimatedArrival != null) {
                        Text(
                            uiState.selectedPlanEstimatedArrival!!,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Yolculuğu Başlat Butonu
        Button(
            onClick = onStartJourney,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF16A34A),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Yolculuğu Başlat", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlanRow(
    plan: RoutePlan,
    etaText: String? = null,
    onClick: () -> Unit
) {
    val arrivalTimeText = remember(plan.estimatedDurationMinutes) {
        runCatching {
            val now = java.time.LocalTime.now()
            val arrival = now.plusMinutes(plan.estimatedDurationMinutes.toLong())
            arrival.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }.getOrNull()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sol İkon / Hat Rozeti
        if (plan.isWalkOnly) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF757575), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.DirectionsWalk, "Yürüyüş", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        } else {
            Box(
                modifier = Modifier
                    .background(
                        if (plan.isTransfer) Color(0xFFE37400) else Color(0xFF1A73E8),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(plan.routeCode, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.width(12.dp))

        // Orta: Güzergah Bacakları & Süre Özeti
        Column(modifier = Modifier.weight(1f)) {
            if (plan.isWalkOnly) {
                Text("Sadece yürü", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${formatDistance(plan.walkOnlyMeters)} • ~${plan.estimatedDurationMinutes} dk" +
                        (if (arrivalTimeText != null) " • Varış ~$arrivalTimeText" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                PlanLegSummary(plan)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Toplam ~${plan.estimatedDurationMinutes} dk" +
                        (if (arrivalTimeText != null) " • Varış ~$arrivalTimeText" else "") +
                        (if (plan.totalStops > 0) " • ${plan.totalStops} durak" else "") +
                        (if (plan.isTransfer) " • 1 aktarma" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Sağ: Sağa Yaslanmış Otobüs Geliş Zamanı (Madde 2)
        if (!plan.isWalkOnly) {
            val isScheduled = etaText?.contains("Planlı", ignoreCase = true) == true || etaText?.contains("İlk", ignoreCase = true) == true
            val displayEta = if (!etaText.isNullOrBlank() && etaText != "–") etaText else "Hesaplanıyor"
            val badgeColor = if (isScheduled) Color(0xFF1976D2) else Color(0xFF0F9D58)

            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .background(badgeColor.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isScheduled) Icons.Default.Schedule else Icons.Default.DirectionsBus,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        displayEta,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Çok bacaklı rota adımları: "6 dk yürü → 38 (12 durak) → 4 dk yürü" */
@Composable
private fun PlanLegSummary(plan: RoutePlan) {
    val items = mutableListOf<String>()
    if (plan.legs.isNotEmpty()) {
        plan.legs.forEach { leg ->
            when (leg.kind) {
                "walk" -> if (leg.walkMeters >= 40.0) items.add("${leg.minutes} dk yürü")
                "bus" -> {
                    val count = if (leg.stops.isNotEmpty()) "${leg.stops.size - 1} durak" else ""
                    val code = leg.routeCode ?: ""
                    items.add(if (count.isNotEmpty()) "$code ($count)" else code)
                }
            }
        }
    } else {
        items.add(plan.routeCode)
    }
    Text(
        items.joinToString(" → "),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

// =========================================================
//  DURAK DETAY KARTI
// =========================================================

@Composable
private fun StopDetailCard(
    uiState: MainUiState,
    viewModel: MainViewModel,
    favoritesManager: FavoritesManager,
    onStartJourney: (Double, Double, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val stop = uiState.selectedStop ?: return
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Place, null, tint = Color(0xFF174EA6))
                Spacer(Modifier.width(8.dp))
                Text(
                    stop.stopName ?: "Durak",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val refId = stop.stopId ?: 0
                val isFav = uiState.favorites.any { it.type == "S" && it.refId == refId }
                IconButton(onClick = {
                    favoritesManager.toggleFavoriteRaw("S", stop.stopName ?: "", "", refId)
                    viewModel.refreshFavorites()
                }) {
                    Icon(
                        if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                        "Favori",
                        tint = if (isFav) Color(0xFFF9AB00) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { viewModel.clearStop() }) {
                    Icon(Icons.Default.Close, "Kapat")
                }
            }
            Spacer(Modifier.height(4.dp))

            // Yaklaşan araçlar + dakika
            if (uiState.stopEtaList.isEmpty()) {
                Text(
                    "Şu an yaklaşan araç bilgisi yok.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(uiState.stopEtaList) { eta ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF174EA6), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(eta.routeCode ?: "", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(eta.routeTitle ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!eta.licencePlate.isNullOrBlank()) {
                                        Text(
                                            eta.licencePlate,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    if (eta.isAirConditioner == true) {
                                        Icon(Icons.Default.AcUnit, "Klima", tint = Color(0xFF0288D1), modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    if (eta.isSuitableForDisabled == true) {
                                        Icon(Icons.Default.Accessible, "Engelli Erişimi", tint = Color(0xFF1976D2), modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                            Text(
                                formatEtaTime(eta.realTime ?: eta.passTime),
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F9D58)
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }

            // Buradan Git + İniş Alarmı
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.startTripFromSelectedStop() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.AltRoute, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Buradan Git", style = MaterialTheme.typography.labelMedium)
                }
                val stopLat = stop.latitude?.toDoubleOrNull()
                val stopLng = stop.longitude?.toDoubleOrNull()
                if (stopLat != null && stopLng != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onStartJourney(stopLat, stopLng, stop.stopName ?: "Hedef") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Navigation, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("İniş Alarmı", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Tarife
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { viewModel.toggleSchedule() }, modifier = Modifier.align(Alignment.Start)) {
                Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (uiState.showSchedule) "Tarifeyi Gizle" else "Hareket Saatleri (Tarife)",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (uiState.showSchedule) {
                if (uiState.stopSchedule.isEmpty()) {
                    Text(
                        "Tarife bilgisi yükleniyor...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                        items(uiState.stopSchedule) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    entry.stopTime?.take(5) ?: "",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(entry.information ?: entry.routeCode ?: "", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Geçen hatlar
            if (uiState.stopLines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Geçen Hatlar:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                LazyColumn(modifier = Modifier.heightIn(max = 84.dp)) {
                    items(uiState.stopLines) { line ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val code = line.routeCode ?: return@clickable
                                    viewModel.clearStop()
                                    viewModel.selectRoute(code, line.routeNo)
                                }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF174EA6), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(line.routeCode ?: "", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                line.routeName ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BusDetailCard(uiState: MainUiState, viewModel: MainViewModel) {
    val bus = uiState.selectedBus ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DirectionsBus, null, tint = Color(0xFF0F9D58))
                Spacer(Modifier.width(8.dp))
                Text(bus.plaka ?: "Araç", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.clearBus() }) { Icon(Icons.Default.Close, "Kapat") }
            }
            val speed = bus.hiz ?: 0
            val statusText = if (speed > 2) "Hareket halinde" else "Duraklamada"
            Text(
                "Durum: $statusText • Hız: $speed km/s • Yön: " + when (bus.istikamet) {
                    "G" -> "Gidiş"
                    "D" -> "Dönüş"
                    "R" -> "Ring"
                    else -> bus.istikamet ?: "-"
                },
                style = MaterialTheme.typography.bodySmall
            )
            bus.seferYolcu?.let { Text("Seferdeki yolcu: $it", style = MaterialTheme.typography.bodySmall) }
            uiState.selectedBusDistance?.let {
                Text(
                    "Size uzaklık: ${formatDistance(it.toDouble())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            bus.surucu?.let {
                Text("Şoför: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RouteDetailCard(uiState: MainUiState, viewModel: MainViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DirectionsBus, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Hat: ${uiState.selectedRouteCode}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${uiState.realtimeBuses.size} canlı araç", color = Color(0xFF0F9D58), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { viewModel.clearRoute() }) { Icon(Icons.Default.Close, "Kapat") }
            }
            DirectionChips(uiState, viewModel)
            RoutePriceRow(uiState)
            Spacer(Modifier.height(6.dp))
            Text("Duraklar:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            LazyColumn(modifier = Modifier.heightIn(max = 190.dp)) {
                val filtered = uiState.selectedRouteStops
                    .filter { it.direction == uiState.selectedDirection || it.direction == null }
                    .sortedBy { it.sequence }
                itemsIndexed(filtered) { idx, stop ->
                    StopTimelineRow(stop = stop, index = idx + 1, onClick = { viewModel.selectStop(stop) })
                }
            }
        }
    }
}

@Composable
private fun DirectionChips(uiState: MainUiState, viewModel: MainViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val dirs = uiState.selectedRouteDirections
        val showG = dirs.isEmpty() || dirs.contains("G")
        val showD = dirs.isEmpty() || dirs.contains("D")
        val showR = dirs.contains("R")
        if (showG) {
            FilterChip(selected = uiState.selectedDirection == "G", onClick = { viewModel.setDirection("G") }, label = { Text("Gidiş") })
            Spacer(Modifier.width(6.dp))
        }
        if (showD) {
            FilterChip(selected = uiState.selectedDirection == "D", onClick = { viewModel.setDirection("D") }, label = { Text("Dönüş") })
        }
        if (showR && !showG && !showD) {
            FilterChip(selected = uiState.selectedDirection == "R", onClick = { viewModel.setDirection("R") }, label = { Text("Ring") })
        }
    }
}

@Composable
private fun RoutePriceRow(uiState: MainUiState) {
    if (uiState.routePrice.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Payments, null, modifier = Modifier.size(14.dp), tint = Color(0xFFF9AB00))
        Spacer(Modifier.width(6.dp))
        Text(
            uiState.routePrice.joinToString(" • ") {
                "${it.cardType?.lowercase()?.replaceFirstChar { c -> c.uppercase() } ?: ""} ${formatPriceText(it.price) ?: ""}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StopTimelineRow(stop: RouteStop, index: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Text(index.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stop.stopName ?: "",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// =========================================================
//  DURAKLAR SEKMESİ
// =========================================================

@Composable
private fun StopsTab(
    uiState: MainUiState,
    viewModel: MainViewModel,
    favoritesManager: FavoritesManager,
    onStartJourney: (Double, Double, String) -> Unit,
    onOpenSettings: () -> Unit,
    onLocate: () -> Unit
) {
    if (uiState.selectedStop != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                title = uiState.selectedStop?.stopName ?: "Durak",
                onBack = { viewModel.clearStop() },
                actions = {
                    IconButton(onClick = onLocate) { Icon(Icons.Default.Refresh, "Yenile") }
                }
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    StopDetailCard(
                        uiState = uiState,
                        viewModel = viewModel,
                        favoritesManager = favoritesManager,
                        onStartJourney = onStartJourney,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            title = "Duraklar",
            onBack = null,
            actions = {
                IconButton(onClick = onLocate) { Icon(Icons.Default.MyLocation, "Konumumu bul") }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Ayarlar") }
            }
        )

        if (!uiState.locationAvailable) {
            InfoBanner("Konum alınamadı. Konum izni verin ve tekrar deneyin.")
        }

        val favStops = uiState.favorites.filter { it.type == "S" }
        val nearestStationId = remember(uiState.nearbyStops) {
            uiState.nearbyStops.firstOrNull()?.takeIf { it.distanceMeters <= NEAR_STOP_METERS }?.station?.i
        }

        if (uiState.nearbyStops.isEmpty() && favStops.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (uiState.isLoadingNearbyEtas) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Yakındaki duraklar aranıyor...", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Icon(Icons.Default.LocationOff, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Konum bekleniyor...", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "İzin verildiğinde en yakın duraklar burada listelenir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (favStops.isNotEmpty()) {
                    item { SectionHeader("Favori Duraklar") }
                    items(favStops) { fav ->
                        FavoriteStopRow(fav, onClick = { viewModel.selectFavoriteStop(fav) })
                    }
                }
                item {
                    Text(
                        "Size en yakın duraklar",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
                items(uiState.nearbyStops) { row ->
                    NearbyStopRow(
                        row = row,
                        isNearest = row.station.i == nearestStationId,
                        onClick = { viewModel.selectNearbyStop(row.station) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteStopRow(fav: FavoriteItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Star, null, tint = Color(0xFFF9AB00), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            fav.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NearbyStopRow(
    row: NearbyStopUi,
    isNearest: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Place, null, tint = Color(0xFF174EA6), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    row.station.n,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isNearest) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF0F9D58), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Sen burdasın", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    formatDistance(row.distanceMeters),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (row.buses.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                row.buses.forEach { bus ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF174EA6), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(bus.routeCode, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            bus.minutesText,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF0F9D58),
                            fontWeight = FontWeight.Bold
                        )
                        if (bus.ac == true) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.AcUnit, "Klima", tint = Color(0xFF0288D1), modifier = Modifier.size(14.dp))
                        }
                        if (bus.accessible == true) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Accessible, "Engelli Erişimi", tint = Color(0xFF1976D2), modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        if (bus.priceText != null) {
                            Text(bus.priceText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// =========================================================
//  HATLAR SEKMESİ
// =========================================================

@Composable
private fun RoutesTab(
    uiState: MainUiState,
    viewModel: MainViewModel,
    favoritesManager: FavoritesManager,
    onStartJourney: (Double, Double, String) -> Unit,
    onOpenSettings: () -> Unit,
    onStop: (RouteStop) -> Unit
) {
    // Durak detayı (Hat içinden durağa tıklanınca)
    if (uiState.selectedStop != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                title = uiState.selectedStop?.stopName ?: "Durak",
                onBack = { viewModel.clearStop() },
                actions = {}
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    StopDetailCard(
                        uiState = uiState,
                        viewModel = viewModel,
                        favoritesManager = favoritesManager,
                        onStartJourney = onStartJourney,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }
            }
        }
        return
    }

    if (uiState.selectedRouteCode != null) {
        RouteDetailScreen(uiState, viewModel, favoritesManager, onStop)
        return
    }

    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            title = "Hatlar",
            onBack = null,
            actions = {
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Ayarlar") }
            }
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            placeholder = { Text("Hat ara (B16A, T1, M2, 38...)") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) } }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(8.dp))

        val recent = uiState.recentRoutes
        val favRoutes = uiState.favorites.filter { it.type == "R" }
        val results = if (query.trim().isEmpty()) uiState.allRoutes else RouteCatalog.search(query.trim(), 120)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Arama kutusu boşken:
            if (query.trim().isEmpty()) {
                // 1) En yakın durağa yaklaşan hatlar (canlı ETA'lar)
                if (uiState.nearestStopEtas.isNotEmpty()) {
                    item {
                        SectionHeader(
                            if (uiState.nearestStopName != null) {
                                "En Yakın Durak (${uiState.nearestStopName})"
                            } else {
                                "Yakındaki Durağa Gelen Hatlar"
                            }
                        )
                    }
                    items(uiState.nearestStopEtas) { bus ->
                        NearestStopRouteRow(bus = bus, onClick = { viewModel.selectRouteByCode(bus.routeCode) })
                    }
                }

                // 2) Favori Hatlar
                if (favRoutes.isNotEmpty()) {
                    item { SectionHeader("Favori Hatlar") }
                    items(favRoutes) { fav ->
                        RouteRow(code = fav.title, title = fav.subtitle.ifEmpty { null }, onClick = { viewModel.selectRouteByCode(fav.title) })
                    }
                }

                // 3) Son Kullanılanlar
                if (recent.isNotEmpty()) {
                    item { SectionHeader("Son Kullanılanlar") }
                    items(recent) { code ->
                        RouteRow(code = code, title = RouteCatalog.findByCode(code)?.aciklama, onClick = { viewModel.selectRouteByCode(code) })
                    }
                }

                // 4) "Tüm Hatlar (409)" aç/kapa butonu
                item {
                    AllRoutesToggleHeader(
                        totalCount = uiState.allRoutes.size,
                        isExpanded = uiState.showAllRoutes,
                        onToggle = { viewModel.toggleShowAllRoutes() }
                    )
                }

                if (uiState.showAllRoutes) {
                    items(uiState.allRoutes) { entry ->
                        RouteRow(code = entry.kod, title = entry.aciklama, onClick = { viewModel.selectRouteByCode(entry.kod) })
                    }
                }
            } else {
                // Arama kutusunda metin varsa: filtrelenmiş katalog listesi
                item { SectionHeader("Arama Sonuçları (${results.size})") }
                items(results) { entry ->
                    RouteRow(code = entry.kod, title = entry.aciklama, onClick = { viewModel.selectRouteByCode(entry.kod) })
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/** En yakın durağa yaklaşan hat satırı (canlı ETA rozeti ile). */
@Composable
private fun NearestStopRouteRow(bus: NearbyBusEta, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xFF174EA6), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(bus.routeCode, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            RouteCatalog.findByCode(bus.routeCode)?.aciklama ?: "Hat ${bus.routeCode}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .background(Color(0xFF0F9D58).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bus.minutesText,
                    color = Color(0xFF0F9D58),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
                if (bus.ac == true) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.AcUnit, "Klima", tint = Color(0xFF0288D1), modifier = Modifier.size(14.dp))
                }
                if (bus.accessible == true) {
                    Spacer(Modifier.width(3.dp))
                    Icon(Icons.Default.Accessible, "Engelli Erişimi", tint = Color(0xFF1976D2), modifier = Modifier.size(14.dp))
                }
            }
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** "Tüm Hatlar (409)" genişletilebilir başlık düğmesi. */
@Composable
private fun AllRoutesToggleHeader(
    totalCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Tüm Hatlar ($totalCount)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (isExpanded) "Gizle" else "Göster",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun RouteRow(code: String, title: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xFF174EA6), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(code, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RouteDetailScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    favoritesManager: FavoritesManager,
    onStop: (RouteStop) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            title = "Hat ${uiState.selectedRouteCode}",
            onBack = { viewModel.clearRoute() },
            actions = {
                val code = uiState.selectedRouteCode ?: ""
                val isFav = uiState.favorites.any { it.type == "R" && it.title == code }
                IconButton(onClick = {
                    val hatNo = uiState.selectedRouteHatNo ?: RouteCatalog.findByCode(code)?.hatNo ?: 0
                    favoritesManager.toggleFavoriteRaw("R", code, "", hatNo)
                    viewModel.refreshFavorites()
                }) {
                    Icon(
                        if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                        "Favori",
                        tint = if (isFav) Color(0xFFF9AB00) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${uiState.realtimeBuses.size} araç",
                    color = Color(0xFF0F9D58),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
        )

        val filtered = remember(uiState.selectedRouteStops, uiState.selectedDirection) {
            uiState.selectedRouteStops
                .filter { it.direction == uiState.selectedDirection || it.direction == null }
                .sortedBy { it.sequence }
        }
        val nearestStopId = remember(filtered, uiState.userLat, uiState.userLng) {
            val uLat = uiState.userLat; val uLng = uiState.userLng
            if (uLat == null || uLng == null) null
            else filtered.mapNotNull { s ->
                val la = s.latitude?.toDoubleOrNull(); val lo = s.longitude?.toDoubleOrNull()
                if (la != null && lo != null) s.stopId to com.taha.goit.data.api.TransitRepository.calculateDistanceMeters(uLat, uLng, la, lo) else null
            }.minByOrNull { it.second }?.takeIf { it.second <= NEAR_STOP_METERS }?.first
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    DirectionChips(uiState, viewModel)
                }
            }

            if (uiState.routePrice.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.padding(horizontal = 14.dp)) {
                        RoutePriceRow(uiState)
                    }
                }
            }

            if (uiState.realtimeBuses.isNotEmpty()) {
                item { SectionHeader("Canlı Araçlar (${uiState.realtimeBuses.size})") }
                items(uiState.realtimeBuses) { bus ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectBus(bus) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DirectionsBus, null, tint = Color(0xFF0F9D58), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(bus.plaka ?: "?", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${bus.hiz ?: 0} km/s • " + when (bus.istikamet) {
                                "G" -> "Gidiş"
                                "D" -> "Dönüş"
                                "R" -> "Ring"
                                else -> bus.istikamet ?: ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { SectionHeader("Duraklar") }
            itemsIndexed(filtered) { idx, stop ->
                RouteStopRow(
                    index = idx + 1,
                    stop = stop,
                    isNearest = stop.stopId == nearestStopId,
                    buses = uiState.realtimeBuses,
                    onClick = { onStop(stop) }
                )
            }
        }
    }
}

@Composable
private fun RouteStopRow(
    index: Int,
    stop: RouteStop,
    isNearest: Boolean = false,
    buses: List<com.taha.goit.data.model.BusRealtimeLocation>,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Text(index.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                stop.stopName ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val busesHere = remember(stop.stopId, buses) {
                buses.filter { b ->
                    val la = stop.latitude?.toDoubleOrNull()
                    val lo = stop.longitude?.toDoubleOrNull()
                    b.enlem != null && b.boylam != null && la != null && lo != null &&
                            com.taha.goit.data.api.TransitRepository.calculateDistanceMeters(b.enlem, b.boylam, la, lo) <= 200.0
                }
            }
            if (busesHere.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF0F9D58), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        busesHere.joinToString(", ") { it.plaka ?: "" },
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        if (isNearest) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 30.dp, top = 2.dp)) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF0F9D58), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Sen burdasın", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// =========================================================
//  HARİTA
// =========================================================

@Composable
private fun TripMap(
    styleUrl: String,
    onMapViewCreated: (MapView) -> Unit,
    onLayersReady: (MapLayers) -> Unit,
    onStopTapped: (Int) -> Unit,
    onBusTapped: (String) -> Unit,
    onMapGestureChanged: (Boolean) -> Unit = {}
) {
    val currentStopTapped by rememberUpdatedState(onStopTapped)
    val currentBusTapped by rememberUpdatedState(onBusTapped)
    val currentGestureChanged by rememberUpdatedState(onMapGestureChanged)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val container = object : android.widget.FrameLayout(context) {
                override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> currentGestureChanged(true)
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> currentGestureChanged(false)
                    }
                    return super.dispatchTouchEvent(ev)
                }
            }
            val mv = MapView(context)
            container.addView(
                mv,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            onMapViewCreated(mv)
            mv.onCreate(null)
            mv.getMapAsync { map ->
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        currentGestureChanged(true)
                    }
                }
                map.addOnCameraIdleListener {
                    currentGestureChanged(false)
                }

                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(40.1832, 29.0647))
                        .zoom(12.0)
                        .build()
                    val layers = MapLayers(map)
                    layers.setup(
                        style = style,
                        busIcon = MapUtils.createBusIcon(),
                        stopIcon = MapUtils.createStopIcon(),
                        userIcon = MapUtils.createUserIcon(),
                        pinAIcon = MapUtils.createPinIcon("A", 0xFF0F9D58.toInt()),
                        pinBIcon = MapUtils.createPinIcon("B", 0xFFD93025.toInt()),
                        subtleStopIcon = MapUtils.createSubtleStopIcon()
                    )
                    onLayersReady(layers)
                }

                map.addOnMapClickListener { latLng ->
                    runCatching {
                        val screenPoint = map.projection.toScreenLocation(latLng)
                        val features = map.queryRenderedFeatures(
                            screenPoint, MapLayers.LAYER_BUSES, MapLayers.LAYER_STOPS
                        )
                        if (features.isNotEmpty()) {
                            val f = features.first()
                            val stopId = f.getNumberProperty("stopId")?.toInt()
                            val plate = f.getStringProperty("plate")
                            if (stopId != null && stopId > 0) {
                                currentStopTapped(stopId)
                            } else if (!plate.isNullOrBlank()) {
                                currentBusTapped(plate)
                            }
                        }
                    }
                    true
                }
            }
            container
        },
        onRelease = { container ->
            val mv = container.getChildAt(0) as? MapView
            mv?.let {
                runCatching { it.onStop() }
                runCatching { it.onDestroy() }
            }
        }
    )
}

// =========================================================
//  ORTAK KÜÇÜK PARÇALAR
// =========================================================

@Composable
private fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Geri") }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
            )
            actions()
        }
    }
}

@Composable
private fun InfoBanner(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
