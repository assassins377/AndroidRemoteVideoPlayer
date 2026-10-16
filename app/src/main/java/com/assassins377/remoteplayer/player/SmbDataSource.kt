package com.assassins377.remoteplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.assassins377.remoteplayer.data.ReadAheadBuffer
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.data.SmbError
import com.assassins377.remoteplayer.data.SmbErrorKind
import com.assassins377.remoteplayer.data.SmbErrors
import com.assassins377.remoteplayer.data.SmbPool
import com.assassins377.remoteplayer.data.SmbUri
import com.assassins377.remoteplayer.data.HttpUri
import com.assassins377.remoteplayer.data.HttpClients
import com.assassins377.remoteplayer.data.UrlPaths
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File
import java.io.IOException

/**
 * Media3 DataSource for rvp-smb://<serverId>/<path>. Uses pooled SMB sessions (no reconnect per
 * open/seek), 1 MiB read-ahead chunks, and retries reads with backoff, reopening the file if the
 * session dropped.
 */
@UnstableApi
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var server: ServerConfig? = null
    private var path: String = ""
    private var file: File? = null
    private var buffer: ReadAheadBuffer? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    private fun openFile(server: ServerConfig): File =
        SmbPool.share(server).openFile(
            path.replace('/', '\\'),
            setOf(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        try {
            val (serverId, p) = SmbUri.parse(dataSpec.uri.toString()) ?: throw IOException("Bad SMB playback uri")
            val s = ServerStore.get(serverId) ?: throw IOException("Unknown server $serverId")
            server = s; path = p
            val f = SmbPool.withRetry(s) { openFile(s) }
            file = f
            buffer = ReadAheadBuffer(com.assassins377.remoteplayer.settings.SettingsStore.current.readAheadBytes) { pos, buf, off, len -> readRemote(pos, buf, off, len) }
            val size = f.fileInformation.standardInformation.endOfFile
            position = dataSpec.position
            if (position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        } catch (e: DataSourceException) {
            throw e
        } catch (e: Exception) {
            throw toDataSourceException(e)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    /** One network read with retry; reopens the file handle via a fresh pooled session on failure. */
    private fun readRemote(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        val s = server!!
        return SmbPool.withRetry(s) {
            val f = file?.takeIf { it.diskShare.isConnected } ?: openFile(s).also { file = it }
            try {
                f.read(buf, pos, off, len)
            } catch (e: Exception) {
                runCatching { f.close() }; file = null
                throw e
            }
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = try {
            buffer!!.read(position, target, offset, toRead)
        } catch (e: Exception) {
            throw toDataSourceException(e)
        }
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { file?.close() } // session/share stay pooled
        file = null; buffer = null; server = null
        if (opened) { opened = false; transferEnded() }
    }

    private fun toDataSourceException(e: Exception): DataSourceException {
        val err = SmbErrors.wrap(e)
        val code = when (err.kind) {
            SmbErrorKind.NOT_FOUND -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            SmbErrorKind.AUTH, SmbErrorKind.ACCESS_DENIED -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION
            SmbErrorKind.TIMEOUT -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            SmbErrorKind.UNKNOWN -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            else -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        }
        return DataSourceException(err, code)
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }
}

/** Routes rvp-smb:// to SmbDataSource, everything else to the default (HTTP/file) source. */
@UnstableApi
class RoutingDataSourceFactory(private val default: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(default.createDataSource())

    private class RoutingDataSource(private val fallback: DataSource) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var active: DataSource? = null
        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            fallback.addTransferListener(transferListener)
        }
        override fun open(dataSpec: DataSpec): Long {
            val (ds, spec) = when (dataSpec.uri.scheme) {
                SmbUri.SCHEME -> SmbDataSource().also { s -> listeners.forEach(s::addTransferListener) } to dataSpec
                HttpUri.SCHEME -> httpSource(dataSpec)
                else -> fallback to dataSpec
            }
            active = ds
            return ds.open(spec)
        }
        /** rvp-http://id/path → real URL + per-server OkHttp client (Basic/Digest). Range seeking is done by OkHttpDataSource. */
        private fun httpSource(dataSpec: DataSpec): Pair<DataSource, DataSpec> {
            val (id, path) = HttpUri.parse(dataSpec.uri.toString()) ?: throw IOException("Bad HTTP playback uri")
            val server = ServerStore.get(id) ?: throw IOException("Unknown server $id")
            val jf = server.type == com.assassins377.remoteplayer.data.ServerType.JELLYFIN
            val url = when {
                jf -> com.assassins377.remoteplayer.data.JellyfinRepository().resolveStream(server, path)
                path.isEmpty() -> server.host
                else -> UrlPaths.join(server.host, path)
            }
            val ds = OkHttpDataSource.Factory(HttpClients.forServer(if (jf) server.copy(username = "", password = "") else server)).createDataSource()
            listeners.forEach(ds::addTransferListener)
            return ds to dataSpec.withUri(Uri.parse(url))
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int) = active!!.read(buffer, offset, length)
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
        override fun close() { active?.close(); active = null }
    }
}
