package com.taha.goit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.taha.goit.location.LocationHelper

@Composable
fun OnboardingScreen(
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentPage by rememberSaveable { mutableIntStateOf(0) }

    var hasLocation by remember {
        mutableStateOf(LocationHelper.hasPermission(context))
    }
    var hasNotification by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    // Ekran her görünür olduğunda veya dönüldüğünde izin durumunu güncelle
    LaunchedEffect(currentPage) {
        hasLocation = LocationHelper.hasPermission(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotification = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // Üst Çubuk: Noktalar ve Atla butonu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until 5) {
                        val active = i == currentPage
                        Box(
                            modifier = Modifier
                                .size(if (active) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                )
                        )
                    }
                }
                if (currentPage < 4) {
                    TextButton(onClick = onComplete) {
                        Text("Atla", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }

            // Sayfa İçeriği
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when (currentPage) {
                    0 -> OnboardingPagePermissions(
                        hasLocation = hasLocation,
                        hasNotification = hasNotification,
                        onRequestLocation = {
                            onRequestLocationPermission()
                            hasLocation = LocationHelper.hasPermission(context)
                        },
                        onRequestNotification = {
                            onRequestNotificationPermission()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                hasNotification = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            }
                        },
                        onOpenSettings = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }
                    )
                    1 -> OnboardingPageTrip()
                    2 -> OnboardingPageStops()
                    3 -> OnboardingPageRoutes()
                    4 -> OnboardingPageReady()
                }
            }

            // Alt Çubuk: Geri / İleri / Başla
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentPage > 0) {
                    OutlinedButton(
                        onClick = { currentPage-- },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Geri")
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                if (currentPage < 4) {
                    Button(
                        onClick = { currentPage++ },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("İleri")
                    }
                } else {
                    Button(
                        onClick = onComplete,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Başla", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingPagePermissions(
    hasLocation: Boolean,
    hasNotification: Boolean,
    onRequestLocation: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.DirectionsBus,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "GoIt'e Hoş Geldiniz",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Reklamsız, izleyicisiz Bursa ulaşım asistanınız. Başlamadan önce iki izne ihtiyacımız var:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        // Konum İzni
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Konum İzni", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("Yakındaki durakları ve canlı rehberliği sunar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (hasLocation) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, null, tint = Color(0xFF0F9D58), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("İzin Verildi", color = Color(0xFF0F9D58), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    FilledTonalButton(onClick = onRequestLocation, shape = RoundedCornerShape(8.dp)) {
                        Text("İzin Ver")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Bildirim İzni
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bildirim İzni", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("İniş alarmı ve hat yaklaşım uyarıları için", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (hasNotification) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, null, tint = Color(0xFF0F9D58), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("İzin Verildi", color = Color(0xFF0F9D58), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    FilledTonalButton(onClick = onRequestNotification, shape = RoundedCornerShape(8.dp)) {
                        Text("İzin Ver")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onOpenSettings) {
            Text("İzinler reddedildiyse ayarlardan aç", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "İzinler yalnızca cihazında kullanılır; hiçbir veri toplanmaz veya sunuculara iletilmez.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OnboardingPageTrip() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.AltRoute,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Yol Tarifi",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "A'dan B'ye: mekan adı yazın ya da Google Maps linkini veya koordinatı yapıştırın.\n\nKalkış için 'Konumumu kullan', varışı seçin, 'Rota Bul'a basın. Otobüs, yürüme ve aktarma alternatifleri anında karşınızda.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "İpucu: Haritalar Paylaşımı",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Google Maps'te bir yer bulduğunuzda Paylaş → GoIt seçin. Hedef otomatik dolar; anında rota oluşturabilirsiniz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun OnboardingPageStops() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Duraklar",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "En yakın duraklar, canlı varış dakikaları ve ücretler tek ekranda.\n\nDurağa dokunun: yaklaşan otobüsler, araç özellikleri (klima, engelli rampası) ve 'Buradan Git' ile hızlı rota planı.\n\nFavorilerinize ekleyin, yaklaşım bildirimlerini açın.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OnboardingPageRoutes() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.DirectionsBus,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Hatlar",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Bursa'daki 409 hattın tamamı: arayın, favorileyin, canlı takip edin.\n\nEn üstte en yakın durağınıza gelen hatlar listelenir. Hat detayında tüm duraklar, harita üzerinde hareket eden araçlar ve 'Sen burdasın' rozeti bulunur.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OnboardingPageReady() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Hazırsınız",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "• Google Maps linkini GoIt'e paylaş veya arama kutusuna yapıştır.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• 'İniş Alarmı' hedefe yaklaşınca titreşimle uyarır.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• 'Yolculuğu Başlat' ile sıradaki durak ve kalan durak sayısını canlı takip et.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
