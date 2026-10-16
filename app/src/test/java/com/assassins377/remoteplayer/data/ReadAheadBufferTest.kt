package com.assassins377.remoteplayer.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAheadBufferTest {
    private val file = ByteArray(10_000) { (it % 251).toByte() }
    private fun buffer(chunk: Int) = ReadAheadBuffer(chunk) { pos, buf, off, len ->
        if (pos >= file.size) -1 else minOf(len, file.size - pos.toInt()).also { System.arraycopy(file, pos.toInt(), buf, off, it) }
    }

    private fun readAll(b: ReadAheadBuffer, from: Long, slice: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val tmp = ByteArray(slice); var p = from
        while (true) { val n = b.read(p, tmp, 0, slice); if (n < 0) break; out.write(tmp, 0, n); p += n }
        return out.toByteArray()
    }

    @Test fun sequentialSmallReadsUseFewFetches() {
        val b = buffer(4096)
        assertArrayEquals(file, readAll(b, 0, 100))
        assertEquals(4, b.fetchCount) // 3 data chunks + 1 EOF probe
    }

    @Test fun seekBackwardAndForwardRefetches() {
        val b = buffer(1000)
        val t = ByteArray(10)
        b.read(5000, t, 0, 10); assertEquals(file[5000], t[0])
        b.read(5500, t, 0, 10); assertEquals(1, b.fetchCount) // inside chunk
        b.read(100, t, 0, 10); assertEquals(file[100], t[0]); assertEquals(2, b.fetchCount)
    }

    @Test fun eofReturnsMinusOne() {
        assertEquals(-1, buffer(1000).read(10_000, ByteArray(10), 0, 10))
    }

    @Test fun readNeverCrossesChunkBoundaryIncorrectly() {
        val b = buffer(1000); val t = ByteArray(300)
        b.read(0, ByteArray(1), 0, 1) // chunk = [0, 1000)
        val n = b.read(900, t, 0, 300)
        assertEquals(100, n)
        assertArrayEquals(file.copyOfRange(900, 1000), t.copyOf(n))
    }
}
