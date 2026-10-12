package com.taha.goit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taha.goit.service.JourneyTracker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale

/**
 * Canlı Yolculuk Rehberi (Overlay — V1.6.0):
 * - Otomatik aşama geçişleri (yürüyüş, bekleme, araç, iniş, varış)
 * - Beklemede: hattın durağa varışına kalan dakika
 * - Yürümede: hedefe/durağa kalan mesafe ve dakika
 * - Araçta: kalan dakika hesabı, geçilen duraklar, sıradaki durak, kalan duraklar
 * - Harita jestinde hafif daralma (scale 0.92, alpha 0.85)
 * - Saf Material Icons, sıfır emoji, AMOLED uyumlu
 */
@Composable
fun JourneyScreen(
    onStopJourney: () -> Unit,
    isMapGestureActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val state by JourneyTracker.state.collectAsState()
    val runtime = state ?: return

    var showStopsList by remember { mutableStateOf(false) }

    val isAlightImminent = runtime.stageType == "ride" &&
        (runtime.stopsRemaining ?: 99) <= 1

    val animatedScale by animateFloatAsState(
        targetValue = if (isMapGestureActive) 0.95f else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 400f),
        label = "hudScale"
    )
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isMapGestureActive) 0.88f else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 400f),
        label = "hudAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .scale(animatedScale)
                .alpha(animatedAlpha),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isAlightImminent) {
                    Color(0xFF7F1D1D).copy(alpha = 0.98f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // İniş yaklaştı kırmızı uyarı şeridi
                if (isAlightImminent) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.25f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = Color(0xFFFCA5A5),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "İn: Sıradaki durak iniş durağın",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFCA5A5)
                            )
                        }
                    }
                }

                // Üst Durum Şeridi: Aşama ikonu ve türü
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = when (runtime.stageType) {
                                "walk" -> MaterialTheme.colorScheme.secondaryContainer
                                "wait" -> Color(0xFFFEF08A)
                                "ride" -> MaterialTheme.colorScheme.primaryContainer
                                "arrived" -> Color(0xFFBBF7D0)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = when (runtime.stageType) {
                                        "walk" -> Icons.Default.DirectionsWalk
                                        "wait" -> Icons.Default.Schedule
                                        "ride" -> Icons.Default.DirectionsBus
                                        "arrived" -> Icons.Default.CheckCircle
                                        else -> Icons.Default.Navigation
                                    },
                                    contentDescription = null,
                                    tint = if (isAlightImminent) Color(0xFFFCA5A5) else when (runtime.stageType) {
                                        "walk" -> MaterialTheme.colorScheme.onSecondaryContainer
                                        "wait" -> Color(0xFF854D0E)
                                        "ride" -> MaterialTheme.colorScheme.onPrimaryContainer
                                        "arrived" -> Color(0xFF166534)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Text(
                            text = when (runtime.stageType) {
                                "walk" -> "YÜRÜYÜŞ"
                                "wait" -> "DURAKTA BEKLE"
                                "ride" -> "ARAÇTA"
                                "arrived" -> "VARIŞ"
                                else -> "CANLI REHBER"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isAlightImminent) Color(0xFFFCA5A5) else MaterialTheme.colorScheme.primary
                        )
                    }

                    // Sağ üst rozet
                    when (runtime.stageType) {
                        "ride" -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isAlightImminent) Color(0xFFEF4444).copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.AccessTime, null, modifier = Modifier.size(14.dp), tint = if (isAlightImminent) Color(0xFFFCA5A5) else MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "${runtime.stopsRemaining ?: 0} durak • ~${runtime.estimatedMinutesRemaining ?: 0} dk",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAlightImminent) Color(0xFFFCA5A5) else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        "wait" -> {
                            if (!runtime.waitEtaMinutes.isNullOrBlank() && runtime.waitEtaMinutes != "-") {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF0F9D58).copy(alpha = 0.15f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.DirectionsBus, null, modifier = Modifier.size(14.dp), tint = Color(0xFF0F9D58))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "Geliş: ~${runtime.waitEtaMinutes}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF0F9D58)
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = "Otomatik algılanır",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        "walk" -> {
                            runtime.walkMinutes?.let { min ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "~$min dk",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                }

                // Başlık & Detay
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = runtime.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isAlightImminent) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = runtime.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isAlightImminent) Color(0xFECACA) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // İlerleme çubuğu
                if (runtime.progress > 0f) {
                    LinearProgressIndicator(
                        progress = runtime.progress.coerceIn(0f, 1f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (isAlightImminent) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                // Araçta iken: Durak listesini açıp kapatma (Madde 4)
                if (runtime.stageType == "ride" && (runtime.passedStops.isNotEmpty() || runtime.upcomingStops.isNotEmpty())) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showStopsList = !showStopsList }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (showStopsList) "Durakları gizle" else "Güzergah duraklarını göster (${(runtime.passedStops.size + 1 + runtime.upcomingStops.size)} durak)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            if (showStopsList) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    AnimatedVisibility(visible = showStopsList) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 160.dp)
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Geçilen duraklar
                                items(runtime.passedStops) { stopName ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stopName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Şu anki / sıradaki durak
                                runtime.currentStopName?.let { curStop ->
                                    item {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.DirectionsBus,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                curStop,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                // Kalan duraklar
                                items(runtime.upcomingStops) { stopName ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .padding(4.dp)
                                                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stopName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Aksiyon butonu: Yolculuğu Bitir
                OutlinedButton(
                    onClick = onStopJourney,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isAlightImminent) Color(0xFECACA) else MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isAlightImminent) Color(0xFECACA).copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        if (runtime.stageType == "arrived") "Kapat" else "Yolculuğu Bitir",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
