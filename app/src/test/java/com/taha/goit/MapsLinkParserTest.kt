package com.taha.goit

import com.taha.goit.util.MapsLinkParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MapsLinkParserTest {

    @Test
    fun testParsePlainCoordinates() = runBlocking {
        val result = MapsLinkParser.parse("40.1832, 29.0647")
        assertNotNull("Should parse plain coordinates", result)
        assertEquals(40.1832, result!!.lat, 0.0001)
        assertEquals(29.0647, result.lng, 0.0001)
    }

    @Test
    fun testParseGeoUri() = runBlocking {
        val result = MapsLinkParser.parse("geo:40.1832,29.0647")
        assertNotNull("Should parse geo: URI", result)
        assertEquals(40.1832, result!!.lat, 0.0001)
        assertEquals(29.0647, result.lng, 0.0001)
    }

    @Test
    fun testParseGeoUriWithQueryLabel() = runBlocking {
        val result = MapsLinkParser.parse("geo:0,0?q=40.1832,29.0647(Bursa Heykel)")
        assertNotNull("Should parse geo: URI with label", result)
        assertEquals(40.1832, result!!.lat, 0.0001)
        assertEquals(29.0647, result.lng, 0.0001)
        assertEquals("Bursa Heykel", result.label)
    }

    @Test
    fun testParseGoogleMapsUrlWithQuery() = runBlocking {
        val url = "https://maps.google.com/?q=40.1832,29.0647"
        val result = MapsLinkParser.parse(url)
        assertNotNull("Should parse Google Maps query coordinates", result)
        assertEquals(40.1832, result!!.lat, 0.0001)
        assertEquals(29.0647, result.lng, 0.0001)
    }

    @Test
    fun testParseInvalidStringReturnsNull() = runBlocking {
        val result = MapsLinkParser.parse("bu geçersiz bir metindir")
        assertNull("Invalid text should return null", result)
    }
}
