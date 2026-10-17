package com.assassins377.remoteplayer.data

/**
 * Sequential read-ahead cache: fetches [chunkSize] bytes per remote call and serves small
 * reads from memory. ExoPlayer reads in small slices (often 4–64 KB); for SMB each slice
 * would otherwise be a network round trip. A seek outside the chunk just refills.
 *
 * [fetch] reads up to len bytes at absolute position into buf[off..] and returns count (<=0 = EOF).
 */
class ReadAheadBuffer(
    private val chunkSize: Int = DEFAULT_CHUNK,
    private val fetch: (position: Long, buf: ByteArray, off: Int, len: Int) -> Int,
) {
    private val data = ByteArray(chunkSize)
    private var start = 0L
    private var size = 0
    var fetchCount = 0; private set

    /** Reads up to [length] bytes at [position]. Returns -1 at EOF. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position < start || position >= start + size) {
            fetchCount++
            val n = fetch(position, data, 0, chunkSize)
            if (n <= 0) { size = 0; return -1 }
            start = position; size = n
        }
        val inChunk = (position - start).toInt()
        val n = minOf(length, size - inChunk)
        System.arraycopy(data, inChunk, buffer, offset, n)
        return n
    }

    fun clear() { size = 0 }

    companion object { const val DEFAULT_CHUNK = 1024 * 1024 }
}
