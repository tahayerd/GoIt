package com.taha.goit

import com.taha.goit.data.api.OsrmRouter
import org.junit.Assert.*
import org.junit.Test

class OsrmRouterTest {

    @Test
    fun testBuildUrlFormattingDoesNotHaveDoublePercentBug() {
        val lat1 = 40.182812
        val lng1 = 29.061734
        val lat2 = 40.182545
        val lng2 = 29.066512

        val url = OsrmRouter.buildUrl(lat1, lng1, lat2, lng2)

        // K-1 doğrulaması: URL literal % içermemeli (%% bug'ı düzeltildi)
        assertFalse("URL must not contain literal % format token", url.contains("%%"))
        assertFalse("URL must not contain unformatted %.6f literal", url.contains("%.6f"))

        // OSRM formatı: lon1,lat1;lon2,lat2
        assertTrue("URL must contain longitude 1", url.contains("29.061734"))
        assertTrue("URL must contain latitude 1", url.contains("40.182812"))
        assertTrue("URL must contain longitude 2", url.contains("29.066512"))
        assertTrue("URL must contain latitude 2", url.contains("40.182545"))

        // İki koordinat noktası arasında noktalı virgül olmalı
        assertTrue("URL must separate coordinates with semicolon", url.contains(";"))
        assertTrue("URL must point to routed-foot driving endpoint", url.contains("https://routing.openstreetmap.de/routed-foot/route/v1/driving/"))
    }

    @Test
    fun testHashKeyIsStableAndSha256Length() {
        val key = "40.18281,29.06173_40.18254,29.06651"
        val hash1 = OsrmRouter.hashKey(key)
        val hash2 = OsrmRouter.hashKey(key)

        assertEquals("Hashes must match for identical key", hash1, hash2)
        assertEquals("SHA-256 hex string length must be 64 characters", 64, hash1.length)
    }
}
