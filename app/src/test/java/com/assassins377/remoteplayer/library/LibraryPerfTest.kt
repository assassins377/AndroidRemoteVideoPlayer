package com.assassins377.remoteplayer.library

import com.assassins377.remoteplayer.ui.TestApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

/** 50 000 entries on 3 servers: insert, FTS search, sorted/filtered pages and count must stay fast. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class LibraryPerfTest {
    @Test fun fiftyThousandEntries() = runBlocking {
        val db = initLibraryForTest(); val dao = db.dao()
        val words = listOf("Matrix", "Брат", "Inception", "Avatar", "Звёздные", "Войны", "Alien", "Терминатор", "Dune", "Матрица")
        val servers = listOf("s1", "s2", "s3")
        val rows = (0 until 50_000).map { i ->
            val name = "${words[i % 10]}.${words[(i / 10) % 10]}.$i.${1950 + i % 75}.1080p.mkv"
            LibraryRows.of(servers[i % 3], "dir${i % 200}/$name", name, i.toLong(), 0, i.toLong(), 1)
        }
        val tInsert = measureTimeMillis { rows.chunked(500).forEach { dao.upsert(it) } }
        assertEquals(50_000, dao.count())
        fun t(label: String, block: suspend () -> Int): Long {
            runBlocking { block() } // warm-up
            var n = 0; val ms = measureTimeMillis { runBlocking { n = block() } }
            println("PERF $label: ${ms} ms ($n rows)"); return ms
        }
        val times = listOf(
            t("page title") { dao.query(LibrarySql.list("", null, LibrarySort.TITLE, servers, 200)).size },
            t("page year") { dao.query(LibrarySql.list("", null, LibrarySort.YEAR, servers, 200)).size },
            t("page added, server s2, offset 10000") { dao.query(LibrarySql.list("", listOf("s2"), LibrarySort.ADDED, servers, 200, 10_000)).size },
            t("fts 'матр'") { dao.query(LibrarySql.list("матр", null, LibrarySort.TITLE, servers, 200)).size },
            t("fts 'звездные войны'") { dao.query(LibrarySql.list("звездные войны", null, LibrarySort.TITLE, servers, 200)).size },
            t("fts 'dune 49998'") { dao.query(LibrarySql.list("dune 49998", null, LibrarySort.TITLE, servers, 200)).size },
            t("count all") { dao.queryCount(LibrarySql.count("", null, servers)) },
            t("count fts 'alien'") { dao.queryCount(LibrarySql.count("alien", null, servers)) },
        )
        println("PERF insert 50000: $tInsert ms")
        assertEquals(1, dao.query(LibrarySql.list("dune 49998", null, LibrarySort.TITLE, servers, 200)).size)
        assertEquals(9_500, dao.queryCount(LibrarySql.count("alien", null, servers)))
        assertTrue("times=$times", times.all { it < 1500 })
        assertTrue("insert=$tInsert", tInsert < 60_000)
        db.close()
    }
}
