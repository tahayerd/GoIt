package com.taha.goit.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.taha.goit.data.api.TransitRepository
import com.taha.goit.ui.formatDistance
import com.taha.goit.ui.formatEtaTime
import com.taha.goit.ui.parseEtaSeconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Canlı yolculuk asistanı (V2):
 * - Aşamalandırılmış yolculuk modu (Yürüme -> Bekleme/ETA -> Araç/Durak sayımı -> İniş/Varış alarmları)
 * - Geriye dönük uyumlu tek durak iniş alarmı (legacy mode)
 */
class JourneyService : Service(), LocationListener {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val repository = TransitRepository()
    private val json = Json { ignoreUnknownKeys = true }
    private var locationManager: LocationManager? = null

    // V2 Staged Mod Değişkenleri
    private var isStagedMode: Boolean = false
    private var payload: JourneyPayload? = null
    private var stages: List<JourneyStage> = emptyList()
    private var stageIndex: Int = 0
    private var initialWalkDistance: Double? = null
    private var hasAlertedCurrentStage: Boolean = false
    private var hasWarnedTwoStops: Boolean = false
    private var consecutiveSpeedTriggers: Int = 0
    private var waitEtaJob: Job? = null
    private var currentWaitEta: String? = null
    private var lastKnownLocation: Location? = null

    // Legacy Tek Durak Modu Değişkenleri
    private var destinationLat: Double = 0.0
    private var destinationLng: Double = 0.0
    private var destinationName: String = ""
    private var hasAlerted: Boolean = false

    companion object {
        const val CHANNEL_ID = "goit_journey_channel"
        const val ALERT_CHANNEL_ID = "goit_alert_channel"
        const val NOTIFICATION_ID = 4201
        const val ALERT_NOTIFICATION_ID = 4202

        const val ACTION_START = "com.taha.goit.action.START_JOURNEY"
        const val ACTION_START_JOURNEY = "com.taha.goit.action.START_JOURNEY_STAGED"
        const val ACTION_STOP = "com.taha.goit.action.STOP_JOURNEY"
        const val ACTION_ADVANCE = "com.taha.goit.action.ADVANCE_JOURNEY"
        const val EXTRA_PAYLOAD = "journeyPayload"

        /** Legacy iniş alarmı: Duraklar sekmesinden çağrılır */
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

        /** V2 Aşamalı Yolculuk Başlatıcı */
        fun startJourney(context: Context, payloadJson: String) {
            val intent = Intent(context, JourneyService::class.java).apply {
                action = ACTION_START_JOURNEY
                putExtra(EXTRA_PAYLOAD, payloadJson)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Kullanıcı "Bindim ▶" dediğinde bir sonraki aşamaya (ride) geçirir */
        fun advance(context: Context) {
            val intent = Intent(context, JourneyService::class.java).apply {
                action = ACTION_ADVANCE
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, JourneyService::class.java).apply {
                action = ACTION_STOP
            }
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
            ACTION_STOP -> {
                stopTracking()
                JourneyTracker.state.value = null
                serviceScope.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ADVANCE -> {
                if (isStagedMode && stageIndex < stages.size && stages[stageIndex].type == "wait") {
                    advanceStage("Araca bindin")
                }
                return START_STICKY
            }
            ACTION_START_JOURNEY -> {
                val jsonStr = intent.getStringExtra(EXTRA_PAYLOAD)
                if (!jsonStr.isNullOrBlank()) {
                    try {
                        val parsed = json.decodeFromString<JourneyPayload>(jsonStr)
                        if (parsed.stages.isNotEmpty()) {
                            isStagedMode = true
                            payload = parsed
                            stages = parsed.stages
                            stageIndex = 0
                            initialWalkDistance = null
                            hasAlertedCurrentStage = false
                            hasWarnedTwoStops = false
                            consecutiveSpeedTriggers = 0
                            currentWaitEta = null
                            startStagedTracking()
                            return START_STICKY
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            ACTION_START -> {
                isStagedMode = false
                destinationLat = intent.getDoubleExtra("lat", 0.0)
                destinationLng = intent.getDoubleExtra("lng", 0.0)
                destinationName = intent.getStringExtra("name") ?: "Hedef Durak"
                hasAlerted = false
                startLegacyTracking()
                return START_STICKY
            }
        }

        // Ekstra kontrol: intent ACTION_START_JOURNEY olmasa bile EXTRA_PAYLOAD içeriyorsa
        val jsonStr = intent?.getStringExtra(EXTRA_PAYLOAD)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val parsed = json.decodeFromString<JourneyPayload>(jsonStr)
                if (parsed.stages.isNotEmpty()) {
                    isStagedMode = true
                    payload = parsed
                    stages = parsed.stages
                    stageIndex = 0
                    initialWalkDistance = null
                    hasAlertedCurrentStage = false
                    hasWarnedTwoStops = false
                    consecutiveSpeedTriggers = 0
                    currentWaitEta = null
                    startStagedTracking()
                    return START_STICKY
                }
            } catch (_: Exception) {
            }
        }

        return START_STICKY
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startStagedTracking() {
        val initialStage = stages.getOrNull(0)
        val notifText = initialStage?.title ?: "Yolculuk başladı"
        startForegroundCompat(buildNotification(notifText, isAlert = false, isWait = initialStage?.type == "wait"))

        if (initialStage != null) {
            JourneyTracker.state.value = JourneyTracker.RuntimeState(
                stageIndex = 0,
                stageType = initialStage.type,
                title = initialStage.title,
                detail = initialStage.targetName,
                progress = 0f
            )
            if (initialStage.type == "wait") {
                startWaitEtaPolling(initialStage)
            }
        }
        requestLocationUpdates()
    }

    private fun startLegacyTracking() {
        startForegroundCompat(buildNotification("Yolculuk başladı • Hedef: $destinationName", isAlert = false, isWait = false))
        JourneyTracker.state.value = JourneyTracker.RuntimeState(
            stageIndex = 0,
            stageType = "ride",
            title = "İniş Takibi",
            detail = "Hedef: $destinationName",
            progress = 0f
        )
        requestLocationUpdates()
    }

    private fun startWaitEtaPolling(stage: JourneyStage) {
        waitEtaJob?.cancel()
        val stopId = stage.stopId ?: return
        waitEtaJob = serviceScope.launch {
            while (isActive) {
                try {
                    val etas = repository.getStationRemainingTime(stopId)
                    val targetCode = stage.routeCode
                    val matched = if (targetCode != null) {
                        etas.filter { it.routeCode.equals(targetCode, ignoreCase = true) }
                    } else {
                        etas
                    }
                    val best = matched.minByOrNull { parseEtaSeconds(it.realTime ?: it.passTime) }
                    val etaStr = best?.let { formatEtaTime(it.realTime ?: it.passTime) }
                    currentWaitEta = etaStr

                    val detail = buildString {
                        append("🚏 ")
                        append(stage.targetName)
                        if (!etaStr.isNullOrBlank() && etaStr != "-") {
                            append(" • ~$etaStr")
                        }
                    }
                    JourneyTracker.state.value = JourneyTracker.RuntimeState(
                        stageIndex = stageIndex,
                        stageType = "wait",
                        title = stage.title.ifEmpty { "${stage.routeCode ?: "Otobüs"} hattını bekle" },
                        detail = detail,
                        progress = 0f
                    )
                    updateNotification(
                        text = "${stage.title} • $detail",
                        isAlert = false,
                        isWait = true
                    )
                } catch (_: Exception) {
                }
                delay(20_000L)
            }
        }
    }

    private fun advanceStage(reason: String? = null) {
        stageIndex++
        initialWalkDistance = null
        hasAlertedCurrentStage = false
        hasWarnedTwoStops = false
        consecutiveSpeedTriggers = 0
        waitEtaJob?.cancel()
        waitEtaJob = null
        currentWaitEta = null

        if (stageIndex >= stages.size) {
            val destName = payload?.destinationName ?: destinationName.ifEmpty { "Hedef" }
            JourneyTracker.state.value = JourneyTracker.RuntimeState(
                stageIndex = stageIndex,
                stageType = "arrived",
                title = "Hedefe ulaştın! 🎉",
                detail = destName,
                progress = 1f
            )
            sendArrivalAlert("Hedefe ulaştın! 🎉 • $destName")
            updateNotification("Hedefe ulaştın! 🎉 • $destName", isAlert = true, isWait = false)
            serviceScope.launch {
                delay(60_000L)
                stopTracking()
                JourneyTracker.state.value = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }

        val nextStage = stages[stageIndex]
        if (nextStage.type == "wait") {
            startWaitEtaPolling(nextStage)
        }

        lastKnownLocation?.let { onLocationChanged(it) } ?: run {
            JourneyTracker.state.value = JourneyTracker.RuntimeState(
                stageIndex = stageIndex,
                stageType = nextStage.type,
                title = nextStage.title,
                detail = nextStage.targetName,
                progress = 0f
            )
            updateNotification(nextStage.title, isAlert = false, isWait = nextStage.type == "wait")
        }
    }

    private fun requestLocationUpdates() {
        val lm = locationManager ?: return
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                lm.getLastKnownLocation(p)?.let { onLocationChanged(it) }
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f, this)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this)
            }
        } catch (_: SecurityException) {
            updateNotification("Konum izni olmadan takip edilemiyor.", isAlert = false, isWait = false)
        } catch (_: Exception) {
        }
    }

    private fun stopTracking() {
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
        waitEtaJob?.cancel()
        waitEtaJob = null
    }

    override fun onLocationChanged(location: Location) {
        lastKnownLocation = location

        if (!isStagedMode) {
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
                    sendArrivalAlert("İniş zamanı! $destinationName durağındasınız.")
                }
                hasAlerted -> {
                    updateNotification("İniş zamanı! $destinationName durağına hoş geldiniz.", true, isWait = false)
                }
                else -> {
                    val etaMin = (distanceMeters / 1000.0 / 22.0 * 60.0).roundToInt()
                    updateNotification(
                        "Hedefe ${distanceMeters.roundToInt()} m kaldı (~$etaMin dk) • $destinationName",
                        false,
                        isWait = false
                    )
                }
            }
            JourneyTracker.state.value = JourneyTracker.RuntimeState(
                stageIndex = 0,
                stageType = "ride",
                title = "İniş Takibi",
                detail = "Hedef: $destinationName • ${distanceMeters.roundToInt()} m",
                distanceToNextMeters = distanceMeters.toDouble(),
                progress = 0f
            )
            return
        }

        if (stageIndex >= stages.size) return
        val currentStage = stages[stageIndex]

        when (currentStage.type) {
            "walk" -> {
                val results = FloatArray(1)
                Location.distanceBetween(
                    location.latitude, location.longitude,
                    currentStage.targetLat, currentStage.targetLng, results
                )
                val dist = results[0].toDouble()
                if (initialWalkDistance == null || dist > (initialWalkDistance ?: 0.0)) {
                    initialWalkDistance = dist
                }
                val etaMin = (dist / 80.0).roundToInt().coerceAtLeast(1)
                val detail = "${formatDistance(dist)} (~$etaMin dk)"
                val progress = if ((initialWalkDistance ?: 0.0) > 0.0) {
                    ((1.0 - (dist / initialWalkDistance!!)).toFloat()).coerceIn(0f, 1f)
                } else 0f

                JourneyTracker.state.value = JourneyTracker.RuntimeState(
                    stageIndex = stageIndex,
                    stageType = "walk",
                    title = currentStage.title.ifEmpty { "${currentStage.targetName} yürü" },
                    detail = detail,
                    distanceToNextMeters = dist,
                    progress = progress
                )
                updateNotification("${currentStage.title} • $detail", isAlert = false, isWait = false)

                if (dist <= 60.0) {
                    advanceStage("🚏 Durağa ulaştın")
                }
            }

            "wait" -> {
                val results = FloatArray(1)
                Location.distanceBetween(
                    location.latitude, location.longitude,
                    currentStage.targetLat, currentStage.targetLng, results
                )
                val distFromStop = results[0]
                val speedKmh = if (location.hasSpeed()) (location.speed * 3.6f) else 0f

                val detail = buildString {
                    append("🚏 ")
                    append(currentStage.targetName)
                    if (!currentWaitEta.isNullOrBlank() && currentWaitEta != "-") {
                        append(" • ~$currentWaitEta")
                    }
                }
                JourneyTracker.state.value = JourneyTracker.RuntimeState(
                    stageIndex = stageIndex,
                    stageType = "wait",
                    title = currentStage.title.ifEmpty { "${currentStage.routeCode ?: "Otobüs"} hattını bekle" },
                    detail = detail,
                    progress = 0f
                )
                updateNotification("${currentStage.title} • $detail", isAlert = false, isWait = true)

                // Otomatik ilerleme: hız > 8 km/h VE duraktan > 150 m uzaklaşma (10 sn = 2 kontrol)
                if (speedKmh > 8f && distFromStop > 150f) {
                    consecutiveSpeedTriggers++
                    if (consecutiveSpeedTriggers >= 2) {
                        advanceStage("Otobüs hareket etti")
                    }
                } else {
                    consecutiveSpeedTriggers = 0
                }
            }

            "ride" -> {
                var nearestIdx = 0
                var minDist = Double.MAX_VALUE
                currentStage.stops.forEachIndexed { idx, s ->
                    val res = FloatArray(1)
                    Location.distanceBetween(location.latitude, location.longitude, s.lat, s.lng, res)
                    val d = res[0].toDouble()
                    if (d < minDist) {
                        minDist = d
                        nearestIdx = idx
                    }
                }
                val stopsRemaining = if (currentStage.stops.isNotEmpty()) {
                    (currentStage.stops.size - 1 - nearestIdx).coerceAtLeast(0)
                } else 0

                val resAlight = FloatArray(1)
                Location.distanceBetween(
                    location.latitude, location.longitude,
                    currentStage.targetLat, currentStage.targetLng, resAlight
                )
                val distToAlight = resAlight[0].toDouble()

                val nextStopName = currentStage.stops.getOrNull(nearestIdx + 1)?.name ?: currentStage.targetName
                val detail = "Sıradaki: $nextStopName • $stopsRemaining durak kaldı"
                val progress = if (currentStage.stops.size > 1) {
                    (nearestIdx.toFloat() / (currentStage.stops.size - 1)).coerceIn(0f, 1f)
                } else 0f

                JourneyTracker.state.value = JourneyTracker.RuntimeState(
                    stageIndex = stageIndex,
                    stageType = "ride",
                    title = currentStage.title.ifEmpty { "${currentStage.routeCode ?: "Otobüs"} ile git" },
                    detail = detail,
                    stopsRemaining = stopsRemaining,
                    distanceToNextMeters = distToAlight,
                    progress = progress
                )
                updateNotification("${currentStage.title} • $detail", isAlert = false, isWait = false)

                if (stopsRemaining == 2 && !hasWarnedTwoStops) {
                    hasWarnedTwoStops = true
                    updateNotification("2 durak kaldı • ${currentStage.targetName}", isAlert = false, isWait = false)
                }

                if ((distToAlight <= 250.0 || stopsRemaining <= 1) && !hasAlertedCurrentStage) {
                    hasAlertedCurrentStage = true
                    sendArrivalAlert("İn! Sıradaki durak: ${currentStage.targetName}")
                }

                if (distToAlight <= 100.0 || (currentStage.stops.isNotEmpty() && nearestIdx >= currentStage.stops.size - 1 && distToAlight <= 150.0)) {
                    advanceStage("İndin: ${currentStage.targetName}")
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun sendArrivalAlert(alertText: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))

        val alert = buildAlert(alertText)
        nm.notify(ALERT_NOTIFICATION_ID, alert)
        updateNotification(alertText, isAlert = true, isWait = false)
    }

    private fun updateNotification(text: String, isAlert: Boolean, isWait: Boolean = false) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, isAlert, isWait))
    }

    private fun buildNotification(text: String, isAlert: Boolean, isWait: Boolean = false): android.app.Notification {
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

        val builder = NotificationCompat.Builder(this, if (isAlert) ALERT_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(if (isAlert) "GoIt İniş Uyarısı!" else "GoIt Yolculuk Asistanı")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(!isAlert)
            .setContentIntent(contentIntent)
            .setPriority(if (isAlert) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)

        if (isWait) {
            val advanceIntent = PendingIntent.getService(
                this, 2,
                Intent(this, JourneyService::class.java).apply { action = ACTION_ADVANCE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Bindim ▶", advanceIntent)
        }

        builder.addAction(0, "Yolculuğu Bitir", stopIntent)
        return builder.build()
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
        JourneyTracker.state.value = null
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
