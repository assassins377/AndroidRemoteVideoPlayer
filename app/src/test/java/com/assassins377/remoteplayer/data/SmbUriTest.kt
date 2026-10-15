package com.assassins377.remoteplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SmbUriTest {
    @Test fun roundTripWithSpacesAndUnicode() {
        val uri = SmbUri.build("abc-123", "Фильмы/My Movie (2020)/file+1.mkv")
        assertEquals("abc-123" to "Фильмы/My Movie (2020)/file+1.mkv", SmbUri.parse(uri))
    }

    @Test fun linkContainsNoCredentials() {
        val uri = SmbUri.build("id1", "a/b.mp4")
        assertFalse(uri.contains("?"))
        assertEquals("rvp-smb://id1/a/b.mp4", uri)
    }

    @Test fun rejectsOtherSchemes() {
        assertNull(SmbUri.parse("http://host/a.mp4"))
        assertNull(SmbUri.parse("smb://host/share/a.mp4"))
    }
}
