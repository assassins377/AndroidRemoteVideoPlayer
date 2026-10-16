package com.assassins377.remoteplayer.data

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

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

    /** smb://host/share/path?u=..&p=..&d=.. — credentials kept in-process only (TODO: secure storage). */
    override fun playbackUri(server: ServerConfig, file: RemoteFile): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val encodedPath = file.path.split('/').joinToString("/") { e(it).replace("+", "%20") }
        return "smb://${server.host}/${e(server.share)}/$encodedPath?u=${e(server.username)}&p=${e(server.password)}&d=${e(server.domain)}"
    }
}

object SmbClientHolder {
    val client: SMBClient by lazy { SMBClient() }
}

fun ServerConfig.authContext(): AuthenticationContext =
    if (username.isBlank()) AuthenticationContext.anonymous()
    else AuthenticationContext(username, password.toCharArray(), domain.ifBlank { null })
