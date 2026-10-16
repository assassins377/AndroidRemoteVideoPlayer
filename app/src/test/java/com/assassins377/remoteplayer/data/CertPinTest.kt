package com.assassins377.remoteplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class CertPinTest {
    private fun load(n: String) = CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.getResourceAsStream("/certs/$n.pem")) as X509Certificate
    private val a = load("a"); private val b = load("b"); private val expired = load("expired")
    private val aPin = CertPins.sha256(a)

    /** Stands in for the system store: trusts nothing (like a self-signed NAS cert). */
    private val noCa = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>, t: String) = throw CertificateException("no")
        override fun checkServerTrusted(c: Array<out X509Certificate>, t: String) = throw CertificateException("unknown CA")
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }
    private val allCa = object : X509TrustManager by noCa {
        override fun checkServerTrusted(c: Array<out X509Certificate>, t: String) {}
    }

    @Test fun fingerprintMatchesOpensslFormatAndNormalizes() {
        assertEquals(64, aPin.length)
        assertTrue(CertPins.matches(aPin.uppercase().chunked(2).joinToString(":"), a))
        assertFalse(CertPins.matches(aPin, b))
        assertFalse(CertPins.matches("", a)); assertFalse(CertPins.matches(aPin.take(40), a))
    }

    @Test fun pinnedCertAccepted() { PinningTrustManager(noCa, aPin).checkServerTrusted(arrayOf(a), "RSA") }

    @Test fun unknownCertRejectedWithDetails() {
        val e = assertThrows(UntrustedCertificateException::class.java) { PinningTrustManager(noCa, "").checkServerTrusted(arrayOf(a), "RSA") }
        assertFalse(e.info.pinMismatch); assertEquals(aPin, e.info.sha256); assertTrue(e.info.selfSigned)
        assertTrue(e.info.subject.contains("CN=nas.local"))
    }

    @Test fun pinMismatchFlagged() {
        val e = assertThrows(UntrustedCertificateException::class.java) { PinningTrustManager(noCa, aPin).checkServerTrusted(arrayOf(b), "RSA") }
        assertTrue(e.info.pinMismatch)
        assertEquals(SmbErrorKind.CERT_CHANGED, HttpErrors.wrap(javax.net.ssl.SSLHandshakeException("x").apply { initCause(e) }).kind)
    }

    @Test fun systemCaStillWorksWithPin() { PinningTrustManager(allCa, aPin).checkServerTrusted(arrayOf(b), "RSA") }

    @Test fun expiredPinnedCertRejected() {
        assertThrows(CertificateException::class.java) { PinningTrustManager(noCa, CertPins.sha256(expired)).checkServerTrusted(arrayOf(expired), "RSA") }
    }

    @Test fun certInfoFromExceptionChain() {
        val e = UntrustedCertificateException(CertInfo.of(a), null)
        val wrapped = java.io.IOException("outer", javax.net.ssl.SSLHandshakeException("hs").apply { initCause(e) })
        assertEquals(aPin, CertInfo.from(wrapped)?.sha256)
        assertEquals(SmbErrorKind.CERT_UNTRUSTED, HttpErrors.wrap(wrapped).kind)
        assertNull(CertInfo.from(java.io.IOException("plain")))
    }
}

/** Opt-in: HTTPS_IT_URL=https://127.0.0.1:8443/video/ (nginx autoindex with self-signed test cert a.pem). */
class HttpsPinIntegrationTest {
    private val url = System.getenv("HTTPS_IT_URL")
    private val aPin = CertPins.sha256(CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.getResourceAsStream("/certs/a.pem")) as X509Certificate)
    private fun srv(pin: String) = ServerConfig("https-it-$pin", "h", ServerType.HTTP, url ?: "", pinnedCertSha256 = pin)

    @Test fun unpinnedPinnedAndMismatch() = runBlocking {
        assumeTrue(url != null)
        val e = try { HttpRepository().list(srv(""), ""); null } catch (e: SmbError) { e }
        assertEquals(SmbErrorKind.CERT_UNTRUSTED, e?.kind); assertEquals(aPin, CertInfo.from(e)?.sha256)
        // Pinned: works even though host is an IP and the cert says CN=nas.local.
        assertTrue(HttpRepository().list(srv(aPin), "").any { it.name == "Movies" })
        val req = okhttp3.Request.Builder().url(UrlPaths.join(url!!, "Movies/Test Clip.mkv")).header("Range", "bytes=0-3").build()
        assertEquals(206, HttpClients.forServer(srv(aPin)).newCall(req).execute().use { it.code })
        val m = try { HttpRepository().list(srv("00".repeat(32)), ""); null } catch (e: SmbError) { e }
        assertEquals(SmbErrorKind.CERT_CHANGED, m?.kind)
    }
}
