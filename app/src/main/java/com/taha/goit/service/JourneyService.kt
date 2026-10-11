package com.taha.goit.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.taha.goit.MainActivity
import kotlin.math.roundToInt

/**
 * Canlı yolculuk asistanı: kullanıcının GPS konumunu izler, hedef durağa kalan mesafeyi
 * hesaplar ve 250 m'den yaklaşıldığında titreşimli "İniş zamanı!" bildirimi gönderir.
 */
class JourneyService : Service(), LocationListener {

    private var destinationLat: Double = 0.0
    private var destinationLng: Double = 0.0
    private var destinationName: String = ""
    private var hasAlerted: Boolean = false
    private var locationManager: LocationManager? = null

    companion object {
        const val CHANNEL_ID = "goit_journey_channel"
        const val ALERT_CHANNEL_ID = "goit_alert_channel"
        const val NOTIFICATION_ID = 4201
        const val ALERT_NOTIFICATION_ID = 4202
        const val ACTION_START = "com.taha.goit.action.START_JOURNEY"
        const val ACTION_STOP = "com.taha.goit.action.STOP_JOURNEY"

        fun start(context: Context, lat: Double, lng: Double, stopName: String) {
            val intent = Intent(context, JourneyService::class.java).apply {
                action = ACTION_START
                putExtra("lat", lat)
                putExtra("lng", lng)
                putExtra("name", stopName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, JourneyService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                destinationLat = intent.getDoubleExtra("lat", 0.0)
                destinationLng = intent.getDoubleExtra("lng", 0.0)
                destinationName = intent.getStringExtra("name") ?: "Hedef Durak"
                hasAlerted = false
                startTracking()
            }
            ACTION_STOP -> {
                stopTracking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startTracking() {
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Yolculuk başladı • Hedef: $destinationName", false)
        )
        requestLocationUpdates()
    }

    private fun requestLocationUpdates() {
        val lm = locationManager ?: return
        try {
            // Önce son bilinen konumu kullan
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                lm.getLastKnownLocation(p)?.let { onLocationChanged(it) }
            }
            // Canlı güncellemeler: 5 saniye / 10 metre
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f, this)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this)
            }
        } catch (_: SecurityException) {
            // İzin yoksa sessizce çık
            updateNotification("Konum izni olmadan takip edilemiyor.", false)
        } catch (_: Exception) {
        }
    }

    private fun stopTracking() {
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        if (destinationLat == 0.0 && destinationLng == 0.0) return
        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude,
            destinationLat, destinationLng, results
        )
        val distanceMeters = results[0]

        when {
            distanceMeters < 250 && !hasAlerted -> {
                hasAlerted = true
                sendArrivalAlert()
            }
            hasAlerted -> {
                updateNotification("İniş zamanı! $destinationName durağına hoş geldiniz.", true)
            }
            else -> {
                val etaMin = (distanceMeters / 1000.0 / 22.0 * 60.0).roundToInt() // ~22 km/s ortalama
                updateNotification(
                    "Hedefe ${distanceMeters.roundToInt()} m kaldı (~$etaMin dk) • $destinationName",
                    false
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun sendArrivalAlert() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Titreşim
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))

        val alert = buildAlert("İniş zamanı! $destinationName durağındasınız.")
        nm.notify(ALERT_NOTIFICATION_ID, alert)

        updateNotification("İniş zamanı! $destinationName", true)
    }

    private fun updateNotification(text: String, isAlert: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, isAlert))
    }

    private fun buildNotification(text: String, isAlert: Boolean): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, JourneyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, if (isAlert) ALERT_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(if (isAlert) "GoIt İniş Uyarısı!" else "GoIt Yolculuk Asistanı")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(!isAlert)
            .setContentIntent(contentIntent)
            .addAction(0, "Yolculuğu Bitir", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun buildAlert(text: String): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("GoIt • İniş Zamanı!")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val journey = NotificationChannel(
                CHANNEL_ID,
                "Yolculuk Asistanı",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Canlı yolculuk takibi bildirimi" }
            val alert = NotificationChannel(
                ALERT_CHANNEL_ID,
                "İniş Uyarısı",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "İniş zamanı uyarıları"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            }
            nm.createNotificationChannel(journey)
            nm.createNotificationChannel(alert)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
