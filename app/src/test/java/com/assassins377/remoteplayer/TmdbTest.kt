package com.assassins377.remoteplayer

import com.assassins377.remoteplayer.meta.TitleParser
import com.assassins377.remoteplayer.meta.TmdbCache
import com.assassins377.remoteplayer.meta.TmdbClient
import com.assassins377.remoteplayer.meta.TmdbMeta
import org.junit.Assert.*
import org.junit.Test

class TmdbTest {
    @Test fun cacheTtlHitAndMiss() {
        var t = 0L
        val c = TmdbCache(hitTtlMs = 100, missTtlMs = 10, now = { t })
        c.put("a", TmdbMeta(1, "Матрица")); c.put("b", null)
        assertEquals("Матрица", c.get("a")?.meta?.title); assertNotNull(c.get("b"))
        t = 50; assertNotNull(c.get("a")); assertNull(c.get("b"))   // miss expired
        t = 150; assertNull(c.get("a"))                              // hit expired
    }

    @Test fun cacheJsonRoundTrip() {
        val c = TmdbCache(now = { 5 }); c.put("movie|брат|1997", TmdbMeta(2, "Брат", year = 1997, posterPath = "/p.jpg"))
        val d = TmdbCache(now = { 5 }); d.loadJson(c.toJson())
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", d.get("movie|брат|1997")?.meta?.posterUrl())
    }

    @Test fun cacheKeyFromParsedTitle() {
        assertEquals("movie|the matrix|1999", TitleParser.parse("The.Matrix.1999.1080p.mkv").key)
        assertEquals("tv|breaking bad|", TitleParser.parse("Breaking.Bad.S01E01.mkv").key)
    }

    @Test fun parsesMovieAndTvSearch() {
        val movie = """{"page":1,"results":[{"id":603,"title":"Матрица","original_title":"The Matrix","release_date":"1999-03-30",
            "overview":"Хакер Нео...","poster_path":"/abc.jpg","vote_average":8.2}],"total_results":1}"""
        val m = TmdbClient.parseSearch(movie, tv = false)!!
        assertEquals(603, m.id); assertEquals("Матрица", m.title); assertEquals(1999, m.year); assertEquals(8.2, m.rating, 0.01)
        val tv = """{"results":[{"id":1396,"name":"Во все тяжкие","original_name":"Breaking Bad","first_air_date":"2008-01-20","poster_path":null}]}"""
        val t = TmdbClient.parseSearch(tv, tv = true)!!
        assertEquals("Во все тяжкие", t.title); assertNull(t.posterUrl())
        assertNull(TmdbClient.parseSearch("""{"results":[]}""", false))
    }

    @Test fun keyKind() {
        assertFalse(TmdbClient.isBearer("0123456789abcdef0123456789abcdef"))
        assertTrue(TmdbClient.isBearer("eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIxMjMifQ.sig"))
    }
}
