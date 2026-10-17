package com.assassins377.remoteplayer.desktop

import kotlinx.coroutines.launch
import com.assassins377.remoteplayer.settings.WatchedStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.assassins377.remoteplayer.data.Repositories
import com.assassins377.remoteplayer.meta.TitleParser
import com.assassins377.remoteplayer.settings.SubtitleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import androidx.compose.ui.graphics.asComposeImageBitmap

object Vlc {
    /** Extra libvlc options from REMOTEPLAYER_VLC_ARGS (space-separated), e.g. "--vout=xcb_x11" on X servers without GL. */
    val args: Array<String> get() = System.getenv("REMOTEPLAYER_VLC_ARGS")?.split(' ')?.filter { it.isNotBlank() }?.toTypedArray() ?: emptyArray()
    /** libvlc found (system VLC 3.x). */
    val available: Boolean by lazy { runCatching { NativeDiscovery().discover() }.getOrDefault(false) }
}

/**
 * libvlc renders into memory (RV32) and frames are shown as a Compose image: no heavyweight AWT surface, so it works
 * with software rendering, X11 and XWayland alike.
 */
class VlcVideo {
    private val factory = MediaPlayerFactory(*Vlc.args)
    val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
    val frame = mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    private var info: org.jetbrains.skia.ImageInfo? = null
    private var bytes = ByteArray(0)

    init {
        val formatCb = object : BufferFormatCallback {
            override fun getBufferFormat(w: Int, h: Int): BufferFormat {
                info = org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL)
                bytes = ByteArray(w * h * 4)
                return RV32BufferFormat(w, h)
            }
            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
        }
        val renderCb = object : RenderCallback {
            override fun display(mp: MediaPlayer, buffers: Array<out ByteBuffer>, format: BufferFormat) {
                val i = info ?: return
                val b = buffers[0]; b.rewind(); b.get(bytes, 0, minOf(bytes.size, b.remaining()))
                val bmp = org.jetbrains.skia.Bitmap().apply { allocPixels(i); installPixels(bytes) }
                frame.value = bmp.asComposeImageBitmapCompat()
            }
        }
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(formatCb, renderCb, true))
    }

    fun release() { runCatching { player.controls().stop() }; runCatching { player.release() }; runCatching { factory.release() } }
}

private fun org.jetbrains.skia.Bitmap.asComposeImageBitmapCompat(): androidx.compose.ui.graphics.ImageBitmap {
    setImmutable(); return asComposeImageBitmap()
}

data class Track(val id: Int, val name: String)

/** Pure player helpers (tests). */
object PlayerMath {
    fun fmt(ms: Long): String { val t = (ms / 1000).coerceAtLeast(0); val h = t / 3600; val m = t / 60 % 60; val s = t % 60; return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s) }
    fun delayLabel(ms: Long) = (if (ms > 0) "+" else "") + "%.1f s".format(ms / 1000.0)
}

@Composable
fun PlayerScreen(nav: Nav, sc: Screen.Player, window: WindowState?) {
    if (!Vlc.available) {
        Column { Bar(sc.file.name, nav::back); Text(Strings["vlc_missing"], Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
        return
    }
    val s = Env.settings.current
    val repo = remember(sc) { Repositories.forType(sc.server.type) }
    val key = remember(sc) { repo.playbackUri(sc.server, sc.file) }
    val video = remember { VlcVideo() }
    val mp = video.player
    var time by remember { mutableStateOf(0L) }
    var length by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var audio by remember { mutableStateOf(emptyList<Track>()) }
    var subs by remember { mutableStateOf(emptyList<Track>()) }
    var audioId by remember { mutableStateOf(-1) }
    var subId by remember { mutableStateOf(-1) }
    var audioDelay by remember { mutableStateOf(0L) }
    var subDelay by remember { mutableStateOf(s.subtitleOffsetMs.toLong()) }
    var speed by remember { mutableStateOf(1f) }
    val title = remember(sc) { TitleParser.parse(sc.file.name).display }
    var castPick by remember { mutableStateOf(false) }
    var casting by remember { mutableStateOf<Renderer?>(null) }
    val castScope = rememberCoroutineScope()
    if (castPick) CastDialog(onDismiss = { castPick = false }, onPick = { r ->
        castPick = false
        val at = mp.status().time()
        castScope.launch(Dispatchers.IO) {
            runCatching {
                val url = Env.castStream.register(sc.server, sc.file.path, Dlna.localAddressFor(r.host))
                Dlna.play(r, url, title, StreamServer.mime(sc.file.name), at)
            }.onSuccess { mp.controls().setPause(true); casting = r }.onFailure { error = it.message }
        }
    })

    LaunchedEffect(sc) {
        val url = Env.downloads.localFile(sc.server.id, sc.file.path)?.toURI()?.toString() ?: Env.stream.register(sc.server, sc.file.path)
        val jf = (repo as? com.assassins377.remoteplayer.data.JellyfinRepository)
        val start = if (!s.resume) 0L else Env.resume.get(key) ?: jf?.let { withContext(Dispatchers.IO) { it.resumePositionMs(sc.server, sc.file.path) } } ?: 0L
        mp.media().play(url, ":start-time=${start / 1000.0}", ":network-caching=1500")
        if (s.autoLoadSubtitles) runCatching {
            val dir = sc.file.path.trim('/').substringBeforeLast('/', "")
            val files = withContext(Dispatchers.IO) { repo.list(sc.server, dir) }
            SubtitleMatcher.find(sc.file.name, files).forEach { m -> mp.media().slaves().add(MediaSlaveType.SUBTITLE, uk.co.caprica.vlcj.media.MediaSlavePriority.HIGHEST, Env.stream.register(sc.server, m.file.path)) }
        }
        var ticks = 0
        while (true) {
            delay(500)
            time = mp.status().time(); length = mp.status().length(); playing = mp.status().isPlaying
            if (mp.status().state() == uk.co.caprica.vlcj.player.base.State.ERROR) error = Strings["player_error"]
            audio = mp.audio().trackDescriptions().map { Track(it.id(), it.description()) }; audioId = mp.audio().track()
            subs = mp.subpictures().trackDescriptions().map { Track(it.id(), it.description()) }; subId = mp.subpictures().track()
            if (++ticks % 20 == 0 && s.resume && length > 0) {
                Env.resume.save(key, time, length)
                jf?.let { j -> val t = time; val p = !playing; launch(Dispatchers.IO) { j.reportProgress(sc.server, sc.file.path, t, p) } }
            }
            if (mp.status().state() == uk.co.caprica.vlcj.player.base.State.ENDED) {
                Env.resume.save(key, length, length)
                WatchedStore.set(WatchedStore.key(sc.server.id, sc.file.path), true)
                jf?.let { j -> launch(Dispatchers.IO) { j.setPlayed(sc.server, sc.file.path, true) } }
                val next = com.assassins377.remoteplayer.meta.SeriesGrouper.next(sc.playlist, sc.file)
                if (next != null && s.autoplayNext) nav.replace(Screen.Player(sc.server, next, sc.playlist))
                break
            }
            if (length > 0 && WatchedStore.shouldMark(time, length)) WatchedStore.set(WatchedStore.key(sc.server.id, sc.file.path), true)
        }
    }
    LaunchedEffect(subDelay) { mp.subpictures().setDelay(subDelay * 1000) }
    LaunchedEffect(audioDelay) { mp.audio().setDelay(audioDelay * 1000) }
    LaunchedEffect(speed) { mp.controls().setRate(speed) }
    DisposableEffect(sc) {
        Keys.handler = { e ->
            if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                Key.Spacebar -> { mp.controls().pause(); true }
                Key.DirectionLeft -> { mp.controls().skipTime(-s.seekStepMs); true }
                Key.DirectionRight -> { mp.controls().skipTime(s.seekStepMs); true }
                Key.F -> { window?.let { it.placement = if (it.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen }; true }
                Key.Escape -> { if (window?.placement == WindowPlacement.Fullscreen) window.placement = WindowPlacement.Floating else nav.back(); true }
                else -> false
            }
        }
        onDispose {
            Keys.handler = null
            runCatching {
                val t = mp.status().time(); val l = mp.status().length()
                if (s.resume && l > 0) Env.resume.save(key, t, l)
                (repo as? com.assassins377.remoteplayer.data.JellyfinRepository)?.let { j -> Thread { j.reportProgress(sc.server, sc.file.path, t, true, stopped = true) }.start() }
            }
            video.release()
            if (window?.placement == WindowPlacement.Fullscreen) window.placement = WindowPlacement.Floating
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (window?.placement != WindowPlacement.Fullscreen) Bar(title, nav::back)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            video.frame.value?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
            error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center)) }
            casting?.let { r -> Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.85f))) {
                CastOverlay(r, onStop = { p -> casting = null; if (p > 0) mp.controls().setTime(p) }, modifier = Modifier.align(Alignment.Center))
            } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { mp.controls().pause() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
            IconButton(onClick = { mp.controls().skipTime(-s.seekStepMs) }) { Icon(Icons.Default.Replay10, null) }
            IconButton(onClick = { mp.controls().skipTime(s.seekStepMs) }) { Icon(Icons.Default.Forward10, null) }
            Text(PlayerMath.fmt(time))
            Slider(value = if (length > 0) time.toFloat() / length else 0f, onValueChange = { if (length > 0) mp.controls().setTime((it * length).toLong()) },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Text(PlayerMath.fmt(length))
            TrackMenu(Icons.Default.Audiotrack, Strings["audio"], audio, audioId) { mp.audio().setTrack(it) }
            TrackMenu(Icons.Default.Subtitles, Strings["subtitles"], subs, subId) { mp.subpictures().setTrack(it) }
            com.assassins377.remoteplayer.meta.SeriesGrouper.next(sc.playlist, sc.file)?.let { n ->
                IconButton(onClick = { nav.replace(Screen.Player(sc.server, n, sc.playlist)) }) { Icon(Icons.Default.SkipNext, Strings["next_episode"]) }
            }
            DelayMenu(audioDelay, subDelay, speed, { audioDelay = it }, { subDelay = it }, { speed = it })
            IconButton(onClick = { castPick = true }) { Icon(Icons.Default.Cast, Strings["cast"]) }
            IconButton(onClick = { window?.let { it.placement = if (it.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen } }) {
                Icon(Icons.Default.Fullscreen, Strings["fullscreen"])
            }
        }
    }
}

@Composable
private fun TrackMenu(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tracks: List<Track>, selected: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = tracks.isNotEmpty()) { Icon(icon, label) }
        DropdownMenu(open, { open = false }) {
            tracks.forEach { t -> DropdownMenuItem(text = { Text((if (t.id == selected) "✓ " else "") + t.name.ifBlank { if (t.id < 0) Strings["off"] else "#${t.id}" }) }, onClick = { onPick(t.id); open = false }) }
        }
    }
}

@Composable
private fun DelayMenu(audio: Long, sub: Long, speed: Float, onAudio: (Long) -> Unit, onSub: (Long) -> Unit, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.Tune, null) }
        DropdownMenu(open, { open = false }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stepper(Strings["audio_delay"], audio, onAudio)
                Stepper(Strings["sub_delay"], sub, onSub)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Strings["speed"])
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v -> FilterChip(selected = speed == v, onClick = { onSpeed(v) }, label = { Text("${v}x") }) }
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Long, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.width(150.dp))
        OutlinedButton(onClick = { onChange(value - 100) }) { Text("−0.1") }
        Text(PlayerMath.delayLabel(value), Modifier.width(64.dp))
        OutlinedButton(onClick = { onChange(value + 100) }) { Text("+0.1") }
        TextButton(onClick = { onChange(0) }) { Text("0") }
    }
}
