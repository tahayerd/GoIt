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

/**
 * Canlı yolculuk asistanı (V4 — tam otomatik rehberlik):
 * - Aşama makinesi ve kararlar [JourneyEngine] içindedir (Android bağımsız, birim testli).
 * - Bu servis yalnızca konum dinler, canlı ETA sorar, bildirim/titreşim üretir.
 * - Biniş ve iniş OTOMATİK algılanır; kullanıcı onayı gerekmez.
 * - Süreç öldürülüp servis yeniden başlatılırsa yolculuk kayıtlı payload ile sürdürülür.
 * - Geriye dönük uyumlu tek durak iniş alarmı (legacy mode) korunur.
 */
class JourneyService : Service(), LocationListener {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val repository = TransitRepository.getInstance()
    private val json = Json { ignoreUnknownKeys = true }
    private var locationManager: LocationManager? = null

    // Aşamalı mod
    private var isStagedMode: Boolean = false
    private var engine: JourneyEngine? = null
    private var waitEtaJob: Job? = null
    private var finishJob: Job? = null
    private var lastKnownLocation: Location? = null
    private var lastFixForSpeed: Location? = null
    private var alertsEnabled: Boolean = true
    private var arrivalAnnounced: Boolean = false

    // Bildirim güncelleme throttling (O-4)
    private var lastNotificationText: String? = null
    private var lastNotificationTime: Long = 0L

    // Legacy tek durak modu
    private var destinationLat: Double = 0.0
    private var destinationLng: Double = 0.0
    private var destinationName: String = ""
    private var hasAlerted: Boolean = false

    companion object {
        private const val TAG = "JourneyService"

        const val CHANNEL_ID = "goit_journey_channel"
        const val ALERT_CHANNEL_ID = "goit_alert_channel"
        const val NOTIFICATION_ID = 4201
        const val ALERT_NOTIFICATION_ID = 4202

        const val ACTION_START = "com.taha.goit.action.START_JOURNEY"
        const val ACTION_START_JOURNEY = "com.taha.goit.action.START_JOURNEY_STAGED"
        const val ACTION_STOP = "com.taha.goit.action.STOP_JOURNEY"
        const val ACTION_TOGGLE_ALERTS = "com.taha.goit.action.TOGGLE_ALERTS"
        const val EXTRA_PAYLOAD = "journeyPayload"

        /** Canlı ETA sorgu aralığı. */
        private const val ETA_POLL_MS = 20_000L

        /** Yolculuk bitti bildiriminden sonra otomatik kapanma süresi. */
        private const val FINISH_LINGER_MS = 60_000L

        /** Son bilinen konum bu yaştan eskiyse başlangıçta kullanılmaz. */
        private const val STALE_FIX_MAX_AGE_MS = 120_000L

        private const val PREFS = "goit_active_journey"
        private const val KEY_PAYLOAD = "payload"

        /** Legacy iniş alarmı: Duraklar sekmesinden çağrılır */
        fun start(context: Context, lat: Double, lng: Double, stopName: String) {
            val intent = Intent(context, JourneyService::class.java).apply {
                action = ACTION_START
                putExtra("lat", lat)
                putExtra("lng", lng)
                putExtra("name", stopName)
            }
            startCompat(context, intent)
        }

        /** Aşamalı yolculuk başlatıcı (tam otomatik) */
        fun startJourney(context: Context, payloadJson: String) {
            val intent = Intent(context, JourneyService::class.java).apply {
                action = ACTION_START_JOURNEY
                putExtra(EXTRA_PAYLOAD, payloadJson)
            }
            startCompat(context, intent)
        }

        /** Yolculuğu durdurur; servis çalışmıyorsa da HUD temizlenir. */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, JourneyService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stopService failed: ${e.message}")
            }
            JourneyTracker.updateState(null)
        }

        private fun startCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
                stopJourney()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_ALERTS -> {
                if (engine == null) {
                    // Yolculuk yokken alarm düğmesine basıldıysa servisi ayakta tutma
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                alertsEnabled = !alertsEnabled
                engine?.let { applyDecision(it.snapshot(), forceNotification = true) }
                return START_STICKY
            }
            ACTION_START_JOURNEY -> {
                val jsonStr = intent.getStringExtra(EXTRA_PAYLOAD)
                val parsed = jsonStr?.let { raw ->
                    runCatching { json.decodeFromString<JourneyPayload>(raw) }
                        .onFailure { Log.w(TAG, "JourneyPayload çözümlenemedi", it) }
                        .getOrNull()
                }
                if (parsed != null && parsed.stages.isNotEmpty()) {
                    persistPayload(jsonStr)
                    alertsEnabled = true
                    return startStaged(parsed)
                }
                Log.w(TAG, "Geçersiz veya boş yolculuk payload'ı; servis başlatılmadı")
                notifyStartFailure()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                isStagedMode = false
                engine = null
                destinationLat = intent.getDoubleExtra("lat", 0.0)
                destinationLng = intent.getDoubleExtra("lng", 0.0)
                destinationName = intent.getStringExtra("name") ?: "Hedef Durak"
                hasAlerted = false
                startLegacyTracking()
                return START_STICKY
            }
        }

        // Null/eşleşmeyen intent: sistem servisi yeniden başlattıysa yolculuğu sürdür.
        val restored = restorePayload()
        if (restored != null) {
            Log.i(TAG, "Yolculuk kayıtlı payload ile sürdürülüyor")
            return startStaged(restored)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ Aşamalı mod

    private fun startStaged(parsed: JourneyPayload): Int {
        val settings = (application as? GoItApp)?.settingsManager
        val engineSettings = JourneySettings(
            alightStopsThreshold = settings?.alightStopsThreshold ?: 1,
            alightDistanceMeters = settings?.alightDistanceMeters ?: 250,
            walkingSpeedProfile = settings?.walkingSpeedProfile ?: "normal"
        )

        isStagedMode = true
        arrivalAnnounced = false
        finishJob?.cancel()
        finishJob = null
        val eng = JourneyEngine(parsed, engineSettings)
        engine = eng
        lastFixForSpeed = null

        startForegroundCompat(buildNotification(stageStartText(eng), isAlert = false))
        applyDecision(eng.snapshot(), forceNotification = true)

        if (eng.currentStage?.type == "wait") startWaitEtaPolling()
        requestLocationUpdates()
        return START_STICKY
    }

    private fun stageStartText(eng: JourneyEngine): String {
        val stage = eng.currentStage ?: return "Yolculuk başladı"
        return when (stage.type) {
            "walk" -> "${stage.title} • ${stage.targetName}"
            "wait" -> "${stage.routeCode ?: "Otobüs"} hattı bekleniyor • ${stage.targetName}"
            else -> "${stage.title} • İniş: ${stage.targetName}"
        }
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

    /** Durakta bekleme aşamasında canlı ETA döngüsü; aşama değişince kendiliğinden biter. */
    private fun startWaitEtaPolling() {
        if (waitEtaJob?.isActive == true) return
        waitEtaJob = serviceScope.launch {
            while (isActive) {
                val stage = engine?.currentStage
                if (stage == null || stage.type != "wait") break
                refreshEta(stage)
                delay(ETA_POLL_MS)
            }
        }
    }

    private suspend fun refreshEta(stage: JourneyStage) {
        val stopId = stage.stopId ?: return
        try {
            val etas = repository.getStationRemainingTime(stopId)
            if (etas.isEmpty()) {
                engine?.updateEta(null)
                engine?.let { applyDecision(it.snapshot()) }
                return
            }
            val best = pickBestEta(etas, stage.routeCode)
            if (best == null) {
                engine?.updateEta(null)
            } else {
                val raw = best.realTime?.takeIf { it.isNotBlank() && it != "-" } ?: best.passTime
                val text = formatEtaTime(raw)
                val eta = LiveEta(
                    minutes = parseEtaSeconds(raw).takeIf { it != Int.MAX_VALUE }?.let { it / 60 },
                    text = text,
                    updatedAtMs = System.currentTimeMillis(),
                    vehicleNote = buildVehicleNote(best)
                )
                engine?.updateEta(eta)
            }
            engine?.let { applyDecision(it.snapshot()) }
        } catch (e: Exception) {
            Log.w(TAG, "ETA sorgusu başarısız: ${e.message}")
        }
    }

    /**
     * Yaklaşan aracın kısa tanımı: plaka + klima + erişilebilirlik (Moovit benzeri araç bilgisi).
     */
    private fun buildVehicleNote(eta: com.taha.goit.data.model.StopRemainingTime): String? {
        val parts = mutableListOf<String>()
        eta.licencePlate?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        if (eta.isAirConditioner == true) parts += "Klima"
        if (eta.isSuitableForDisabled == true) parts += "Erişilebilir"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }

    /**
     * Durağa gelecek araçlar arasından hedef hattı seçer.
     * Birebir eşleşme yoksa ön ek / içerik eşleşmesine düşer (hat kodu biçim farkları için).
     */
    private fun pickBestEta(
        etas: List<com.taha.goit.data.model.StopRemainingTime>,
        targetCode: String?
    ): com.taha.goit.data.model.StopRemainingTime? {
        if (targetCode.isNullOrBlank()) {
            return etas.minByOrNull { parseEtaSeconds(it.realTime ?: it.passTime) }
        }
        val code = targetCode.trim()
        val exact = etas.filter { it.routeCode.equals(code, ignoreCase = true) }
        val prefixed = etas.filter { rc ->
            val r = rc.routeCode ?: return@filter false
            r.startsWith(code, ignoreCase = true) || code.startsWith(r, ignoreCase = true)
        }
        val candidates = exact.ifEmpty { prefixed }
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { parseEtaSeconds(it.realTime ?: it.passTime) }
    }

    // ------------------------------------------------------------ Konum

    private fun requestLocationUpdates() {
        val lm = locationManager ?: return
        var anyProvider = false
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                val last = lm.getLastKnownLocation(p)
                if (last != null && System.currentTimeMillis() - last.time <= STALE_FIX_MAX_AGE_MS) {
                    onLocationChanged(last)
                }
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f, this)
                anyProvider = true
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this)
                anyProvider = true
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Konum izni eksik: ${e.message}")
            updateNotification("Konum izni olmadan yolculuk takip edilemiyor.", isAlert = true, force = true)
            return
        } catch (e: Exception) {
            Log.e(TAG, "Konum dinleyicisi kurulamadı: ${e.message}")
        }

        if (!anyProvider) {
            notifyStartFailure("Konum servisleri kapalı. Yolculuk takibi için konumu açın.")
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

    /**
     * Efektif hız (km/h): cihazın raporladığı hız ile ardışık fix'lerden hesaplanan hızın büyüğü.
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
            handleLegacyFix(location)
            return
        }

        val eng = engine ?: return
        val fix = JourneyFix(
            lat = location.latitude,
            lng = location.longitude,
            speedKmh = speedKmh(location),
            timeMs = location.time,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else 0f
        )
        applyDecision(eng.onFix(fix))
    }

    private fun handleLegacyFix(location: Location) {
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

        val settings = (application as? GoItApp)?.settingsManager
        val alertDist = (settings?.alightDistanceMeters ?: 250).toDouble()

        if (distance <= alertDist && !hasAlerted) {
            hasAlerted = true
            sendArrivalAlert("İniş durağına yaklaştın! ($distText) • $destinationName")
        } else {
            updateNotification(detail, isAlert = false)
        }
    }

    // ------------------------------------------------------------ Kararları uygula

    private fun applyDecision(decision: JourneyDecision, forceNotification: Boolean = false) {
        JourneyTracker.updateState(decision.state)

        if (decision.advanceReason != null) {
            Log.d(TAG, "Aşama → ${decision.state.stageType} (${decision.advanceReason})")
            if (decision.state.stageType == "wait") {
                startWaitEtaPolling()
            } else {
                waitEtaJob?.cancel()
                waitEtaJob = null
            }
        }

        if (alertsEnabled) {
            decision.approachWarning?.let { sendApproachAlert(it) }
            decision.alert?.let { sendArrivalAlert(it) }
        } else if (decision.alert != null) {
            Log.i(TAG, "İniş alarmı kapalı; uyarı gösterilmedi: ${decision.alert}")
        }

        updateNotification(decision.notification, isAlert = false, force = forceNotification)

        if (decision.finished) {
            if (!arrivalAnnounced) {
                arrivalAnnounced = true
                // Varış: titreşimli kutlama bildirimi + konum takibinin durdurulması
                sendArrivalAlert("İndin. Hedefe ulaştın • ${decision.state.detail}")
                stopTracking()
                persistPayload(null)
                scheduleFinish()
            }
        }
    }

    private fun scheduleFinish() {
        finishJob?.cancel()
        finishJob = serviceScope.launch {
            delay(FINISH_LINGER_MS)
            stopJourney()
        }
    }

    private fun stopJourney() {
        stopTracking()
        finishJob?.cancel()
        finishJob = null
        persistPayload(null)
        JourneyTracker.updateState(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ Kalıcılık

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun persistPayload(rawJson: String?) {
        val editor = prefs().edit()
        if (rawJson.isNullOrBlank()) {
            editor.remove(KEY_PAYLOAD)
        } else {
            editor.putString(KEY_PAYLOAD, rawJson)
        }
        editor.apply()
    }

    private fun restorePayload(): JourneyPayload? {
        val raw = prefs().getString(KEY_PAYLOAD, null) ?: return null
        return runCatching { json.decodeFromString<JourneyPayload>(raw) }
            .onFailure { persistPayload(null) }
            .getOrNull()
            ?.takeIf { it.stages.isNotEmpty() }
    }

    // ------------------------------------------------------------ Bildirim / uyarı

    private fun sendArrivalAlert(alertText: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        VibrationHelper.vibrateAlert(this)
        nm.notify(ALERT_NOTIFICATION_ID, buildAlert("İniş Zamanı", alertText))
        updateNotification(alertText, isAlert = true, force = true)
    }

    /** Erken uyarı: titreşimsiz bilgi bildirimi (Moovit "Yaklaşıyorsun" davranışı). */
    private fun sendApproachAlert(text: String) {
        updateNotification(text, isAlert = false, force = true)
    }

    private fun notifyStartFailure(message: String = "Yolculuk başlatılamadı. Lütfen rotayı yeniden seçin.") {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID, buildAlert("Yolculuk Başlatılamadı", message))
    }

    // O-4: Bildirim güncelleme throttling (en az 15 sn aralık veya metin değişimi)
    private fun updateNotification(text: String, isAlert: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!isAlert && !force && text == lastNotificationText && now - lastNotificationTime < 15_000L) {
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
        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, JourneyService::class.java).apply { action = ACTION_TOGGLE_ALERTS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, if (isAlert) ALERT_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF1976D2.toInt())
            .setContentTitle(if (isAlert) "GoIt İniş Uyarısı!" else "GoIt Canlı Yolculuk")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(!isAlert)
            .setContentIntent(contentIntent)
            .setPriority(if (isAlert) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .addAction(0, if (alertsEnabled) "Alarmı kapat" else "Alarmı aç", toggleIntent)
            .addAction(0, "Yolculuğu Bitir", stopIntent)
        return builder.build()
    }

    private fun buildAlert(title: String, text: String): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFD32F2F.toInt())
            .setContentTitle("GoIt • $title")
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

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        if (isStagedMode) JourneyTracker.updateState(null)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
