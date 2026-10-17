package com.assassins377.remoteplayer.data

import com.hierynomus.msfscc.FileAttributes
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import com.assassins377.remoteplayer.core.CoreSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbRepository : RemoteFileRepository {

    override suspend fun list(server: ServerConfig, path: String): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            SmbPool.withRetry(server) {
                SmbPool.share(server).list(path.trimStart('/').replace('/', '\\'))
            }
                .filter { it.fileName != "." && it.fileName != ".." }
                .map {
                    val isDir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    RemoteFile(
                        name = it.fileName,
                        path = if (path.isBlank()) it.fileName else "${path.trimEnd('/')}/${it.fileName}",
                        isDirectory = isDir,
                        size = it.endOfFile,
                        modified = it.lastWriteTime.toEpochMillis(),
                        hidden = it.fileName.startsWith(".") ||
                            (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L,
                    )
                }
                .filter { it.isDirectory || it.isVideo || it.isSubtitle }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }

    /** Credentials are NOT in the link; see [SmbUri]. */
    override fun playbackUri(server: ServerConfig, file: RemoteFile): String = SmbUri.build(server.id, file.path)

    /** Connects, authenticates and (if a share is set) lists its root. Throws [SmbError]. */
    suspend fun testConnection(server: ServerConfig) = withContext(Dispatchers.IO) {
        SmbPool.invalidate(server.id) // always test with fresh credentials
        SmbPool.withRetry(server, RetryPolicy(maxAttempts = minOf(2, CoreSettings.current.retryCount))) {
            if (server.share.isBlank()) SmbPool.session(server) else SmbPool.share(server).list("")
        }
        Unit
    }

    /**
     * Lists disk shares via MS-SRVS (NetShareEnum over IPC$, smbj-rpc).
     * Hidden/admin shares ($) and non-disk shares are filtered out. Throws [SmbError].
     */
    suspend fun listShares(server: ServerConfig): List<String> = withContext(Dispatchers.IO) {
        SmbPool.withRetry(server, RetryPolicy(maxAttempts = minOf(2, CoreSettings.current.retryCount))) {
            val transport = SMBTransportFactories.SRVSVC.getTransport(SmbPool.session(server))
            ServerService(transport).shares1
                .filter { (it.type and 0x0FFFFFFF) == 0 } // STYPE_DISKTREE
                .map { it.netName }
                .filter { !it.endsWith("$") }
                .sortedBy { it.lowercase() }
        }
    }
}

fun ServerConfig.authContext(): com.hierynomus.smbj.auth.AuthenticationContext =
    if (username.isBlank()) com.hierynomus.smbj.auth.AuthenticationContext.anonymous()
    else com.hierynomus.smbj.auth.AuthenticationContext(username, password.toCharArray(), domain.ifBlank { null })
