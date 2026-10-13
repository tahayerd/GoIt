package com.taha.goit

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.taha.goit.location.LocationHelper
import com.taha.goit.service.JourneyService
import com.taha.goit.ui.MainScreen
import com.taha.goit.ui.MainViewModel
import com.taha.goit.ui.theme.GoItTheme
import com.taha.goit.util.MapsLinkParser
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private var stopLocationUpdates: (() -> Unit)? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (LocationHelper.hasPermission(this)) {
            startLocationTracking()
            lifecycleScope.launch {
                val loc = LocationHelper.getFreshLocation(this@MainActivity)
                if (loc != null) viewModel.updateUserLocation(loc.latitude, loc.longitude)
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dışarıdan gelen Google Maps Intent'ini işle
        handleIncomingIntent(intent)

        val app = application as GoItApp
        viewModel.setPlacesManager(app.savedPlacesManager)
        viewModel.setJourneyHistoryManager(app.journeyHistoryManager)
        viewModel.setSettingsManager(app.settingsManager)
        viewModel.setScheduleCache(app.scheduleCache)
        viewModel.setOsrmRouter(app.osrmRouter)

        // İlk açılışta hızlı son konum
        val lastLoc = LocationHelper.getLastKnownLocation(this)
        if (lastLoc != null) {
            viewModel.updateUserLocation(lastLoc.latitude, lastLoc.longitude)
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            GoItTheme(themeMode = uiState.themeMode) {
                MainScreen(
                    viewModel = viewModel,
                    favoritesManager = (application as GoItApp).favoritesManager,
                    historyManager = (application as GoItApp).historyManager,
                    onRequestLocationPermission = {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onStartJourney = { lat, lng, name ->
                        JourneyService.start(this, lat, lng, name)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val rawText = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.clipData?.getItemAt(0)?.text?.toString()
            else -> null
        } ?: return

        lifecycleScope.launch {
            val parsed = MapsLinkParser.parse(rawText)
            if (parsed != null) {
                viewModel.setDestinationFromExternal(parsed.lat, parsed.lng, parsed.label)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.resumeBackgroundPolling()
        startLocationTracking()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopBackgroundPolling()
        stopLocationUpdates?.invoke()
        stopLocationUpdates = null
    }

    private fun startLocationTracking() {
        if (LocationHelper.hasPermission(this)) {
            stopLocationUpdates?.invoke()
            stopLocationUpdates = LocationHelper.startContinuousLocationUpdates(this) { loc ->
                viewModel.updateUserLocation(loc.latitude, loc.longitude)
            }
        }
    }
}
