package com.assassins377.remoteplayer.meta

import com.assassins377.remoteplayer.data.RemoteFile
import org.junit.Assert.*
import org.junit.Test

class SeriesGrouperTest {
    private fun f(p: String) = RemoteFile(p.substringAfterLast('/'), p, false, 1)

    @Test fun groupsAndOrders() {
        val files = listOf(f("Show/Season 2/Show.S02E01.mkv"), f("Show/Season 1/Show.S01E10.mkv"), f("Show/Season 1/Show.S01E02.mkv"),
            f("Show/Season 3/Episode 1.mkv"), f("Show/notes.txt"))
        val g = SeriesGrouper.group(files)
        assertEquals(listOf(1, 2, 3), g.keys.toList())
        assertEquals(listOf(2, 10), g[1]!!.map { it.episode })
        val pl = SeriesGrouper.playlist(g)
        assertEquals("Show.S01E10.mkv", SeriesGrouper.next(pl, pl[0])!!.name)
        assertNull(SeriesGrouper.next(pl, pl.last()))
    }

    @Test fun detection() {
        assertTrue(SeriesGrouper.looksLikeSeries(listOf(f("a.S01E01.mkv"), f("a.S01E02.mkv"))))
        assertTrue(SeriesGrouper.looksLikeSeries(listOf(RemoteFile("Сезон 1", "x/Сезон 1", true))))
        assertFalse(SeriesGrouper.looksLikeSeries(listOf(f("Movie.2020.mkv"), RemoteFile("Movies 2", "Movies 2", true))))
    }
}
