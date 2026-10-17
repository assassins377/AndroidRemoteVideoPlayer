package com.assassins377.remoteplayer.desktop

import com.assassins377.remoteplayer.data.RangeSource
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.data.ServerType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val data = ByteArray(1_000_000) { (it % 251).toByte() }
    private val srv = ServerConfig("s1", "NAS", ServerType.HTTP, "http://x")

    private fun src() = object : RangeSource {
        override val size = data.size.toLong()
        override fun open(from: Long) = data.inputStream(from.toInt(), data.size - from.toInt())
        override fun close() {}
    }

    @Test fun downloadsResumesPartAndPersists() = runBlocking {
        val folder = tmp.newFolder("dl")
        // A previous partial download is continued from its byte offset.
        File(folder, "NAS").mkdirs(); File(folder, "NAS/a.mkv.part").writeBytes(data.copyOf(1234))
        val state = File(tmp.root, "downloads.json")
        val d = Downloads(state, File(tmp.root, "folder"), folder, { _, _ -> src() })
        val item = d.enqueue(srv, "Movies/a.mkv", "a.mkv")
        withTimeout(10_000) { d.awaitIdle() }
        assertEquals(DownloadStatus.DONE, d.items.value.single().status)
        assertArrayEquals(data, File(item.target).readBytes())
        assertEquals(File(item.target), d.localFile("s1", "/Movies/a.mkv"))
        // Same file again is de-duplicated; state survives a restart.
        assertEquals(item.id, d.enqueue(srv, "Movies/a.mkv", "a.mkv").id)
        val d2 = Downloads(state, File(tmp.root, "folder"), folder, { _, _ -> src() })
        assertEquals(DownloadStatus.DONE, d2.items.value.single().status)
    }

    @Test fun failureAndFolderSetting() = runBlocking {
        val d = Downloads(File(tmp.root, "s.json"), File(tmp.root, "folder"), tmp.newFolder("def"), { _, _ -> null })
        d.enqueue(srv, "b.mkv", "b.mkv")
        withTimeout(10_000) { d.awaitIdle() }
        assertEquals(DownloadStatus.FAILED, d.items.value.single().status)
        val custom = tmp.newFolder("custom"); d.folder = custom
        assertEquals(custom.absolutePath, d.folder.absolutePath)
    }
}
