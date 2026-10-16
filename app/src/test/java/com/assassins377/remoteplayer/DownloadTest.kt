package com.assassins377.remoteplayer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.media3.datasource.DefaultHttpDataSource
import com.assassins377.remoteplayer.download.DownloadStore
import com.assassins377.remoteplayer.download.FileCopier
import com.assassins377.remoteplayer.ui.TestApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class DownloadTest {
    @Test fun workNamesDontCollide() {
        org.junit.Assert.assertEquals("Aa".hashCode(), "BB".hashCode())
        org.junit.Assert.assertNotEquals(com.assassins377.remoteplayer.download.DownloadStore.tagFor("smb://x/Aa.mkv"), com.assassins377.remoteplayer.download.DownloadStore.tagFor("smb://x/BB.mkv"))
    }

    @Test fun safeNameAndStore() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        DownloadStore.init(app, File(app.filesDir, "dl"))
        val n = DownloadStore.safeName("rvp-smb://a/x", "a:b?.mkv")
        assertTrue(n.endsWith("_a_b_.mkv")); assertEquals(n, DownloadStore.safeName("rvp-smb://a/x", "a:b?.mkv"))
        assertNull(DownloadStore.localFile("nope"))
    }

    /** Copy over HTTP with resume from a partial file (nginx test server). */
    @Test fun copyResumes(): Unit = runBlocking {
        val base = System.getenv("HTTP_IT_URL"); val local = File("/workspace/smbshare/Movies/Test Clip.mkv")
        assumeTrue(base != null && local.exists())
        val dest = File.createTempFile("dltest", ".mkv"); dest.writeBytes(local.readBytes().copyOf(123_456)) // partial
        var last = 0L
        val n = FileCopier.copy(DefaultHttpDataSource.Factory().createDataSource(), base + "Movies/Test%20Clip.mkv", dest) { d, _ -> last = d }
        assertEquals(local.length(), n); assertEquals(local.length(), last)
        assertArrayEquals(local.readBytes(), dest.readBytes())
        dest.delete()
    }
}
