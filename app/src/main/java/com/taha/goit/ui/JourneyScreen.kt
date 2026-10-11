package com.taha.goit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taha.goit.service.JourneyTracker

/**
 * Canlı Yolculuk Rehberi (Overlay — V3 tam otomatik, Moovit tarzı):
 * Haritanın üzerinde yarı saydam kart şeklinde aşama aşama kılavuzluk eder:
 * - Yürüyüş mesafesi & süresi
 * - Durak bekleme & canlı hat ETA'sı
 * - Araçta kalan durak sayısı (tek yönlü sayaç)
 * - Biniş/iniş otomatik algılanır; yaklaşınca "İn!" uyarı şeridi
 * - Tek aksiyon: "Yolculuğu Bitir"
 */
@Composable
fun JourneyScreen(
    onStopJourney: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by JourneyTracker.state.collectAsState()
    val runtime = state ?: return

    val isAlightImminent = runtime.stageType == "ride" &&
        (runtime.stopsRemaining ?: 99) <= 1

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isAlightImminent) Color(0xFF7F1D1D).copy(alpha = 0.95f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // İniş yaklaştı uyarı şeridi (kırmızı, dikkat çekici)
                if (isAlightImminent) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.25f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                                "İn! Sıradaki durak iniş durağın",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFCA5A5)
                            )
                        }
                    }
                }

                // Üst şerit: Aşama ikonu ve türü
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
                            modifier = Modifier.size(36.dp)
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
                                    modifier = Modifier.size(20.dp)
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

                    if (runtime.stopsRemaining != null && runtime.stopsRemaining > 0) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isAlightImminent) Color(0xFFEF4444).copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "${runtime.stopsRemaining} durak kaldı",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isAlightImminent) Color(0xFFFCA5A5) else MaterialTheme.colorScheme.primary
                            )
                        }
                    } else if (runtime.stageType == "wait") {
                        // Otomatik biniş algılama göstergesi
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF16A34A).copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "otomatik algılanır",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF4ADE80)
                            )
                        }
                    }
                }

                // Aşama Başlığı ve Detayı
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = runtime.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isAlightImminent) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = runtime.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isAlightImminent) Color(0xFECACA) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // İlerleme çubuğu
                if (runtime.progress > 0f) {
                    LinearProgressIndicator(
                        progress = runtime.progress.coerceIn(0f, 1f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (isAlightImminent) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                // Tek aksiyon: Yolculuğu Bitir (biniş/iniş tamamen otomatik)
                OutlinedButton(
                    onClick = onStopJourney,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
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
