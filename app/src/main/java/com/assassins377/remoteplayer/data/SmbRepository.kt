package com.assassins377.remoteplayer.data

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbRepository(private val client: SMBClient = SmbClientHolder.client) : RemoteFileRepository {

    override suspend fun list(server: ServerConfig, path: String): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            client.connect(server.host).use { connection ->
                val session = connection.authenticate(server.authContext())
                (session.connectShare(server.share) as DiskShare).use { share ->
                    share.list(path.trimStart('/').replace('/', '\\'))
                        .filter { it.fileName != "." && it.fileName != ".." }
                        .map {
                            val isDir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                            RemoteFile(
                                name = it.fileName,
                                path = if (path.isBlank()) it.fileName else "${path.trimEnd('/')}/${it.fileName}",
                                isDirectory = isDir,
                                size = it.endOfFile,
                            )
                        }
                        .filter { it.isDirectory || it.isVideo }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                }
            }
        }

    /** Credentials are NOT in the link; see [SmbUri]. */
    override fun playbackUri(server: ServerConfig, file: RemoteFile): String = SmbUri.build(server.id, file.path)

    /** Connects, authenticates and opens the share root. Throws on failure. */
    suspend fun testConnection(server: ServerConfig) = withContext(Dispatchers.IO) {
        client.connect(server.host).use { c ->
            (c.authenticate(server.authContext()).connectShare(server.share) as DiskShare).use { it.list("") }
        }
        Unit
    }
}

object SmbClientHolder {
    val client: SMBClient by lazy { SMBClient() }
}

fun ServerConfig.authContext(): AuthenticationContext =
    if (username.isBlank()) AuthenticationContext.anonymous()
    else AuthenticationContext(username, password.toCharArray(), domain.ifBlank { null })
