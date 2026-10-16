package com.assassins377.remoteplayer.data

/**
 * WebDAV stub. README lists WebDAV, but no WebDAV library is in the dependencies.
 * Suggested: sardine-android (com.github.thegrizzlylabs:sardine-android via JitPack),
 * use Sardine.list(url) for PROPFIND; play via Media3 HTTP DataSource with Basic auth header.
 */
class WebDavRepository : RemoteFileRepository {
    override suspend fun list(server: ServerConfig, path: String): List<RemoteFile> =
        throw NotImplementedError("WebDAV is not implemented yet (add sardine-android)")

    override fun playbackUri(server: ServerConfig, file: RemoteFile): String =
        "${server.host.trimEnd('/')}/${file.path}"
}
