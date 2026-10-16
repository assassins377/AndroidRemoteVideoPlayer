package com.assassins377.remoteplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.assassins377.remoteplayer.data.SmbClientHolder
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException

/** Media3 DataSource that reads smb://host/share/path?u=&p=&d= via smbj with random access (seek). */
@UnstableApi
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var connection: Connection? = null
    private var share: DiskShare? = null
    private var file: File? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val u = dataSpec.uri
        uri = u
        try {
            val segments = u.pathSegments
            require(segments.size >= 2) { "smb uri must be smb://host/share/path" }
            val user = u.getQueryParameter("u").orEmpty()
            val auth = if (user.isBlank()) AuthenticationContext.anonymous()
            else AuthenticationContext(user, u.getQueryParameter("p").orEmpty().toCharArray(),
                u.getQueryParameter("d")?.ifBlank { null })
            val conn = SmbClientHolder.client.connect(u.host).also { connection = it }
            val s = conn.authenticate(auth).connectShare(segments[0]) as DiskShare
            share = s
            val f = s.openFile(
                segments.drop(1).joinToString("\\"),
                setOf(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            )
            file = f
            val size = f.fileInformation.standardInformation.endOfFile
            position = dataSpec.position
            if (position > size) throw DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        } catch (e: IOException) {
            throw DataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = try {
            file!!.read(buffer, position, offset, toRead)
        } catch (e: Exception) {
            throw DataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
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
        runCatching { file?.close() }
        runCatching { share?.close() }
        runCatching { connection?.close() }
        file = null; share = null; connection = null
        if (opened) { opened = false; transferEnded() }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }
}

/** Routes smb:// to SmbDataSource, everything else to the default (HTTP/file) source. */
@UnstableApi
class RoutingDataSourceFactory(private val default: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(default.createDataSource())

    private class RoutingDataSource(private val fallback: DataSource) : DataSource {
        private var active: DataSource? = null
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) =
            fallback.addTransferListener(transferListener)
        override fun open(dataSpec: DataSpec): Long {
            val ds = if (dataSpec.uri.scheme == "smb") SmbDataSource() else fallback
            active = ds
            return ds.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = active!!.read(buffer, offset, length)
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
        override fun close() { active?.close(); active = null }
    }
}
