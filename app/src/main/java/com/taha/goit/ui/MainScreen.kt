package com.taha.goit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.taha.goit.data.model.SearchItem
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("GoIt Bursa - FOSS Transit", fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Map View (MapLibre OSM Carto Dark / Light)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    MapView(context).apply {
                        getMapAsync { map ->
                            // OpenStreetMap Carto style (no trackers, no Google keys)
                            map.setStyle(
                                Style.Builder().fromUri("https://basemaps.cartocdn.com/gl/positron-gl-style/style.json")
                            ) {
                                // Default center: Bursa Heykel
                                map.cameraPosition = CameraPosition.Builder()
                                    .target(LatLng(40.1832, 29.0647))
                                    .zoom(12.0)
                                    .build()
                            }
                        }
                    }
                }
            )

            // Top Search & Overlay Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChange(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                    placeholder = { Text("Hat veya Durak Ara (Örn: B16A, Heykel...)") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Ara") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                if (uiState.searchResults.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(max = 280.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        LazyColumn {
                            items(uiState.searchResults) { item ->
                                SearchResultRow(item = item, onClick = {
                                    if (item.type == "R") {
                                        viewModel.selectRoute(item.kod ?: "", item.hatNo)
                                    }
                                })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            // Bottom Live Tracker Status Card
            if (uiState.selectedRouteCode != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.DirectionsBus,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Hat: ${uiState.selectedRouteCode}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "${uiState.realtimeBuses.size} Otobüs Canlı",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (uiState.realtimeBuses.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                                items(uiState.realtimeBuses) { bus ->
                                    Text(
                                        text = "• Plaka: ${bus.plaka ?: "Bilinmiyor"} | Hız: ${bus.hiz ?: 0} km/s",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultRow(item: SearchItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = if (item.type == "R") Icons.Default.DirectionsBus else Icons.Default.Place
        Icon(
            icon,
            contentDescription = null,
            tint = if (item.type == "R") MaterialTheme.colorScheme.primary else Color(0xFFE65100)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = item.kod ?: item.stationName ?: "",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            item.aciklama?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}
