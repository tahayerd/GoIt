package com.taha.goit

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.taha.goit.service.JourneyService
import com.taha.goit.ui.MainScreen
import com.taha.goit.ui.MainViewModel
import com.taha.goit.ui.theme.GoItTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Konum ve bildirim izinlerini iste
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())

        setContent {
            GoItTheme {
                MainScreen(
                    viewModel = viewModel,
                    favoritesManager = (application as GoItApp).favoritesManager,
                    historyManager = (application as GoItApp).historyManager,
                    onStartJourney = { lat, lng, name ->
                        JourneyService.start(this, lat, lng, name)
                    }
                )
            }
        }
    }
}
