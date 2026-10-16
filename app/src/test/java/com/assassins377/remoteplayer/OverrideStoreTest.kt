package com.assassins377.remoteplayer

import com.assassins377.remoteplayer.meta.OverrideStore
import com.assassins377.remoteplayer.meta.TitleParser
import com.assassins377.remoteplayer.meta.TmdbClient
import com.assassins377.remoteplayer.meta.TmdbMeta
import com.assassins377.remoteplayer.meta.TmdbOverride
import org.junit.Assert.*
import org.junit.Test

class OverrideStoreTest {
    private val matrix = TmdbMeta(603, "Матрица", "The Matrix", 1999, posterPath = "/m.jpg")

    @Test fun setGetResetAndHide() {
        val s = OverrideStore()
        val k = OverrideStore.key("srv1", "/Movies/Film.mkv")
        assertEquals("srv1/Movies/Film.mkv", k)
        assertNull(s.get(k))
        s.set(k, TmdbOverride(matrix))
        assertEquals(603, s.get(k)?.meta?.id); assertFalse(s.get(k)!!.hidden)
        s.set(k, TmdbOverride(null)); assertTrue(s.get(k)!!.hidden)
        s.set(k, null); assertNull(s.get(k)); assertEquals(0, s.size())
    }

    @Test fun keysAreSeparatedByServerAndPath() {
        val s = OverrideStore()
        s.set(OverrideStore.key("a", "x.mkv"), TmdbOverride(matrix))
        assertNull(s.get(OverrideStore.key("b", "x.mkv")))
        assertNull(s.get(OverrideStore.key("a", "y.mkv")))
    }

    @Test fun jsonRoundTripKeepsManualAndHidden() {
        val s = OverrideStore()
        s.set("a/1.mkv", TmdbOverride(matrix)); s.set("a/Show", TmdbOverride(null, tv = true))
        val r = OverrideStore(); r.loadJson(s.toJson())
        assertEquals("Матрица", r.get("a/1.mkv")?.meta?.title)
        assertTrue(r.get("a/Show")!!.hidden); assertTrue(r.get("a/Show")!!.tv)
    }

    @Test fun parseAllResults() {
        val body = """{"results":[{"id":1,"title":"A","release_date":"2000-01-01"},{"id":2,"title":"B"},{"title":"no id"}]}"""
        assertEquals(listOf(1, 2), TmdbClient.parseSearchAll(body, tv = false).map { it.id })
    }

    @Test fun seriesFolders() {
        val p = TitleParser.parseFolder("Breaking.Bad.S02.1080p")!!
        assertEquals("Breaking Bad", p.title); assertTrue(p.isSeries); assertEquals("tv|breaking bad|", p.key)
        assertEquals("Слово пацана", TitleParser.parseFolder("Слово пацана (2023)")!!.title)
        assertNull(TitleParser.parseFolder("Movies")); assertNull(TitleParser.parseFolder("Сериалы"))
        assertNull(TitleParser.parseFolder("Season 2")); assertNull(TitleParser.parseFolder("Сезон 1"))
    }
}
