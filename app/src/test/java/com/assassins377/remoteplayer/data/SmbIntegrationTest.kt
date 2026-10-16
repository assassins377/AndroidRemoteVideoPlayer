package com.assassins377.remoteplayer.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against a real Samba server only when SMB_IT_HOST is set, e.g.
 * SMB_IT_HOST=127.0.0.1 SMB_IT_USER=tester SMB_IT_PASS=secret123 SMB_IT_SHARE=media ./gradlew testDebugUnitTest
 * Expects <share>/Movies/Test Clip.mkv.
 */
class SmbIntegrationTest {
    private val host = System.getenv("SMB_IT_HOST")
    private fun server(pass: String = System.getenv("SMB_IT_PASS") ?: "") = ServerConfig(
        "it-" + pass.hashCode(), "it", ServerType.SMB, host ?: "", System.getenv("SMB_IT_SHARE") ?: "media",
        System.getenv("SMB_IT_USER") ?: "", pass,
    )

    @Test fun listSharesBrowseAndReadThroughPool() = runBlocking {
        assumeTrue(host != null)
        val s = server(); val repo = SmbRepository()
        repo.testConnection(s)
        val shares = repo.listShares(s)
        assertTrue("shares=$shares", s.share in shares && shares.none { it.endsWith("$") })
        val root = repo.list(s, "")
        assertTrue(root.any { it.isDirectory && it.name == "Movies" })
        val clip = repo.list(s, "Movies").first { it.name == "Test Clip.mkv" }
        assertTrue(clip.isVideo && clip.size > 1_000_000)
        assertSame(SmbPool.session(s), SmbPool.session(s)) // pooled

        // Random-access read through read-ahead buffer; compare with direct reads.
        val f = SmbPool.share(s).openFile("Movies\\Test Clip.mkv", setOf(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        val rab = ReadAheadBuffer { pos, b, o, l -> f.read(b, pos, o, l) }
        val head = ByteArray(4); rab.read(0, head, 0, 4)
        assertEquals(listOf(0x1A, 0x45, 0xDF, 0xA3), head.map { it.toInt() and 0xFF }) // EBML magic
        for (pos in listOf(clip.size / 2, 5_000_000L, clip.size - 10)) {
            val a = ByteArray(8); val n = rab.read(pos, a, 0, 8)
            val b = ByteArray(n); f.read(b, pos, 0, n)
            assertTrue(a.copyOf(n).contentEquals(b))
        }
        f.close()
        SmbPool.invalidate(s.id)
    }

    @Test fun wrongPasswordIsAuthError() = runBlocking {
        assumeTrue(host != null)
        try {
            SmbRepository().testConnection(server("wrong-pass"))
            fail("expected auth failure")
        } catch (e: SmbError) {
            assertEquals(SmbErrorKind.AUTH, e.kind)
            assertTrue(e.message!!.startsWith("Неверный логин или пароль"))
        }
    }

    @Test fun unreachableHostIsNetworkError() = runBlocking {
        assumeTrue(host != null)
        try {
            SmbRepository().testConnection(server().copy(id = "unreach", host = "10.255.255.1"))
            fail("expected failure")
        } catch (e: SmbError) {
            assertTrue(e.kind.toString(), e.kind in setOf(SmbErrorKind.UNREACHABLE, SmbErrorKind.TIMEOUT, SmbErrorKind.NETWORK))
        }
    }
}
