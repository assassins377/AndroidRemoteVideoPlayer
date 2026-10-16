package com.assassins377.remoteplayer.data

/**
 * HTTP: no directory listing (yet). The "server" is a direct video URL or base URL;
 * the browser shows a single entry that plays [ServerConfig.host] directly.
 * TODO: parse Apache/nginx autoindex HTML for real listing.
 */
class HttpRepository : RemoteFileRepository {
    override suspend fun list(server: ServerConfig, path: String): List<RemoteFile> =
        listOf(RemoteFile(name = server.host.substringAfterLast('/').ifBlank { server.host }, path = "", isDirectory = false))

    override fun playbackUri(server: ServerConfig, file: RemoteFile): String =
        if (file.path.isBlank()) server.host else "${server.host.trimEnd('/')}/${file.path}"
}
