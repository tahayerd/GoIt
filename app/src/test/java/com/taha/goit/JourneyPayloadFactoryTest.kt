package com.taha.goit

import com.taha.goit.data.model.PlanLeg
import com.taha.goit.data.model.RoutePlan
import com.taha.goit.data.model.RouteStop
import com.taha.goit.service.GeoPoint
import com.taha.goit.service.JourneyPayload
import com.taha.goit.service.JourneyPayloadFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RoutePlan → JourneyPayload dönüşümü testleri.
 *
 * Amaç: canlı yolculuğun hiçbir koşulda "boş aşama listesi" ile (yani sessizce
 * çalışmayan bir servisle) başlamamasını garanti etmek.
 */
class JourneyPayloadFactoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun stop(id: Int, name: String, lat: Double, lng: Double) = RouteStop(
        stopId = id,
        stopName = name,
        sequence = id,
        latitude = lat.toString(),
        longitude = lng.toString(),
        direction = "G"
    )

    private val board = stop(1, "Kalkış Durağı", 40.1900, 29.0600)
    private val alight = stop(3, "İniş Durağı", 40.1940, 29.0600)
    private val stops = listOf(
        board,
        stop(2, "Orta Durak", 40.1920, 29.0600),
        alight
    )

    private fun directPlan(): RoutePlan = RoutePlan(
        originStop = board,
        destinationStop = alight,
        routeCode = "38",
        routeName = "38 Hattı",
        totalStops = 3,
        estimatedDurationMinutes = 12,
        intermediateStops = stops,
        legs = listOf(
            PlanLeg(kind = "walk", walkMeters = 300.0, minutes = 4, alightStop = board),
            PlanLeg(
                kind = "bus",
                routeCode = "38",
                routeNo = 38,
                direction = "G",
                boardStop = board,
                alightStop = alight,
                stops = stops,
                minutes = 8
            )
        ),
        walkToMeters = 300.0
    )

    @Test
    fun `doğrudan plan yürüme bekleme ve araç aşamalarını üretir`() {
        val payload = JourneyPayloadFactory.build(directPlan(), "Hedef", 40.2000, 29.0650)

        assertEquals(3, payload.stages.size)
        assertEquals("walk", payload.stages[0].type)
        assertEquals("wait", payload.stages[1].type)
        assertEquals("ride", payload.stages[2].type)
        assertEquals("38", payload.stages[1].routeCode)
        assertEquals(1, payload.stages[1].stopId)
        assertEquals(3, payload.stages[2].stops.size)
    }

    @Test
    fun `bacak verisi olmayan plandan da güvenli aşama üretilir`() {
        val broken = directPlan().copy(legs = emptyList())
        val payload = JourneyPayloadFactory.build(broken, "Hedef", 40.2000, 29.0650)

        assertTrue("En az bir aşama üretilmeli", payload.stages.isNotEmpty())
        assertEquals("walk", payload.stages.first().type)
        assertTrue("Bekleme aşaması da üretilmeli", payload.stages.any { it.type == "wait" })
        assertTrue("Araç aşaması da üretilmeli", payload.stages.any { it.type == "ride" })
    }

    @Test
    fun `koordinatsız plan sadece yürüyüş aşamasına düşer ve boş kalmaz`() {
        val broken = RoutePlan(
            originStop = RouteStop(stopName = "Bilinmeyen"),
            destinationStop = RouteStop(stopName = "Bilinmeyen Hedef"),
            routeCode = "38",
            routeName = "",
            totalStops = 0,
            estimatedDurationMinutes = 5,
            intermediateStops = emptyList(),
            legs = emptyList()
        )
        val payload = JourneyPayloadFactory.build(broken, "Hedef", 40.2000, 29.0650)

        assertTrue("Boş payload üretilmemeli", payload.stages.isNotEmpty())
        assertEquals("walk", payload.stages.first().type)
    }

    @Test
    fun `sadece yürüyüş planı tek aşama üretir ve yol geometrisini taşır`() {
        val walkOnly = RoutePlan(
            originStop = RouteStop(stopName = "Kalkış"),
            destinationStop = RouteStop(stopName = "Varış"),
            routeCode = "Yürüyüş",
            routeName = "",
            totalStops = 0,
            estimatedDurationMinutes = 8,
            intermediateStops = emptyList(),
            legs = listOf(PlanLeg(kind = "walk", walkMeters = 700.0, minutes = 8)),
            isWalkOnly = true,
            walkOnlyMeters = 700.0
        )
        val path = listOf(GeoPoint(40.1900, 29.0600), GeoPoint(40.1910, 29.0600))
        val payload = JourneyPayloadFactory.build(walkOnly, "Hedef", 40.2000, 29.0650, mapOf(0 to path))

        assertEquals(1, payload.stages.size)
        assertEquals("walk", payload.stages[0].type)
        assertEquals("Yol geometrisi korunmalı", 2, payload.stages[0].path.size)
    }

    @Test
    fun `sıfır koordinatlı duraklar elenir`() {
        val zeroBoard = stop(9, "Bozuk Durak", 0.0, 0.0)
        val plan = directPlan().copy(
            legs = listOf(
                PlanLeg(
                    kind = "bus",
                    routeCode = "38",
                    boardStop = zeroBoard,
                    alightStop = alight,
                    stops = listOf(zeroBoard, alight)
                )
            )
        )
        val payload = JourneyPayloadFactory.build(plan, "Hedef", 40.2000, 29.0650)

        assertTrue("Payload boş olmamalı", payload.stages.isNotEmpty())
        payload.stages.forEach { stage ->
            assertTrue(
                "Geçersiz (0,0) koordinatlı aşama üretilmemeli",
                stage.targetLat != 0.0 && stage.targetLng != 0.0
            )
        }
    }

    @Test
    fun `payload JSON gidiş dönüşü yol geometrisini korur`() {
        val path = listOf(GeoPoint(40.1900, 29.0600), GeoPoint(40.1905, 29.0605))
        val payload = JourneyPayloadFactory.build(directPlan(), "Hedef", 40.2000, 29.0650, mapOf(0 to path))

        val encoded = json.encodeToString(payload)
        val decoded = json.decodeFromString<JourneyPayload>(encoded)

        assertEquals(payload, decoded)
        assertEquals("Yol geometrisi JSON'da korunmalı", 2, decoded.stages[0].path.size)
        assertNotNull(decoded.stages[1].stopId)
    }

    @Test
    fun `hat kodu ve durak kimliği canlı ETA için taşınır`() {
        val payload = JourneyPayloadFactory.build(directPlan(), "Hedef", 40.2000, 29.0650)
        val wait = payload.stages.first { it.type == "wait" }

        assertEquals("38", wait.routeCode)
        assertEquals(1, wait.stopId)
        assertTrue("Biniş durağı adı taşınmalı", wait.targetName.isNotBlank())
    }
}
