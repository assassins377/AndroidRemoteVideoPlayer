package com.assassins377.remoteplayer.desktop

/** A device the player can cast to (DLNA renderer or Chromecast). All calls are blocking. */
interface CastTarget {
    val name: String
    val host: String
    val kind: String
    fun play(url: String, title: String, mime: String, startMs: Long)
    fun pause()
    fun resume()
    fun stop()
    fun seek(ms: Long)
    /** (position, duration) in ms. */
    fun position(): Pair<Long, Long>
    fun close() {}
}

class DlnaTarget(val r: Renderer) : CastTarget {
    override val name get() = r.name
    override val host get() = r.host
    override val kind = "DLNA"
    override fun play(url: String, title: String, mime: String, startMs: Long) = Dlna.play(r, url, title, mime, startMs)
    override fun pause() = Dlna.pause(r)
    override fun resume() = Dlna.resume(r)
    override fun stop() = Dlna.stop(r)
    override fun seek(ms: Long) = Dlna.seek(r, ms)
    override fun position() = Dlna.position(r)
}

class ChromecastTarget(val device: CastDevice) : CastTarget {
    private var client: CastClient? = null
    override val name get() = device.name
    override val host get() = device.host
    override val kind = "Chromecast"
    private fun c() = client ?: CastClient.connect(device.host, device.port).also { client = it }
    override fun play(url: String, title: String, mime: String, startMs: Long) = c().load(url, title, receiverMime(mime), startMs)
    override fun pause() = c().pause()
    override fun resume() = c().play()
    override fun stop() { runCatching { c().stopMedia() }; close() }
    override fun seek(ms: Long) = c().seek(ms)
    override fun position() = c().status()
    override fun close() { client?.close(); client = null }
}

/**
 * The Default Media Receiver only accepts MP4/WebM/HLS/DASH content types; Matroska with H.264/AAC usually plays when
 * announced as video/mp4, other codecs will fail with LOAD_FAILED.
 */
fun receiverMime(mime: String) = when (mime) { "video/x-matroska", "video/x-msvideo", "application/octet-stream" -> "video/mp4"; else -> mime }
