package com.assassins377.remoteplayer

import com.assassins377.remoteplayer.settings.AppSettings
import com.assassins377.remoteplayer.ui.player.PlayerMath
import com.assassins377.remoteplayer.ui.player.TrackOptions
import org.junit.Assert.*
import org.junit.Test

class PlayerMathTest {
    @Test fun swipeSeek() {
        assertEquals(60_000L + 45_000, PlayerMath.swipeTarget(60_000, 500f, 1000f, 600_000))   // half width = 45 s
        assertEquals(0L, PlayerMath.swipeTarget(10_000, -1000f, 1000f, 600_000))              // clamp at start
        assertEquals(600_000L, PlayerMath.swipeTarget(590_000, 1000f, 1000f, 600_000))        // clamp at end
        assertEquals(100_000L, PlayerMath.swipeTarget(10_000, 1000f, 1000f, 0))               // unknown duration
        assertEquals(5L, PlayerMath.swipeTarget(5, 100f, 0f, 100))
    }

    @Test fun timeFormatting() {
        assertEquals("0:05", PlayerMath.formatTime(5_400)); assertEquals("1:02:03", PlayerMath.formatTime(3_723_000))
        assertEquals("+0:15", PlayerMath.formatDelta(15_000)); assertEquals("−1:02", PlayerMath.formatDelta(-62_000))
    }

    @Test fun pipAspectClamped() {
        assertEquals(1920 to 1080, PlayerMath.pipAspect(1920, 1080))
        assertEquals(239 to 100, PlayerMath.pipAspect(3000, 1000))
        assertEquals(100 to 239, PlayerMath.pipAspect(400, 1200))
        assertEquals(16 to 9, PlayerMath.pipAspect(0, 0))
    }

    @Test fun trackLabels() {
        assertEquals("Русский · Дубляж · 5.1", TrackOptions.labelOf("Дубляж", "ru", 6, "audio/ac3", 1, java.util.Locale("ru")))
        assertEquals("Английский · SRT", TrackOptions.labelOf(null, "en", -1, "application/x-subrip", 1, java.util.Locale("ru")))
        assertEquals("Дорожка 3", TrackOptions.labelOf(null, "und", -1, null, 3, java.util.Locale("ru")))
        assertEquals("Японский · стерео", TrackOptions.labelOf("", "ja", 2, null, 1, java.util.Locale("ru")))
    }

    @Test fun newSettingsMapping() {
        val s = AppSettings.fromMap(mapOf("backgroundAudio" to true, "horizontalSeek" to false))
        assertTrue(s.backgroundAudio); assertFalse(s.horizontalSeek)
        assertFalse(AppSettings().backgroundAudio); assertTrue(AppSettings().horizontalSeek)
    }
}
