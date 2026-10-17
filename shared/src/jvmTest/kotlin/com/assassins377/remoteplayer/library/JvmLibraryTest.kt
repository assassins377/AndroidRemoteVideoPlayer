package com.assassins377.remoteplayer.library

import com.assassins377.remoteplayer.core.MemoryKeyValue
import com.assassins377.remoteplayer.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Library index on the desktop driver (bundled SQLite + FTS4) through the shared Room code. */
class JvmLibraryTest {
    private val srv = ServerConfig("a", "NAS", ServerType.SMB, "h", "media")
    private val repo = object : RemoteFileRepository {
        override suspend fun list(server: ServerConfig, path: String) = when (path) {
            "" -> listOf(RemoteFile("Movies", "Movies", true), RemoteFile("Брат.1997.mkv", "Брат.1997.mkv", false),
                RemoteFile("Breaking.Bad.S01E01.mkv", "Breaking.Bad.S01E01.mkv", false))
            "Movies" -> listOf(RemoteFile("The.Matrix.1999.mkv", "Movies/The.Matrix.1999.mkv", false))
            else -> throw SmbError(SmbErrorKind.NOT_FOUND, null)
        }
        override fun playbackUri(server: ServerConfig, file: RemoteFile) = "x://${file.path}"
    }

    @Test fun scanSearchSeriesOnBundledSqlite() = runBlocking {
        val db = LibraryDatabase.inMemory()
        LibraryStore.init(db, MemoryKeyValue(), null)
        LibraryStore.refreshNow(listOf(srv)) { repo }
        val dao = db.dao()
        assertEquals(3, dao.queryCount(LibrarySql.count("", null, listOf("a"))))
        assertEquals(listOf("Брат (1997)"), dao.query(LibrarySql.list("брат", null, LibrarySort.TITLE, listOf("a"), 10)).map { it.title })
        assertEquals(listOf("The Matrix (1999)"), dao.query(LibrarySql.list("\"the matrix\"", null, LibrarySort.TITLE, listOf("a"), 10)).map { it.title })
        assertEquals(1, dao.queryCount(LibrarySql.count("", null, listOf("a"), LibraryMode.SERIES)))
        assertEquals(listOf("Breaking Bad"), dao.strings(LibrarySql.suggestions("break", listOf("a"))!!))
        db.close()
    }

    @Test fun titleParserShared() {
        val p = com.assassins377.remoteplayer.meta.TitleParser.parse("The.Matrix.1999.1080p.BluRay.x264.mkv")
        assertEquals("The Matrix", p.title); assertEquals(1999, p.year)
    }
}
