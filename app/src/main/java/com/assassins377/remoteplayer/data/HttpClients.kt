package com.assassins377.remoteplayer.data

import com.assassins377.remoteplayer.settings.SettingsStore
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Digest (RFC 2617/7616, MD5, qop=auth) and Basic challenge handling. */
object DigestAuth {
    fun parseChallenge(header: String): Map<String, String> {
        val body = header.substringAfter(' ', "")
        val re = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""")
        return re.findAll(body).associate { m -> m.groupValues[1].lowercase() to (m.groupValues[2].ifEmpty { m.groupValues[3] }) }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun authorization(
        challenge: Map<String, String>, user: String, pass: String, method: String, uri: String,
        nc: Int = 1, cnonce: String = randomHex(),
    ): String {
        val realm = challenge["realm"].orEmpty(); val nonce = challenge["nonce"].orEmpty()
        val qop = challenge["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val ha1 = md5("$user:$realm:$pass"); val ha2 = md5("$method:$uri")
        val ncs = "%08x".format(nc)
        val resp = if (qop != null) md5("$ha1:$nonce:$ncs:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\"")
            challenge["opaque"]?.let { append(", opaque=\"$it\"") }
            if (qop != null) append(", qop=$qop, nc=$ncs, cnonce=\"$cnonce\"")
            append(", algorithm=MD5")
        }
    }

    private fun randomHex() = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

/**
 * One OkHttp client per server config: answers Basic/Digest challenges with the stored (decrypted) password,
 * then sends Basic preemptively once the server is known to use it (saves a 401 per Range request).
 */
object HttpClients {
    private val base by lazy { OkHttpClient.Builder().followRedirects(true).build() }
    private val clients = ConcurrentHashMap<ServerConfig, OkHttpClient>()

    fun forServer(server: ServerConfig): OkHttpClient = clients.getOrPut(server) {
        val s = SettingsStore.current
        val basicKnown = java.util.concurrent.atomic.AtomicBoolean(false)
        base.newBuilder()
            .connectTimeout(s.timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(s.timeoutSec + 10L, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(Interceptor { chain ->
                val r = chain.request()
                if (basicKnown.get() && server.username.isNotBlank() && r.header("Authorization") == null)
                    chain.proceed(r.newBuilder().header("Authorization", Credentials.basic(server.username, server.password, Charsets.UTF_8)).build())
                else chain.proceed(r)
            })
            .authenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    if (server.username.isBlank()) return null
                    if (response.priorResponse?.code == 401) return null // credentials rejected
                    val challenges = response.headers("WWW-Authenticate")
                    val digest = challenges.firstOrNull { it.startsWith("Digest", ignoreCase = true) }
                    val req = response.request
                    val header = if (digest != null) {
                        val u = req.url
                        val uri = u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "")
                        DigestAuth.authorization(DigestAuth.parseChallenge(digest), server.username, server.password, req.method, uri)
                    } else {
                        basicKnown.set(true)
                        Credentials.basic(server.username, server.password, Charsets.UTF_8)
                    }
                    return req.newBuilder().header("Authorization", header).build()
                }
            })
            .build()
    }

    fun invalidateAll() = clients.clear()
}

/** HTTP status / IO failure → SmbError with a Russian message (shared error kinds). */
object HttpErrors {
    fun forCode(code: Int): SmbErrorKind = when (code) {
        401 -> SmbErrorKind.AUTH
        403 -> SmbErrorKind.ACCESS_DENIED
        404, 410 -> SmbErrorKind.NOT_FOUND
        408, 504 -> SmbErrorKind.TIMEOUT
        in 500..599 -> SmbErrorKind.NETWORK
        else -> SmbErrorKind.UNKNOWN
    }

    fun check(r: Response) { if (!r.isSuccessful && r.code != 207) throw SmbError(forCode(r.code), IOException("HTTP ${r.code}")) }

    fun wrap(t: Throwable): SmbError = when (t) {
        is SmbError -> t
        is UnknownHostException -> SmbError(SmbErrorKind.HOST_UNKNOWN, t)
        is SocketTimeoutException -> SmbError(SmbErrorKind.TIMEOUT, t)
        is ConnectException, is NoRouteToHostException -> SmbError(SmbErrorKind.UNREACHABLE, t)
        is IOException -> SmbError(SmbErrorKind.NETWORK, t)
        else -> SmbError(SmbErrorKind.UNKNOWN, t)
    }
}
