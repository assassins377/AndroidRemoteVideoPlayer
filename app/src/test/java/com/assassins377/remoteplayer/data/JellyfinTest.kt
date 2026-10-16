package com.assassins377.remoteplayer.data

import com.assassins377.remoteplayer.settings.WatchedStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class JellyfinTest {
    private val items = """{"Items":[
      {"Id":"f1","Name":"Breaking Bad","IsFolder":true,"Type":"Series"},
      {"Id":"m1","Name":"The Matrix","IsFolder":false,"Type":"Movie","ProductionYear":1999,"Container":"mkv,webm","MediaSources":[{"Size":1234}],"UserData":{"Played":true}},
      {"Id":"e1","Name":"Pilot","IsFolder":false,"Type":"Episode","SeriesName":"Breaking Bad","ParentIndexNumber":1,"IndexNumber":1,"Container":"mp4"},
      {"Id":"a1","Name":"Song","IsFolder":false,"Type":"Audio"}]}"""

    @Test fun sessionBoundToConfig() {
        val a = com.assassins377.remoteplayer.data.ServerConfig("j", "J", com.assassins377.remoteplayer.data.ServerType.JELLYFIN, "http://h:8096", username = "alice", password = "p")
        org.junit.Assert.assertEquals(JellyfinRepository.fingerprint(a), JellyfinRepository.fingerprint(a.copy(name = "x")))
        org.junit.Assert.assertNotEquals(JellyfinRepository.fingerprint(a), JellyfinRepository.fingerprint(a.copy(username = "bob")))
        org.junit.Assert.assertNotEquals(JellyfinRepository.fingerprint(a), JellyfinRepository.fingerprint(a.copy(host = "http://other")))
    }

    @Test fun parse() {
        val r = JellyfinRepository.parseItems(items, "v1").map { it.first }
        assertEquals(listOf("Breaking Bad", "The Matrix (1999).mkv", "Breaking Bad S01E01 Pilot.mp4"), r.map { it.name })
        assertEquals(listOf("v1/f1", "v1/m1.mkv", "v1/e1.mp4"), r.map { it.path })
        assertTrue(r[1].isVideo && r[0].isDirectory); assertEquals(1234, r[1].size)
        assertEquals("m1", JellyfinRepository.itemId("v1/m1.mkv"))
    }

    @Test fun loginListStreamAndPlayedSync() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setBody("""{"User":{"Id":"u1"},"AccessToken":"tok"}"""))
        mock.enqueue(MockResponse().setBody("""{"Items":[{"Id":"v1","Name":"Фильмы","IsFolder":true}]}"""))
        mock.enqueue(MockResponse().setBody(items))
        mock.enqueue(MockResponse().setResponseCode(200))
        mock.start()
        val s = ServerConfig("jf-test", "JF", ServerType.JELLYFIN, mock.url("/").toString(), username = "me", password = "pw")
        val repo = JellyfinRepository(); JellyfinRepository.invalidate(s.id)
        assertEquals(listOf("Фильмы"), repo.list(s, "").map { it.name })
        val auth = mock.takeRequest()
        assertEquals("/Users/AuthenticateByName", auth.path); assertTrue(auth.body.readUtf8().contains("\"Username\":\"me\""))
        assertTrue(auth.getHeader("Authorization")!!.startsWith("MediaBrowser"))
        assertEquals("/Users/u1/Views", mock.takeRequest().path)
        repo.list(s, "v1")
        val req = mock.takeRequest(); assertTrue(req.path!!.startsWith("/Users/u1/Items?ParentId=v1")); assertEquals("tok", req.getHeader("X-Emby-Token"))
        assertTrue(WatchedStore.isWatched(WatchedStore.key(s.id, "v1/m1.mkv")))
        assertTrue(repo.resolveStream(s, "v1/m1.mkv").endsWith("/Videos/m1/stream?static=true&api_key=tok"))
        repo.setPlayed(s, "v1/m1.mkv", true)
        val p = mock.takeRequest(); assertEquals("POST", p.method); assertEquals("/Users/u1/PlayedItems/m1", p.path)
        mock.shutdown()
    }

    @Test fun authError() = runBlocking {
        val mock = MockWebServer(); mock.enqueue(MockResponse().setResponseCode(401)); mock.start()
        val s = ServerConfig("jf-bad", "JF", ServerType.JELLYFIN, mock.url("/").toString(), username = "me", password = "x")
        try { JellyfinRepository().testConnection(s); fail() } catch (e: SmbError) { assertEquals(SmbErrorKind.AUTH, e.kind) }
        mock.shutdown()
    }
}
