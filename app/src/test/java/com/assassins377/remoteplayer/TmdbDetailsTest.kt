package com.assassins377.remoteplayer

import com.assassins377.remoteplayer.meta.TmdbClient
import org.junit.Assert.*
import org.junit.Test

class TmdbDetailsTest {
    @Test fun manualTypeWins() {
        val m = com.assassins377.remoteplayer.meta.TmdbMeta(1, "X")
        org.junit.Assert.assertFalse(com.assassins377.remoteplayer.meta.TmdbRepository.isTv(com.assassins377.remoteplayer.meta.TmdbOverride(m, tv = false), true))
        org.junit.Assert.assertTrue(com.assassins377.remoteplayer.meta.TmdbRepository.isTv(null, true))
    }

    @Test fun parseMovie() {
        val body = """{"overview":"Нео","vote_average":8.2,"runtime":136,"tagline":"Добро пожаловать","genres":[{"id":1,"name":"Фантастика"},{"id":2,"name":"Боевик"}],
          "credits":{"cast":[{"name":"Keanu Reeves","character":"Neo","profile_path":"/k.jpg"},{"name":"Carrie-Anne Moss","character":"Trinity"}]},
          "videos":{"results":[{"site":"Vimeo","type":"Trailer","key":"v"},{"site":"YouTube","type":"Teaser","key":"t"},{"site":"YouTube","type":"Trailer","key":"abc"}]}}"""
        val d = TmdbClient.parseDetails(body, tv = false)
        assertEquals(listOf("Фантастика", "Боевик"), d.genres); assertEquals(136, d.runtimeMin)
        assertEquals("https://www.youtube.com/watch?v=abc", d.trailerUrl)
        assertEquals("https://image.tmdb.org/t/p/w185/k.jpg", d.cast[0].photoUrl()); assertNull(d.cast[1].photoUrl())
    }
    @Test fun parseTv() {
        val d = TmdbClient.parseDetails("""{"episode_run_time":[47],"number_of_seasons":5,"videos":{"results":[]}}""", tv = true)
        assertEquals(47, d.runtimeMin); assertEquals(5, d.seasons); assertNull(d.trailerUrl); assertTrue(d.cast.isEmpty())
    }
}
