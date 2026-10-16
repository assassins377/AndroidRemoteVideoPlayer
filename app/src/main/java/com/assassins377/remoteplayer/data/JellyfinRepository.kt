package com.assassins377.remoteplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Jellyfin (and Emby-compatible) server: login with user/password, libraries as folders, direct static streaming,
 * played-state sync. Paths are "<viewId>/<folderId>/.../<itemId>.<container>".
 */
class JellyfinRepository : RemoteFileRepository {
    data class Session(val userId: String, val token: String)

    companion object {
        private val sessions = ConcurrentHashMap<String, Session>()
        private const val CLIENT = "MediaBrowser Client=\"RemotePlayer\", Device=\"Android\", DeviceId=\"rvp-android\", Version=\"1.0\""
        fun invalidate(serverId: String) { sessions.remove(serverId) }

        fun base(server: ServerConfig) = server.host.trimEnd('/')
        fun itemId(path: String) = path.trim('/').substringAfterLast('/').substringBefore('.')

        /** Parses /Items response into folders and playable videos (name gets the container as extension). */
        fun parseItems(body: String, parentPath: String): List<Pair<RemoteFile, Boolean?>> {
            val items = Json.parseToJsonElement(body).jsonObject["Items"]?.jsonArray.orEmpty()
            val prefix = parentPath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            return items.mapNotNull { e ->
                val o = e.jsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                val id = s("Id") ?: return@mapNotNull null
                val name = s("Name") ?: id
                val type = s("Type").orEmpty()
                val isFolder = o["IsFolder"]?.jsonPrimitive?.booleanOrNull == true
                val played = o["UserData"]?.jsonObject?.get("Played")?.jsonPrimitive?.booleanOrNull
                if (isFolder) RemoteFile(name, prefix + id, true) to played
                else if (type in setOf("Movie", "Episode", "Video", "MusicVideo") || s("MediaType") == "Video") {
                    val container = s("Container")?.substringBefore(',')?.ifBlank { null } ?: "mkv"
                    val display = buildString {
                        if (type == "Episode") {
                            s("SeriesName")?.let { append(it).append(' ') }
                            val se = o["ParentIndexNumber"]?.jsonPrimitive?.intOrNull; val ep = o["IndexNumber"]?.jsonPrimitive?.intOrNull
                            if (se != null && ep != null) append("S%02dE%02d ".format(se, ep))
                            append(name)
                        } else { append(name); o["ProductionYear"]?.jsonPrimitive?.intOrNull?.let { append(" ($it)") } }
                    }.replace('/', ' ')
                    RemoteFile("$display.$container", "$prefix$id.$container", false, size = o["MediaSources"]?.jsonArray?.firstOrNull()?.jsonObject?.get("Size")?.jsonPrimitive?.longOrNull ?: 0) to played
                } else null
            }
        }

        fun streamUrl(server: ServerConfig, path: String, token: String) =
            "${base(server)}/Videos/${itemId(path)}/stream?static=true&api_key=$token"
    }

    private fun client(server: ServerConfig): OkHttpClient = HttpClients.forServer(server.copy(username = "", password = ""))

    /** Blocking login (cached per server). */
    fun session(server: ServerConfig): Session = sessions[server.id] ?: run {
        val body = buildJsonObject { put("Username", server.username); put("Pw", server.password) }.toString()
        val req = Request.Builder().url(base(server) + "/Users/AuthenticateByName")
            .header("Authorization", CLIENT).header("X-Emby-Authorization", CLIENT)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        client(server).newCall(req).execute().use { r ->
            if (r.code == 401) throw SmbError(SmbErrorKind.AUTH, IOException("HTTP 401"))
            HttpErrors.check(r)
            val o = Json.parseToJsonElement(r.body!!.string()).jsonObject
            Session(o["User"]!!.jsonObject["Id"]!!.jsonPrimitive.content, o["AccessToken"]!!.jsonPrimitive.content)
        }.also { sessions[server.id] = it }
    }

    private fun get(server: ServerConfig, url: String): String {
        val s = session(server)
        val req = Request.Builder().url(url).header("X-Emby-Token", s.token).header("Accept", "application/json").build()
        return client(server).newCall(req).execute().use { r ->
            if (r.code == 401) { invalidate(server.id); throw SmbError(SmbErrorKind.AUTH, IOException("HTTP 401")) }
            HttpErrors.check(r); r.body!!.string()
        }
    }

    override suspend fun list(server: ServerConfig, path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        try {
            val s = session(server)
            val parent = path.trim('/').substringAfterLast('/')
            val url = if (parent.isEmpty()) "${base(server)}/Users/${s.userId}/Views"
            else "${base(server)}/Users/${s.userId}/Items?ParentId=$parent&Fields=MediaSources,UserData&SortBy=SortName"
            val parsed = parseItems(get(server, url), path)
            // Played state from Jellyfin → local watched marks.
            parsed.forEach { (f, played) ->
                if (!f.isDirectory && played != null) com.assassins377.remoteplayer.settings.WatchedStore.set(com.assassins377.remoteplayer.settings.WatchedStore.key(server.id, f.path), played)
            }
            parsed.map { it.first }
        } catch (e: SmbError) { throw e } catch (e: Exception) { throw HttpErrors.wrap(e) }
    }

    override fun playbackUri(server: ServerConfig, file: RemoteFile): String = HttpUri.build(server.id, file.path)

    suspend fun testConnection(server: ServerConfig) { invalidate(server.id); list(server, "") }

    /** Marks the item played/unplayed on the server (best effort). */
    suspend fun setPlayed(server: ServerConfig, path: String, played: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            val s = session(server)
            val b = Request.Builder().url("${base(server)}/Users/${s.userId}/PlayedItems/${itemId(path)}").header("X-Emby-Token", s.token)
            val req = if (played) b.post(ByteArray(0).toRequestBody()).build() else b.delete().build()
            client(server).newCall(req).execute().close()
        }
    }

    /** Resolved stream URL for the data source (blocking). */
    fun resolveStream(server: ServerConfig, path: String): String = streamUrl(server, path, session(server).token)
}
