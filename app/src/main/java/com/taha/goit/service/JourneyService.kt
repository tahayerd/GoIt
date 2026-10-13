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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.taha.goit.GoItApp
import com.taha.goit.MainActivity
import com.taha.goit.R
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
 * Canlı yolculuk asistanı (V3 — tam otomatik kentsel transit rehberlik):
 * - Aşamalandırılmış yolculuk modu (Yürüme -> Bekleme/ETA -> Araç/Durak sayımı -> İniş/Varış alarmları)
 * - Kullanıcı onayı GEREKMEZ: biniş, hız + duraktan uzaklaşma ile otomatik algılanır
 * - Durak sayacı tek yönlüdür (GPS zıplamasında asla geri saymaz)
 * - Geriye dönük uyumlu tek durak iniş alarmı (legacy mode)
 */
class JourneyService : Service(), LocationListener {

    // Y-15: Servis arka plan işleri için Dispatchers.Default
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // Y-4: Singleton TransitRepository kullanımı
    private val repository = TransitRepository.getInstance()
    private val json = Json { ignoreUnknownKeys = true }
    private var locationManager: LocationManager? = null

    // V3 Staged Mod Değişkenleri
    private var isStagedMode: Boolean = false
    private var payload: JourneyPayload? = null
    private var stages: List<JourneyStage> = emptyList()
    private var stageIndex: Int = 0
    private var initialWalkDistance: Double? = null
    private var hasAlertedCurrentStage: Boolean = false
    private var hasWarnedApproaching: Boolean = false
    private var waitEtaJob: Job? = null
    private var currentWaitEta: String? = null
    private var lastKnownLocation: Location? = null

    // O-4: Bildirim güncelleme throttling
    private var lastNotificationText: String? = null
    private var lastNotificationTime: Long = 0L

    // Otomatik biniş algılama (Bekle -> Araç geçişi)
    private var consecutiveFastFar: Int = 0     // >12 km/h VE duraktan >150 m (ardışık 2 fix = ~10 sn)
    private var consecutiveSlowFar: Int = 0     // >6 km/h VE duraktan >400 m (ardışık 6 fix = ~30 sn, yavaş trafik)
    private var lastFixForSpeed: Location? = null

    // Araçta: tek yönlü durak ilerlemesi (GPS zıplamasına dayanıklı)
    private var maxStopIdx: Int = -1

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

        /** V3 Aşamalı Yolculuk Başlatıcı (tam otomatik) */
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
                JourneyTracker.updateState(null)
                serviceScope.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_JOURNEY -> {
                val jsonStr = intent.getStringExtra(EXTRA_PAYLOAD)
                if (!jsonStr.isNullOrBlank()) {
                    try {
                        val parsed = json.decodeFromString<JourneyPayload>(jsonStr)
                        if (parsed.stages.isNotEmpty()) {
                            enterStagedMode(parsed)
                            return START_STICKY
                        }
                    } catch (e: Exception) {
                        Log.w("JourneyService", "Failed to parse JourneyPayload", e)
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
                    enterStagedMode(parsed)
                    return START_STICKY
                }
            } catch (e: Exception) {
                Log.w("JourneyService", "Failed to parse fallback JourneyPayload", e)
            }
        }

        // Y-14: Eşleşmeyen / boş intent durumunda sonsuz boş servis engeli
        if (!isStagedMode && destinationName.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun enterStagedMode(parsed: JourneyPayload) {
        isStagedMode = true
        payload = parsed
        stages = parsed.stages
        stageIndex = 0
        resetStageCounters()
        startStagedTracking()
    }

    /** Aşama geçişlerinde sıfırlanan tüm sayaçlar. */
    private fun resetStageCounters() {
        initialWalkDistance = null
        hasAlertedCurrentStage = false
        hasWarnedApproaching = false
        consecutiveFastFar = 0
        consecutiveSlowFar = 0
        maxStopIdx = -1
        lastFixForSpeed = null
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
        startForegroundCompat(buildNotification(notifText, isAlert = false))

        if (initialStage != null) {
            JourneyTracker.updateState(
                JourneyTracker.RuntimeState(
                    stageIndex = 0,
                    stageType = initialStage.type,
                    title = initialStage.title,
                    detail = initialStage.targetName,
                    progress = 0f
                )
            )
            if (initialStage.type == "wait") {
                startWaitEtaPolling(initialStage)
            }
        }
        requestLocationUpdates()
    }

    private fun startLegacyTracking() {
        startForegroundCompat(buildNotification("Yolculuk başladı • Hedef: $destinationName", isAlert = false))
        JourneyTracker.updateState(
            JourneyTracker.RuntimeState(
                stageIndex = 0,
                stageType = "ride",
                title = "İniş Takibi",
                detail = "Hedef: $destinationName",
                progress = 0f
            )
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
                    currentWaitEta = best?.let { formatEtaTime(it.realTime ?: it.passTime) }

                    val route = stage.routeCode ?: "Otobüs"
                    val etaPart = if (!currentWaitEta.isNullOrBlank() && currentWaitEta != "-") {
                        "$route hattı ~$currentWaitEta içinde geliyor"
                    } else {
                        "$route hattı bekleniyor"
                    }
                    val detail = "Durak: ${stage.targetName} • $etaPart"
                    JourneyTracker.updateState(
                        JourneyTracker.RuntimeState(
                            stageIndex = stageIndex,
                            stageType = "wait",
                            title = stage.title.ifEmpty { "$route hattını bekle" },
                            detail = detail,
                            progress = 0f,
                            waitEtaMinutes = currentWaitEta
                        )
                    )
                    updateNotification("$etaPart • Durak: ${stage.targetName}", isAlert = false)
                } catch (e: Exception) {
                    Log.w("JourneyService", "ETA polling error: ${e.message}")
                }
                delay(20_000L)
            }
        }
    }

    private fun advanceStage(reason: String? = null) {
        Log.d("JourneyService", "Advancing stage. Reason: $reason")
        stageIndex++
        resetStageCounters()
        waitEtaJob?.cancel()
        waitEtaJob = null

        if (stageIndex >= stages.size) {
            val destName = payload?.destinationName ?: destinationName.ifEmpty { "Hedef" }
            JourneyTracker.updateState(
                JourneyTracker.RuntimeState(
                    stageIndex = stageIndex,
                    stageType = "arrived",
                    title = "Hedefe ulaştın",
                    detail = destName,
                    progress = 1f
                )
            )
            sendArrivalAlert("İndin. Hedefe ulaştın • $destName")
            updateNotification("İndin. Hedefe ulaştın • $destName", isAlert = true)
            serviceScope.launch {
                delay(60_000L)
                stopTracking()
                JourneyTracker.updateState(null)
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
            JourneyTracker.updateState(
                JourneyTracker.RuntimeState(
                    stageIndex = stageIndex,
                    stageType = nextStage.type,
                    title = nextStage.title,
                    detail = nextStage.targetName,
                    progress = 0f
                )
            )
            updateNotification(nextStage.title, isAlert = false)
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
        } catch (e: SecurityException) {
            Log.e("JourneyService", "Konum izni eksik: ${e.message}")
        }
    }

    private fun stopTracking() {
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        waitEtaJob?.cancel()
        waitEtaJob = null
    }

    /**
     * İki fix arasındaki mesafeden veya cihazın raporladığı hızdan efektif km/h hesaplar.
     */
    private fun speedKmh(location: Location): Float {
        var spd = if (location.hasSpeed()) location.speed * 3.6f else 0f
        val prev = lastFixForSpeed
        if (prev != null && prev.time > 0 && location.time > prev.time) {
            val dtSec = (location.time - prev.time) / 1000f
            if (dtSec in 2f..30f) {
                val d = prev.distanceTo(location)
                val computedKmh = (d / dtSec) * 3.6f
                spd = maxOf(spd, computedKmh)
            }
        }
        lastFixForSpeed = location
        return spd
    }

    override fun onLocationChanged(location: Location) {
        lastKnownLocation = location

        if (!isStagedMode) {
            // Legacy Tek Durak Modu
            val results = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                destinationLat, destinationLng, results
            )
            val distance = results[0].toDouble()

            val distText = formatDistance(distance)
            val detail = "Kalan mesafe: $distText • $destinationName"
            JourneyTracker.updateState(
                JourneyTracker.RuntimeState(
                    stageIndex = 0,
                    stageType = "ride",
                    title = "İniş Takibi",
                    detail = detail,
                    distanceToNextMeters = distance,
                    progress = 0f
                )
            )

            // Y-17: SettingsManager ayarlarını kullan
            val settings = (application as? GoItApp)?.settingsManager
            val alertDist = (settings?.alightDistanceMeters ?: 250).toDouble()

            if (distance <= alertDist && !hasAlerted) {
                hasAlerted = true
                sendArrivalAlert("İniş durağına yaklaştın! ($distText) • $destinationName")
            } else {
                updateNotification(detail, isAlert = false)
            }
            return
        }

        // V3 Staged Mod
        val currentStage = stages.getOrNull(stageIndex) ?: return
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
                val initDist = initialWalkDistance ?: dist
                val progress = if (initDist > 0) ((initDist - dist) / initDist).toFloat().coerceIn(0f, 1f) else 0f
                val distText = formatDistance(dist)
                val estWalkMin = TransitRepository.walkMinutes(dist, (application as? GoItApp)?.settingsManager?.walkingSpeedProfile ?: "normal")
                val detail = "Kalan mesafe: $distText (~$estWalkMin dk) • ${currentStage.targetName}"

                JourneyTracker.updateState(
                    JourneyTracker.RuntimeState(
                        stageIndex = stageIndex,
                        stageType = "walk",
                        title = currentStage.title,
                        detail = detail,
                        distanceToNextMeters = dist,
                        progress = progress,
                        walkMinutes = estWalkMin
                    )
                )
                updateNotification("${currentStage.title} • $distText", isAlert = false)

                // Durağa/hedefe varış: ≤40 m
                if (dist <= 40.0) {
                    advanceStage("Yürüyüş tamamlandı")
                }
            }

            "wait" -> {
                val results = FloatArray(1)
                Location.distanceBetween(
                    location.latitude, location.longitude,
                    currentStage.targetLat, currentStage.targetLng, results
                )
                val distFromStop = results[0]
                val spd = speedKmh(location)

                val route = currentStage.routeCode ?: "Otobüs"
                val etaPart = if (!currentWaitEta.isNullOrBlank() && currentWaitEta != "-") {
                    "$route hattı ~$currentWaitEta içinde geliyor"
                } else {
                    "$route hattı bekleniyor"
                }
                val detail = "Durak: ${currentStage.targetName} • $etaPart"
                JourneyTracker.updateState(
                    JourneyTracker.RuntimeState(
                        stageIndex = stageIndex,
                        stageType = "wait",
                        title = currentStage.title.ifEmpty { "$route hattını bekle" },
                        detail = detail,
                        progress = 0f,
                        waitEtaMinutes = currentWaitEta
                    )
                )
                updateNotification("$etaPart • Durak: ${currentStage.targetName}", isAlert = false)

                // Otomatik biniş algılama (kullanıcı onayı gerektirmez):
                // 1) Hızlı hareket: >12 km/h VE duraktan >150 m uzakta — ardışık 2 fix (~10 sn)
                // 2) Yavaş trafik fallback: >6 km/h VE duraktan >400 m uzakta — ardışık 6 fix (~30 sn)
                if (spd > 12f && distFromStop > 150f) consecutiveFastFar++ else consecutiveFastFar = 0
                if (spd > 6f && distFromStop > 400f) consecutiveSlowFar++ else consecutiveSlowFar = 0

                if (consecutiveFastFar >= 2 || consecutiveSlowFar >= 6) {
                    advanceStage("Araca bindin")
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
                // Tek yönlü sayaç: GPS zıplamasında geriye düşmez, yalnızca ilerler
                val effectiveIdx = maxOf(maxStopIdx, nearestIdx)
                maxStopIdx = effectiveIdx
                val stopsRemaining = if (currentStage.stops.isNotEmpty()) {
                    (currentStage.stops.size - 1 - effectiveIdx).coerceAtLeast(0)
                } else 0

                val resAlight = FloatArray(1)
                Location.distanceBetween(
                    location.latitude, location.longitude,
                    currentStage.targetLat, currentStage.targetLng, resAlight
                )
                val distToAlight = resAlight[0].toDouble()

                val nextStopName = currentStage.stops.getOrNull(
                    (effectiveIdx + 1).coerceAtMost(currentStage.stops.size - 1)
                )?.name ?: currentStage.targetName
                val route = currentStage.routeCode ?: "Otobüs"
                val estMinutes = (stopsRemaining * 1.8).roundToInt().coerceAtLeast(1)
                val detail = "Sıradaki: $nextStopName • $stopsRemaining durak kaldı (~$estMinutes dk)"
                val progress = if (currentStage.stops.size > 1) {
                    (effectiveIdx.toFloat() / (currentStage.stops.size - 1)).coerceIn(0f, 1f)
                } else 0f

                val passedList = if (currentStage.stops.isNotEmpty()) {
                    currentStage.stops.take(effectiveIdx).map { it.name }
                } else emptyList()
                val upcomingList = if (currentStage.stops.isNotEmpty()) {
                    currentStage.stops.drop(effectiveIdx + 1).map { it.name }
                } else emptyList()

                JourneyTracker.updateState(
                    JourneyTracker.RuntimeState(
                        stageIndex = stageIndex,
                        stageType = "ride",
                        title = currentStage.title.ifEmpty { "$route ile git" },
                        detail = detail,
                        stopsRemaining = stopsRemaining,
                        distanceToNextMeters = distToAlight,
                        progress = progress,
                        passedStops = passedList,
                        currentStopName = nextStopName,
                        upcomingStops = upcomingList,
                        estimatedMinutesRemaining = estMinutes
                    )
                )
                updateNotification("$route • $detail", isAlert = false)

                // "Yaklaşıyorsun" erken uyarısı
                if (stopsRemaining == 2 && !hasWarnedApproaching) {
                    hasWarnedApproaching = true
                    updateNotification("Yaklaşıyorsun • 2 durak kaldı • ${currentStage.targetName}", isAlert = false)
                }

                // Y-17: SettingsManager iniş alarmı eşiği
                val settings = (application as? GoItApp)?.settingsManager
                val stopThreshold = settings?.alightStopsThreshold ?: 1
                val distThreshold = (settings?.alightDistanceMeters ?: 250).toDouble()

                // "Hazır ol!" iniş alarmı — titreşimli
                if ((stopsRemaining <= stopThreshold || distToAlight <= distThreshold) && !hasAlertedCurrentStage) {
                    hasAlertedCurrentStage = true
                    sendArrivalAlert("Hazır ol! Sıradaki durak iniş durağın • ${currentStage.targetName}")
                }

                // Otomatik iniş: iniş durağına ≤100 m (veya son durak + ≤150 m)
                if (distToAlight <= 100.0 || (currentStage.stops.isNotEmpty() && effectiveIdx >= currentStage.stops.size - 1 && distToAlight <= 150.0)) {
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
        VibrationHelper.vibrateAlert(this) // O-23

        val alert = buildAlert(alertText)
        nm.notify(ALERT_NOTIFICATION_ID, alert)
        updateNotification(alertText, isAlert = true)
    }

    // O-4: Bildirim güncelleme throttling (en az 15 sn aralık veya metin değişimi)
    private fun updateNotification(text: String, isAlert: Boolean) {
        val now = System.currentTimeMillis()
        if (!isAlert && text == lastNotificationText && now - lastNotificationTime < 15_000L) {
            return
        }
        lastNotificationText = text
        lastNotificationTime = now
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

        val builder = NotificationCompat.Builder(this, if (isAlert) ALERT_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // Y-16 & D-1
            .setColor(0xFF1976D2.toInt())
            .setContentTitle(if (isAlert) "GoIt İniş Uyarısı!" else "GoIt Canlı Yolculuk")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(!isAlert)
            .setContentIntent(contentIntent)
            .setPriority(if (isAlert) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Yolculuğu Bitir", stopIntent)
        return builder.build()
    }

    private fun buildAlert(text: String): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // Y-16 & D-1
            .setColor(0xFFD32F2F.toInt())
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
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notification_channel_desc) }
            val alert = NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.alert_channel_desc)
                enableVibration(true)
                vibrationPattern = VibrationHelper.ALERT_WAVEFORM
            }
            nm.createNotificationChannel(journey)
            nm.createNotificationChannel(alert)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        JourneyTracker.updateState(null)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
