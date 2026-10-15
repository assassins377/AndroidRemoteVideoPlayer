package com.assassins377.remoteplayer.settings

import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.data.ServerTransfer
import com.assassins377.remoteplayer.data.ServerType
import com.assassins377.remoteplayer.data.SmbPool
import com.hierynomus.mssmb2.SMB2Dialect
import org.junit.Assert.*
import org.junit.Test

class SettingsMappingTest {
    @Test fun emptyMapGivesDefaults() = assertEquals(AppSettings(), AppSettings.fromMap(emptyMap()))

    @Test fun validValuesAreMapped() {
        val s = AppSettings.fromMap(mapOf(
            "defaultSpeed" to 1.5f, "seekStepSec" to 30, "resume" to false, "decoder" to "SOFTWARE",
            "smbDialect" to "SMB3", "smbEncryption" to true, "theme" to "DARK", "sort" to "SIZE",
            "readAheadKb" to 2048, "retryCount" to 5, "language" to "EN", "subtitleColor" to 0xFFFFEB3BL,
        ))
        assertEquals(1.5f, s.defaultSpeed); assertEquals(30_000L, s.seekStepMs); assertFalse(s.resume)
        assertEquals(DecoderMode.SOFTWARE, s.decoder); assertEquals(SmbDialect.SMB3, s.smbDialect)
        assertTrue(s.smbEncryption); assertEquals(ThemeMode.DARK, s.theme); assertEquals(SortOrder.SIZE, s.sort)
        assertEquals(2048 * 1024, s.readAheadBytes); assertEquals(5, s.retryCount); assertEquals("en", s.language.tag)
        assertEquals(0xFFFFEB3BL, s.subtitleColor)
    }

    @Test fun invalidValuesFallBack() {
        val s = AppSettings.fromMap(mapOf("seekStepSec" to 7, "defaultSpeed" to 9f, "decoder" to "GPU", "retryCount" to 0, "resume" to "yes"))
        val d = AppSettings()
        assertEquals(d.seekStepSec, s.seekStepSec); assertEquals(d.defaultSpeed, s.defaultSpeed)
        assertEquals(d.decoder, s.decoder); assertEquals(d.retryCount, s.retryCount); assertEquals(d.resume, s.resume)
    }

    @Test fun dialectMapping() {
        assertTrue(SmbPool.dialectsFor(SmbDialect.SMB2).none { it.name.startsWith("SMB_3") })
        assertTrue(SmbPool.dialectsFor(SmbDialect.SMB3).all { it.name.startsWith("SMB_3") })
        assertTrue(SMB2Dialect.SMB_2_0_2 in SmbPool.dialectsFor(SmbDialect.AUTO))
    }

    @Test fun networkChangeDetection() {
        val a = AppSettings()
        assertTrue(SettingsStore.networkChanged(a, a.copy(smbEncryption = true)))
        assertFalse(SettingsStore.networkChanged(a, a.copy(theme = ThemeMode.DARK)))
    }
}

class ResumePositionsTest {
    @Test fun storesMidPositionAndForgetsNearEnds() {
        val r = ResumePositions()
        r.update("a", 60_000, 600_000); assertEquals(60_000L, r.get("a"))
        r.update("a", 2_000, 600_000); assertNull(r.get("a"))          // near start
        r.update("b", 595_000, 600_000); assertNull(r.get("b"))        // near end
        r.update("c", 30_000, 0); assertEquals(30_000L, r.get("c"))    // unknown duration
    }

    @Test fun lruCap() {
        val r = ResumePositions(maxEntries = 2)
        r.update("1", 10_000, 0); r.update("2", 10_000, 0); r.get("1"); r.update("3", 10_000, 0)
        assertNull(r.get("2")); assertNotNull(r.get("1")); assertNotNull(r.get("3"))
    }

    @Test fun clear() { val r = ResumePositions(); r.update("x", 10_000, 0); r.clear(); assertTrue(r.snapshot().isEmpty()) }
}

class SubtitleMatcherTest {
    private fun f(n: String) = RemoteFile(n, "Movies/$n", false)

    @Test fun matchesSameBaseNameWithLanguage() {
        val files = listOf(f("Film.mkv"), f("Film.srt"), f("Film.ru.ass"), f("film.eng.forced.vtt"), f("Film 2.srt"), f("Other.srt"), f("Film.txt"))
        val m = SubtitleMatcher.find("Film.mkv", files).associateBy { it.file.name }
        assertEquals(setOf("Film.srt", "Film.ru.ass", "film.eng.forced.vtt"), m.keys)
        assertNull(m["Film.srt"]!!.language)
        assertEquals("ru", m["Film.ru.ass"]!!.language)
        assertEquals("eng", m["film.eng.forced.vtt"]!!.language)
        assertEquals("application/x-subrip", m["Film.srt"]!!.mimeType)
        assertEquals("text/x-ssa", m["Film.ru.ass"]!!.mimeType)
        assertEquals("text/vtt", m["film.eng.forced.vtt"]!!.mimeType)
    }
}

class FileSorterTest {
    private val files = listOf(
        RemoteFile("b.mkv", "b.mkv", false, size = 300, modified = 1),
        RemoteFile("A.mp4", "A.mp4", false, size = 100, modified = 3),
        RemoteFile("c.mkv", "c.mkv", false, size = 200, modified = 2),
        RemoteFile("Zdir", "Zdir", true, modified = 0),
        RemoteFile(".hidden.mkv", ".hidden.mkv", false, hidden = true),
        RemoteFile("b.srt", "b.srt", false),
    )

    @Test fun byName() = assertEquals(listOf("Zdir", "A.mp4", "b.mkv", "c.mkv"), FileSorter.sort(files, SortOrder.NAME, false).map { it.name })
    @Test fun byDate() = assertEquals(listOf("Zdir", "A.mp4", "c.mkv", "b.mkv"), FileSorter.sort(files, SortOrder.DATE, false).map { it.name })
    @Test fun bySize() = assertEquals(listOf("Zdir", "b.mkv", "c.mkv", "A.mp4"), FileSorter.sort(files, SortOrder.SIZE, false).map { it.name })
    @Test fun hiddenShownWhenEnabled() = assertTrue(FileSorter.sort(files, SortOrder.NAME, true).any { it.hidden })
    @Test fun nextVideo() {
        assertEquals("b.mkv", FileSorter.nextVideo(files, "A.mp4")?.name)
        assertNull(FileSorter.nextVideo(files, "c.mkv"))
    }
}

class ServerTransferTest {
    @Test fun exportHasNoPasswordAndRoundTrips() {
        val s = ServerConfig("id1", "NAS", ServerType.SMB, "192.168.1.5", "media", "user", "secret123")
        val json = ServerTransfer.encode(listOf(s))
        assertFalse(json.contains("secret123"))
        assertEquals(listOf(s.copy(password = "")), ServerTransfer.decode(json))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsForeignFormat() { ServerTransfer.decode("""{"format":"other","servers":[]}""") }
}
