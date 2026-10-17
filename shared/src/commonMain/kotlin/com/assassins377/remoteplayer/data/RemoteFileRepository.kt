package com.assassins377.remoteplayer.data

/** Abstraction over a remote file source (SMB, HTTP, WebDAV). */
interface RemoteFileRepository {
    suspend fun list(server: ServerConfig, path: String): List<RemoteFile>

    /** Uri to hand to the player. SMB uses the custom "smb://" scheme handled by SmbDataSource. */
    fun playbackUri(server: ServerConfig, file: RemoteFile): String
}

/** Repository per server type. */
object Repositories {
    fun forType(type: ServerType): RemoteFileRepository = when (type) {
        ServerType.SMB -> SmbRepository()
        ServerType.HTTP -> HttpRepository()
        ServerType.WEBDAV -> WebDavRepository()
        ServerType.JELLYFIN -> JellyfinRepository()
    }
}
