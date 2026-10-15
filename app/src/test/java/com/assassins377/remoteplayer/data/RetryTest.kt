package com.assassins377.remoteplayer.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RetryTest {
    private val policy = RetryPolicy(maxAttempts = 4, initialDelayMs = 100, factor = 2.0, maxDelayMs = 300)

    @Test fun backoffGrowsAndIsCapped() {
        assertEquals(listOf(100L, 200L, 300L, 300L), (0..3).map(policy::delayFor))
    }

    @Test fun retriesRetryableUntilSuccess() {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val r = retryBlocking(policy, sleep = { sleeps += it }) {
            calls++
            if (calls < 3) throw SocketTimeoutException("slow") else "ok"
        }
        assertEquals("ok", r); assertEquals(3, calls); assertEquals(listOf(100L, 200L), sleeps)
    }

    @Test fun givesUpAfterMaxAttempts() {
        var calls = 0
        assertThrows(SocketTimeoutException::class.java) {
            retryBlocking(policy, sleep = {}) { calls++; throw SocketTimeoutException() }
        }
        assertEquals(4, calls)
    }

    @Test fun nonRetryableFailsImmediately() {
        var calls = 0
        assertThrows(UnknownHostException::class.java) {
            retryBlocking(policy, sleep = {}) { calls++; throw UnknownHostException("nas") }
        }
        assertEquals(1, calls)
    }

    @Test fun suspendVariantRetries() = runTest {
        var calls = 0
        val r = retrySuspend(policy) { calls++; if (calls < 2) throw java.net.ConnectException() else 42 }
        assertEquals(42, r); assertEquals(2, calls)
    }

    @Test fun classifiesWrappedCauses() {
        assertEquals(SmbErrorKind.TIMEOUT, SmbErrors.classify(IOException("x", SocketTimeoutException())))
        assertEquals(SmbErrorKind.HOST_UNKNOWN, SmbErrors.classify(UnknownHostException()))
        assertEquals(SmbErrorKind.AUTH, SmbErrors.classifyStatus(com.hierynomus.mserref.NtStatus.STATUS_LOGON_FAILURE))
        assertEquals(SmbErrorKind.NOT_FOUND, SmbErrors.classifyStatus(com.hierynomus.mserref.NtStatus.STATUS_BAD_NETWORK_NAME))
        assertEquals(SmbErrorKind.UNKNOWN, SmbErrors.classify(IllegalStateException()))
    }
}
