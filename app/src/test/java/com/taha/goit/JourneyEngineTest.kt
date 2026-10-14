package com.taha.goit

import com.taha.goit.service.Geo
import com.taha.goit.service.GeoPoint
import com.taha.goit.service.JourneyEngine
import com.taha.goit.service.JourneyFix
import com.taha.goit.service.JourneyPayload
import com.taha.goit.service.JourneySettings
import com.taha.goit.service.JourneyStage
import com.taha.goit.service.JourneyStop
import com.taha.goit.service.LiveEta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canlı yolculuk çekirdeği testleri.
 *
 * Sentetik rota: kuzeye giden ~200 m aralıklı 6 duraklı bir hat.
 * Yürüyüş aşaması → bekleme (otomatik biniş) → araç (durak sayacı + iniş alarmı) akışının
 * tamamı gerçek GPS izleriyle burada doğrulanır.
 */
class JourneyEngineTest {

    // ~200 m aralıkla kuzeye giden durak listesi
    private val stops: List<JourneyStop> = (0 until 6).map { i ->
        JourneyStop(name = "Durak ${i + 1}", lat = 40.1900 + i * 0.0018, lng = 29.0600)
    }

    private val destination = JourneyStop("Hedef", 40.2000, 29.0650)

    private fun payloadWith(stages: List<JourneyStage>) =
        JourneyPayload(destinationName = destination.name, destLat = destination.lat, destLng = destination.lng, stages = stages)

    private val walkStage = JourneyStage(
        type = "walk",
        title = "Kalkış Durağı yürü",
        targetLat = stops.first().lat,
        targetLng = stops.first().lng,
        targetName = "Kalkış Durağı"
    )

    private val waitStage = JourneyStage(
        type = "wait",
        routeCode = "38",
        stopId = 1001,
        title = "38 hattını bekle",
        targetLat = stops.first().lat,
        targetLng = stops.first().lng,
        targetName = "Kalkış Durağı"
    )

    private val rideStage = JourneyStage(
        type = "ride",
        routeCode = "38",
        title = "38 ile git",
        targetLat = stops.last().lat,
        targetLng = stops.last().lng,
        targetName = "İniş Durağı",
        stops = stops
    )

    private fun fix(
        lat: Double,
        lng: Double,
        speedKmh: Float = 0f,
        timeMs: Long = 1_000_000L,
        accuracy: Float = 8f
    ) = JourneyFix(lat, lng, speedKmh, timeMs, accuracy)

    private fun engine(stages: List<JourneyStage>, settings: JourneySettings = JourneySettings()) =
        JourneyEngine(payloadWith(stages), settings)

    // ------------------------------------------------------------ Yürüyüş

    @Test
    fun `yürüyüş aşaması durağa 40 m kalınca bekleme aşamasına geçer`() {
        val eng = engine(listOf(walkStage, waitStage, rideStage))

        // 220 m güneyde başla → hâlâ yürüyüş
        val far = eng.onFix(fix(40.1880, 29.0600))
        assertEquals("walk", far.state.stageType)
        assertNull(far.advanceReason)

        // Durağa ~33 m kalınca bekleme aşamasına geçmeli
        val close = eng.onFix(fix(40.1897, 29.0600))
        assertEquals("wait", close.state.stageType)
        assertNotNull(close.advanceReason)
    }

    @Test
    fun `düşük doğruluklu fix aşama atlatmaz`() {
        val eng = engine(listOf(walkStage, waitStage, rideStage))

        // Konum tam durağın üstünde ama doğruluk 300 m → yürüyüş aşamasında kalmalı
        val decision = eng.onFix(fix(40.1900, 29.0600, accuracy = 300f))
        assertEquals("walk", decision.state.stageType)
        assertNull(decision.advanceReason)
    }

    @Test
    fun `yürüyüş ilerlemesi başlangıç mesafesine göre hesaplanır`() {
        val eng = engine(listOf(walkStage, waitStage, rideStage))
        eng.onFix(fix(40.1860, 29.0600)) // ~444 m
        val mid = eng.onFix(fix(40.1888, 29.0600)) // ~133 m
        assertTrue("İlerleme 0 ile 1 arasında olmalı", mid.state.progress in 0.0f..1.0f)
        assertTrue("İlerleme artmış olmalı", mid.state.progress > 0.4f)
    }

    // ------------------------------------------------------------ Biniş

    @Test
    fun `hızlı ve uzaklaşan hareket iki fixte binişi algılar`() {
        val eng = engine(listOf(waitStage, rideStage))

        // Duraktan ~600 m uzakta, 15 km/h (rota koridorunun dışında)
        val first = eng.onFix(fix(40.1930, 29.0670, speedKmh = 15f, timeMs = 1_000_000L))
        assertEquals("wait", first.state.stageType)

        val second = eng.onFix(fix(40.1935, 29.0680, speedKmh = 16f, timeMs = 1_005_000L))
        assertEquals("ride", second.state.stageType)
        assertNotNull(second.advanceReason)
    }

    @Test
    fun `durakta bekleyen kullanıcı araçta sayılmaz`() {
        val eng = engine(listOf(waitStage, rideStage))

        // Durağın 10 m yanında, hareketsiz — birden fazla fix
        repeat(6) { i ->
            val d = eng.onFix(fix(40.1901, 29.0601, speedKmh = 0f, timeMs = 1_000_000L + i * 5000L))
            assertEquals("wait", d.state.stageType)
        }
    }

    @Test
    fun `rota koridoru boyunca yavaş ilerleme de binişi algılar`() {
        val eng = engine(listOf(waitStage, rideStage))

        // Rota hattı üzerinde 2. durağa doğru ~5 km/h (sıkışık trafik senaryosu)
        val d1 = eng.onFix(fix(40.1915, 29.0600, speedKmh = 5f, timeMs = 1_000_000L))
        assertEquals("wait", d1.state.stageType)
        val d2 = eng.onFix(fix(40.1916, 29.0600, speedKmh = 5f, timeMs = 1_005_000L))
        assertEquals("ride", d2.state.stageType)
    }

    // ------------------------------------------------------------ Araç içi

    @Test
    fun `durak sayacı GPS sıçramasında geri saymaz`() {
        val eng = engine(listOf(rideStage))
        val third = stops[2]

        val atThird = eng.onFix(fix(third.lat, third.lng, speedKmh = 20f, timeMs = 1_000_000L))
        assertEquals("ride", atThird.state.stageType)
        val remaining = atThird.state.stopsRemaining ?: -1
        assertEquals("3. durakta 3 durak kalmalı", 3, remaining)

        // GPS sıçraması: 1. durağa geri dön
        val jumped = eng.onFix(fix(stops[0].lat, stops[0].lng, speedKmh = 20f, timeMs = 1_005_000L))
        assertEquals("Sayı geri saymamalı", remaining, jumped.state.stopsRemaining)
    }

    @Test
    fun `iniş alarmı bir kez tetiklenir ve son durakta yolculuk biter`() {
        val eng = engine(listOf(rideStage))

        var alerts = 0
        // Son durağa doğru ilerle
        for (i in 0 until 5) {
            val d = eng.onFix(fix(stops[i].lat, stops[i].lng, speedKmh = 18f, timeMs = 1_000_000L + i * 5000L))
            if (d.alert != null) alerts++
        }
        assertEquals("İniş alarmı yalnızca bir kez verilmeli", 1, alerts)

        // Son durakta yolculuk bitmeli
        val finalFix = eng.onFix(fix(stops.last().lat, stops.last().lng, speedKmh = 0f, timeMs = 1_030_000L))
        assertTrue("Yolculuk bitmiş olmalı", finalFix.finished)
        assertEquals("arrived", finalFix.state.stageType)
    }

    @Test
    fun `yaklaşım uyarısı iki durak kala verilir`() {
        val eng = engine(listOf(rideStage))
        var warned = false
        for (i in 0 until 6) {
            val d = eng.onFix(fix(stops[i].lat, stops[i].lng, speedKmh = 18f, timeMs = 1_000_000L + i * 5000L))
            if (d.approachWarning != null) warned = true
        }
        assertTrue("2 durak kala yaklaşım uyarısı bekleniyordu", warned)
    }

    @Test
    fun `durak verisi bozuksa mesafe tabanlı iniş yapılır`() {
        val stopless = rideStage.copy(stops = emptyList())
        val eng = engine(listOf(stopless))

        val far = eng.onFix(fix(40.1700, 29.0600, speedKmh = 15f, timeMs = 1_000_000L))
        assertEquals("ride", far.state.stageType)

        val near = eng.onFix(fix(stops.last().lat, stops.last().lng, speedKmh = 5f, timeMs = 1_005_000L))
        assertTrue("Durak listesi olmasa da iniş algılanmalı", near.finished)
    }

    // ------------------------------------------------------------ ETA

    @Test
    fun `bekleme aşamasında canlı ETA HUD metnine yansır`() {
        val eng = engine(listOf(waitStage, rideStage))
        eng.onFix(fix(40.1901, 29.0601, speedKmh = 0f))
        eng.updateEta(LiveEta(minutes = 7, text = "7 dk", updatedAtMs = 1L))

        val decision = eng.snapshot()
        assertEquals("7 dk", decision.state.waitEtaMinutes)
        assertTrue("ETA bilgisi detayda görünmeli", decision.state.detail.contains("7 dk"))
    }

    @Test
    fun `hat yaklaşınca geliyor uyarısı bir kez verilir`() {
        val eng = engine(listOf(waitStage, rideStage))

        // Durakta bekleyen kullanıcı, hat 7 dk uzakta
        eng.onFix(fix(40.1901, 29.0601, speedKmh = 0f, timeMs = 1_000_000L))
        eng.updateEta(LiveEta(minutes = 7, text = "7 dk"))
        val far = eng.onFix(fix(40.1901, 29.0601, speedKmh = 0f, timeMs = 1_005_000L))
        assertNull("7 dk kala geliyor uyarısı verilmemeli", far.approachWarning)

        // Hat 1 dk içinde
        eng.updateEta(LiveEta(minutes = 1, text = "1 dk"))
        val near = eng.onFix(fix(40.1901, 29.0601, speedKmh = 0f, timeMs = 1_010_000L))
        assertNotNull("Hat yaklaşınca uyarı verilmeli", near.approachWarning)

        val again = eng.onFix(fix(40.1901, 29.0601, speedKmh = 0f, timeMs = 1_015_000L))
        assertNull("Uyarı tekrarlanmamalı", again.approachWarning)
    }

    // ------------------------------------------------------------ Yürüyüş yönergesi

    @Test
    fun `yürüyüş yönergesi dönüş noktasını bildirir`() {
        val path = listOf(
            GeoPoint(40.1900, 29.0600),
            GeoPoint(40.1900, 29.0610),
            GeoPoint(40.1910, 29.0610)
        )
        val guidance = Geo.walkGuidance(GeoPoint(40.1900, 29.0602), path)
        assertNotNull(guidance)
        assertTrue(
            "Kuzeydoğuya dönüşte yön tarifi bekleniyordu, gelen: ${guidance!!.hint}",
            guidance.hint.contains("dön")
        )
    }

    @Test
    fun `düz yolda hedefe doğru devam yönergesi verilir`() {
        val path = listOf(
            GeoPoint(40.1900, 29.0600),
            GeoPoint(40.1920, 29.0600),
            GeoPoint(40.1940, 29.0600)
        )
        val guidance = Geo.walkGuidance(GeoPoint(40.1905, 29.0600), path)
        assertNotNull(guidance)
        assertTrue("Düz devam bekleniyordu", guidance!!.hint.contains("düz"))
    }

    // ------------------------------------------------------------ Geometri

    @Test
    fun `polyline izdüşümü hat boyunca mesafeyi doğru hesaplar`() {
        val path = listOf(
            GeoPoint(40.1900, 29.0600),
            GeoPoint(40.1920, 29.0600)
        )
        val proj = Geo.projectOnPolyline(GeoPoint(40.1910, 29.0600), path)
        // 0.001° enlem ≈ 111 m; yol 0.002° ≈ 222 m
        assertTrue("Yol boyu mesafe ~111 m olmalı: ${proj.alongMeters}", proj.alongMeters in 100.0..125.0)
        assertTrue("Hattan dik uzaklık ~0 olmalı", proj.perpendicularMeters < 5.0)
    }

    @Test
    fun `etap dışı noktada dik mesafe pozitif döner`() {
        val path = listOf(
            GeoPoint(40.1900, 29.0600),
            GeoPoint(40.1920, 29.0600)
        )
        val proj = Geo.projectOnPolyline(GeoPoint(40.1910, 29.0610), path)
        assertTrue("Hattan uzaklık ~85 m olmalı: ${proj.perpendicularMeters}", proj.perpendicularMeters in 70.0..100.0)
    }
}
