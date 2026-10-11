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
import androidx.compose.material.icons.filled.PlayArrow
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
 * Canlı Yolculuk Rehberi (Overlay):
 * Haritanın üzerinde yarı saydam kart şeklinde aşama aşama kılavuzluk eder:
 * - Yürüyüş mesafesi & süresi
 * - Durak bekleme & canlı hat ETA'sı
 * - Araçta kalan durak sayısı
 * - "Bindim ▶" ve "Yolculuğu Bitir" aksiyonları
 */
@Composable
fun JourneyScreen(
    onStopJourney: () -> Unit,
    onAdvanceJourney: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by JourneyTracker.state.collectAsState()
    val runtime = state ?: return

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
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                                    tint = when (runtime.stageType) {
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
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (runtime.stopsRemaining != null && runtime.stopsRemaining > 0) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "${runtime.stopsRemaining} durak kaldı",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = runtime.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                // Butonlar
                if (runtime.stageType == "wait") {
                    Button(
                        onClick = onAdvanceJourney,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF16A34A),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Bindim ▶", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onStopJourney,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
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
