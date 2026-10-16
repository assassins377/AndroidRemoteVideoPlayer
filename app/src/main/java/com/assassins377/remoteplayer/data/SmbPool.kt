package com.assassins377.remoteplayer.data

import com.assassins377.remoteplayer.settings.AppSettings
import com.assassins377.remoteplayer.settings.SettingsStore
import com.assassins377.remoteplayer.settings.SmbDialect
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.TimeUnit

/**
 * Pool of authenticated SMB sessions keyed by server id. Reuses TCP connection + session +
 * connected shares across listing and playback. Entry is rebuilt when server config changes
 * or the connection drops; callers invalidate on network/auth errors.
 */
object SmbPool {
    private var clientRef: SMBClient? = null

    /** Client built from current settings; rebuilt by [invalidateAll] when network settings change. */
    val client: SMBClient
        @Synchronized get() = clientRef ?: SMBClient(buildConfig(SettingsStore.current)).also { clientRef = it }

    fun dialectsFor(d: SmbDialect): List<SMB2Dialect> = when (d) {
        SmbDialect.AUTO -> listOf(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
        SmbDialect.SMB2 -> listOf(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
        SmbDialect.SMB3 -> listOf(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0)
    }

    private fun buildConfig(s: AppSettings): SmbConfig = SmbConfig.builder()
        .withDialects(dialectsFor(s.smbDialect))
        .withEncryptData(s.smbEncryption)                    // SMB3 only; ignored for SMB2
        .withTimeout(s.timeoutSec.toLong(), TimeUnit.SECONDS)  // request timeout
        .withSoTimeout(s.timeoutSec + 10L, TimeUnit.SECONDS)   // socket read timeout
        .withReadBufferSize(4 * 1024 * 1024)                   // allow large SMB2 READs (server may cap)
        .build()

    fun defaultPolicy() = RetryPolicy(maxAttempts = SettingsStore.current.retryCount)

    private class Entry(val fingerprint: ServerConfig, val connection: Connection, val session: Session) {
        val shares = HashMap<String, DiskShare>()
        fun alive() = connection.isConnected
        fun close() {
            shares.values.forEach { runCatching { it.close() } }
            runCatching { session.close() }
            runCatching { connection.close() }
        }
    }

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun session(server: ServerConfig): Session = entry(server).session

    @Synchronized
    fun share(server: ServerConfig, name: String = server.share): DiskShare {
        val e = entry(server)
        e.shares[name]?.takeIf { it.isConnected }?.let { return it }
        return (e.session.connectShare(name) as DiskShare).also { e.shares[name] = it }
    }

    private fun entry(server: ServerConfig): Entry {
        entries[server.id]?.let { if (it.fingerprint == server && it.alive()) return it else it.close() }
        val conn = client.connect(server.host)
        val session = try { conn.authenticate(server.authContext()) } catch (t: Throwable) { conn.close(); throw t }
        return Entry(server, conn, session).also { entries[server.id] = it }
    }

    @Synchronized
    fun invalidate(serverId: String) { entries.remove(serverId)?.close() }

    @Synchronized
    fun invalidateAll() {
        entries.values.forEach { it.close() }; entries.clear()
        clientRef?.let { runCatching { it.close() } }; clientRef = null
    }

    /** Runs [block] with retries; drops the pooled session on connection-level failures. */
    fun <T> withRetry(server: ServerConfig, policy: RetryPolicy = defaultPolicy(), block: () -> T): T = try {
        retryBlocking(policy, onRetry = { _, e ->
            if (SmbErrors.invalidatesSession(SmbErrors.classify(e))) invalidate(server.id)
        }) { block() }
    } catch (t: Throwable) {
        if (SmbErrors.invalidatesSession(SmbErrors.classify(t))) invalidate(server.id)
        throw SmbErrors.wrap(t)
    }
}
