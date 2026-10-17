package com.assassins377.remoteplayer

import androidx.media3.datasource.DefaultHttpDataSource
import com.assassins377.remoteplayer.player.PlayerFrame
import com.assassins377.remoteplayer.player.StreamingMediaDataSource
import com.assassins377.remoteplayer.ui.TestApp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class ThumbnailTest {
    @Test fun timeoutReleasesWithoutWaitingForBlockedWork() = kotlinx.coroutines.runBlocking {
        val t0 = System.currentTimeMillis()
        val r = com.assassins377.remoteplayer.player.Thumbnails.runWithTimeout(200) { a -> while (!a.isAborted) Thread.sleep(20); Thread.sleep(3000); "late" }
        org.junit.Assert.assertNull(r); org.junit.Assert.assertTrue(System.currentTimeMillis() - t0 < 2000)
        org.junit.Assert.assertEquals("ok", com.assassins377.remoteplayer.player.Thumbnails.runWithTimeout(2000) { "ok" })
    }

    @Test fun framePosition() {
        assertEquals(0, PlayerFrame.positionUs(0))
        assertEquals(600_000_000L, PlayerFrame.positionUs(6_000_000))
        assertEquals(5_000_000L, PlayerFrame.positionUs(20_000))
        assertEquals(2_000_000L, PlayerFrame.positionUs(4_000))
    }

    /** Random-access reads over HTTP ranges match the file on disk (needs HTTP_IT_URL + /workspace/smbshare). */
    @Test fun streamingRandomAccessOverHttp() {
        val base = System.getenv("HTTP_IT_URL"); val local = File("/workspace/smbshare/Movies/Test Clip.mkv")
        assumeTrue(base != null && local.exists())
        val src = StreamingMediaDataSource({ DefaultHttpDataSource.Factory().createDataSource() }, android.net.Uri.parse(base + "Movies/Test%20Clip.mkv"))
        assertEquals(local.length(), src.size)
        RandomAccessFile(local, "r").use { raf ->
            for (p in listOf(0L, local.length() / 2, 1234L, local.length() - 100)) {
                val a = ByteArray(64); val n = src.readAt(p, a, 0, 64)
                val b = ByteArray(n); raf.seek(p); raf.readFully(b)
                assertArrayEquals(b, a.copyOf(n))
            }
        }
        assertEquals(-1, src.readAt(local.length(), ByteArray(4), 0, 4))
        src.close()
    }
}
