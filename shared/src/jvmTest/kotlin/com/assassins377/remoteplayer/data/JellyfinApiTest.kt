package com.assassins377.remoteplayer.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class JellyfinApiTest {
    @Test fun loginResumeAndProgress() {
        val requests = mutableListOf<RecordedRequest>()
        val mws = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(requests) { requests += request }
                    val p = request.path!!
                    return when {
                        p.startsWith("/Users/AuthenticateByName") -> MockResponse().setBody("""{"User":{"Id":"u1"},"AccessToken":"tok"}""")
                        p.startsWith("/Users/u1/Items/Resume") -> MockResponse().setBody("""{"Items":[{"Id":"m1","Name":"Film","Type":"Movie","Container":"mp4","ProductionYear":2020}]}""")
                        p.startsWith("/Users/u1/Items/m1") -> MockResponse().setBody("""{"Id":"m1","UserData":{"PlaybackPositionTicks":600000000}}""")
                        else -> MockResponse().setResponseCode(204)
                    }
                }
            }
            start()
        }
        val srv = ServerConfig(id = "jtest", name = "J", type = ServerType.JELLYFIN, host = mws.url("/").toString(), username = "a", password = "b")
        val repo = JellyfinRepository()
        val items = runBlocking { repo.resumeItems(srv) }
        assertEquals(listOf("Film (2020).mp4"), items.map { it.name })
        assertEquals(60_000L, repo.resumePositionMs(srv, items[0].path))
        repo.reportProgress(srv, items[0].path, 5_000, paused = true, stopped = true)
        val stop = synchronized(requests) { requests.last() }
        assertEquals("/Sessions/Playing/Stopped", stop.path)
        assertTrue(stop.body.readUtf8().contains(""""PositionTicks":50000000"""))
        assertEquals("tok", stop.getHeader("X-Emby-Token"))
        mws.shutdown()
    }
}
