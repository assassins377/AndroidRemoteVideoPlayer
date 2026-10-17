package com.assassins377.remoteplayer.desktop

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WindowsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun windowsDirs() {
        val d = Dirs.forOs(mapOf("APPDATA" to "C:\\U\\AppData\\Roaming", "LOCALAPPDATA" to "C:\\U\\AppData\\Local"), "C:\\U", windows = true)
        assertEquals(File("C:\\U\\AppData\\Roaming", "RemoteVideoPlayer"), d.config)
        assertEquals(File("C:\\U\\AppData\\Local", "RemoteVideoPlayer"), d.data)
        assertEquals(File(File("C:\\U\\AppData\\Local", "RemoteVideoPlayer"), "cache"), d.cache)
        assertEquals(File(File("C:\\U", "Videos"), "Remote Video Player"), Downloads.defaultFolder(mapOf("USERPROFILE" to "C:\\U"), "x", windows = true))
        assertEquals(Dirs.xdg(mapOf(), "/home/u").config, Dirs.forOs(mapOf(), "/home/u", windows = false).config)
    }

    @Test fun vlcCandidatesIncludeProgramFiles() {
        assertTrue(WinVlc.candidates(mapOf("ProgramFiles" to "D:\\PF")).any { it.path == File("D:\\PF\\VideoLAN\\VLC").path })
    }

    // Run on the windows-latest CI runner.
    @Test fun credentialManagerRoundTrip() {
        assumeTrue(Os.isWindows)
        val s = WinCredSecrets()
        assertTrue(WinCredSecrets.available())
        s.put("test:roundtrip", "pässwörd 123")
        assertEquals("pässwörd 123", s.get("test:roundtrip"))
        s.remove("test:roundtrip")
        assertNull(s.get("test:roundtrip"))
        assertTrue(Secrets.best(tmp.root, emptyMap()) is WinCredSecrets)
    }

    @Test fun dpapiRoundTrip() {
        assumeTrue(Os.isWindows)
        val f = File(tmp.root, "s.properties")
        DpapiSecrets(f).put("a", "secret")
        assertFalse(f.readText().contains("secret="))
        assertEquals("secret", DpapiSecrets(f).get("a"))
    }
}
