package com.taha.goit

import com.taha.goit.data.api.TransitRepository
import com.taha.goit.data.model.RouteStop
import org.junit.Assert.*
import org.junit.Test

class TransitRepositoryTest {

    @Test
    fun testCalculateDistanceMetersSamePoint() {
        val dist = TransitRepository.calculateDistanceMeters(40.1828, 29.0617, 40.1828, 29.0617)
        assertEquals(0.0, dist, 0.01)
    }

    @Test
    fun testCalculateDistanceMetersBetweenUluCamiAndHeykel() {
        // Bursa Ulu Cami: 40.1828, 29.0617
        // Bursa Heykel: 40.1825, 29.0665
        val dist = TransitRepository.calculateDistanceMeters(40.1828, 29.0617, 40.1825, 29.0665)
        // Yaklaşık 400 - 420 metre
        assertTrue("Distance should be around 410 meters, was: $dist", dist in 380.0..450.0)
    }

    @Test
    fun testWalkMinutesProfiles() {
        // 1000 metre (1 km)
        val normalMin = TransitRepository.walkMinutes(1000.0, "normal")
        val slowMin = TransitRepository.walkMinutes(1000.0, "slow")
        val fastMin = TransitRepository.walkMinutes(1000.0, "fast")

        assertEquals("Normal walk speed is 12 min/km", 12, normalMin)
        assertEquals("Slow walk speed is 15 min/km", 15, slowMin)
        assertEquals("Fast walk speed is 10 min/km", 10, fastMin)

        // 0 metre için 0 dakika
        assertEquals(0, TransitRepository.walkMinutes(0.0))
        // Çok kısa mesafe için minimum 1 dakika
        assertEquals(1, TransitRepository.walkMinutes(10.0))
    }

    @Test
    fun testBusLegMinutesCalculation() {
        val stops = listOf(
            RouteStop(stopId = 1, stopName = "Durak 1", latitude = "40.1828", longitude = "29.0617"),
            RouteStop(stopId = 2, stopName = "Durak 2", latitude = "40.1825", longitude = "29.0665"),
            RouteStop(stopId = 3, stopName = "Durak 3", latitude = "40.1820", longitude = "29.0720")
        )

        val durationNormal = TransitRepository.busLegMinutes(stops, hourOfDay = 14)
        val durationPeak = TransitRepository.busLegMinutes(stops, hourOfDay = 8)

        assertTrue("Bus leg duration must be positive", durationNormal >= 3)
        assertTrue("Peak hour duration should be >= normal hour duration", durationPeak >= durationNormal)
    }
}
