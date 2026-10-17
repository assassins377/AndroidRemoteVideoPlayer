package com.assassins377.remoteplayer.desktop

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fileSecretsRoundTripAndEncrypted() {
        val f = tmp.newFile("s.properties"); val k = java.io.File(tmp.root, "k")
        FileSecrets(f, k).put("server:1", "secret123")
        assertFalse(f.readText().contains("secret123"))
        assertEquals("secret123", FileSecrets(f, k).get("server:1"))
        FileSecrets(f, k).remove("server:1")
        assertNull(FileSecrets(f, k).get("server:1"))
    }

    @Test fun forcedFileBackend() {
        assertTrue(Secrets.best(tmp.root, mapOf("REMOTEPLAYER_SECRETS" to "file")) is FileSecrets)
    }

    @Test fun rangeParse() {
        assertEquals(0L to null, Range.parse("bytes=0-"))
        assertEquals(10L to 99L, Range.parse("bytes=10-99"))
        assertNull(Range.parse("bytes=-5"))
        assertNull(Range.parse(null))
    }

    @Test fun stringsComplete() { assertEquals(emptySet<String>(), Strings.missing()) }

    @Test fun playerMath() {
        assertEquals("1:05", PlayerMath.fmt(65_000))
        assertEquals("1:00:00", PlayerMath.fmt(3_600_000))
        assertEquals("+0.5 s", PlayerMath.delayLabel(500).replace(',', '.'))
    }
}
