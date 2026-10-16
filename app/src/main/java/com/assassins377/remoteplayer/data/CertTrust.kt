package com.assassins377.remoteplayer.data

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Human-readable certificate details shown before the user decides to trust it. */
data class CertInfo(
    val subject: String,
    val issuer: String,
    val validFrom: Long,
    val validTo: Long,
    val sha256: String,
    /** true = a pin exists for this server but the server now presents a different certificate. */
    val pinMismatch: Boolean = false,
    val selfSigned: Boolean = subject == issuer,
) {
    fun validity(): String {
        val f = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT)
        return f.format(validFrom) + " — " + f.format(validTo)
    }

    /** "AB:CD:..." grouped for display. */
    fun fingerprintDisplay(): String = sha256.uppercase().chunked(2).joinToString(":")

    companion object {
        fun of(c: X509Certificate, pinMismatch: Boolean = false) = CertInfo(
            c.subjectX500Principal.name, c.issuerX500Principal.name, c.notBefore.time, c.notAfter.time,
            CertPins.sha256(c), pinMismatch,
        )

        /** Finds certificate details in an exception chain (from a TLS failure), or null. */
        fun from(t: Throwable?): CertInfo? {
            var e = t; val seen = HashSet<Throwable>()
            while (e != null && seen.add(e)) { if (e is UntrustedCertificateException) return e.info; e = e.cause }
            return null
        }
    }
}

/** Thrown by [PinningTrustManager] when the chain is neither CA-valid nor matches the pin. */
class UntrustedCertificateException(val info: CertInfo, cause: Throwable?) :
    CertificateException(if (info.pinMismatch) "Pinned certificate mismatch" else "Untrusted certificate", cause)

object CertPins {
    /** SHA-256 over the DER-encoded certificate, lowercase hex. Pins the exact certificate. */
    fun sha256(c: X509Certificate): String = hex(MessageDigest.getInstance("SHA-256").digest(c.encoded))

    fun normalize(pin: String): String = pin.lowercase(Locale.ROOT).filter { it in '0'..'9' || it in 'a'..'f' }

    fun matches(pin: String, c: X509Certificate): Boolean {
        val p = normalize(pin)
        return p.length == 64 && MessageDigest.isEqual(p.toByteArray(), sha256(c).toByteArray())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}

/**
 * System CAs OR the one pinned leaf certificate. Never trust-all: an unknown certificate is rejected with
 * [UntrustedCertificateException] carrying its details so the UI can ask the user.
 */
class PinningTrustManager(private val system: X509TrustManager, private val pin: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty chain")
        if (pin.isNotBlank() && CertPins.matches(pin, leaf)) {
            leaf.checkValidity() // pinned but expired → still reject; the user can re-trust a renewed cert
            return
        }
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            throw UntrustedCertificateException(CertInfo.of(leaf, pinMismatch = pin.isNotBlank()), e)
        }
    }
}

/** Default hostname check; relaxed ONLY when the peer presents exactly the pinned certificate (LAN IPs, wrong CN). */
class PinnedHostnameVerifier(private val default: HostnameVerifier, private val pin: String) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (default.verify(hostname, session)) return true
        if (pin.isBlank()) return false
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return false
        return CertPins.matches(pin, leaf)
    }
}
