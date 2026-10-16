package com.assassins377.remoteplayer.ui.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.data.SmbError
import com.assassins377.remoteplayer.data.PlaybackRef
import com.assassins377.remoteplayer.data.SmbUri
import com.assassins377.remoteplayer.player.RoutingDataSourceFactory
import com.assassins377.remoteplayer.settings.AppSettings
import com.assassins377.remoteplayer.settings.DecoderMode
import com.assassins377.remoteplayer.settings.FileSorter
import com.assassins377.remoteplayer.settings.ResumeStore
import com.assassins377.remoteplayer.settings.SettingsStore
import com.assassins377.remoteplayer.settings.SubtitleMatcher
import kotlinx.coroutines.delay
import android.content.ComponentName
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.assassins377.remoteplayer.player.PlaybackService
import com.assassins377.remoteplayer.player.PlayerHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns the ExoPlayer so playback survives configuration changes. Configured from [SettingsStore]. */
@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    companion object { const val COUNTDOWN_SEC = 10 }
    val settings: AppSettings = SettingsStore.current

    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ minOf(30_000, settings.bufferMaxSec * 1000),
            /* maxBufferMs = */ settings.bufferMaxSec * 1000,
            /* bufferForPlaybackMs = */ 2_500,
            /* bufferForPlaybackAfterRebufferMs = */ 5_000,
        )
        .setTargetBufferBytes(256 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    /** HW/SW preference: reorders decoders; AUTO keeps the platform order. */
    private val codecSelector = MediaCodecSelector { mime, secure, tunneling ->
        val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        when (settings.decoder) {
            DecoderMode.AUTO -> all
            DecoderMode.HARDWARE -> all.sortedBy { !it.hardwareAccelerated }
            DecoderMode.SOFTWARE -> all.sortedBy { !it.softwareOnly }
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setRenderersFactory(DefaultRenderersFactory(app).setMediaCodecSelector(codecSelector).setEnableDecoderFallback(true))
        .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSourceFactory(DefaultDataSource.Factory(app))))
        .setLoadControl(loadControl)
        .setSeekBackIncrementMs(settings.seekStepMs)
        .setSeekForwardIncrementMs(settings.seekStepMs)
        .build()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _speed = MutableStateFlow(settings.defaultSpeed)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    private var currentUri: String? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val _tracks = MutableStateFlow(TrackOptions())
    val tracks: StateFlow<TrackOptions> = _tracks.asStateFlow()
    private val _videoSize = MutableStateFlow(0 to 0)
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    init {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (settings.audioLanguage.isNotBlank()) setPreferredAudioLanguage(settings.audioLanguage)
            if (settings.subtitleLanguage.isNotBlank()) setPreferredTextLanguage(settings.subtitleLanguage)
        }.build()
        player.setPlaybackSpeed(settings.defaultSpeed)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                var c: Throwable? = error
                while (c != null && c !is SmbError) c = c.cause
                var h: Throwable? = error
                while (h != null && h !is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) h = h.cause
                val cert = com.assassins377.remoteplayer.data.CertInfo.from(error)
                _error.value = cert?.let { com.assassins377.remoteplayer.AppStrings.get(com.assassins377.remoteplayer.R.string.err_cert_reopen, "%s. Откройте папку заново, чтобы проверить сертификат.", com.assassins377.remoteplayer.data.HttpErrors.wrap(error).kind.message) }
                    ?: (c as? SmbError)?.message
                    ?: (h as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.let { com.assassins377.remoteplayer.data.HttpErrors.forCode(it.responseCode).message }
                    ?: com.assassins377.remoteplayer.AppStrings.get(com.assassins377.remoteplayer.R.string.err_playback, "Ошибка воспроизведения: %s", error.errorCodeName)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    currentUri?.let { ResumeStore.save(it, 0, 0); markWatched(it) }
                    if (settings.autoplayNext) playNext()
                }
            }
        })
        viewModelScope.launch { while (isActive) { delay(10_000); savePosition() } }
        // MediaSession + notification: connecting a controller starts/binds PlaybackService.
        PlayerHolder.player = player
        controllerFuture = runCatching {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }.getOrNull()
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { _tracks.value = TrackOptions.from(tracks) }
            override fun onVideoSizeChanged(videoSize: VideoSize) { _videoSize.value = videoSize.width to videoSize.height }
        })
    }


    /** Select an audio/text track; [option] null for text = subtitles off. */
    fun selectTrack(type: Int, option: TrackOption?) {
        val b = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            b.setTrackTypeDisabled(type, true)
        } else {
            b.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(player.currentTracks.groups[option.groupIndex].mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = b.build()
    }

    fun retry() {
        _error.value = null
        player.prepare()
        player.playWhenReady = true
    }

    fun play(uri: String) {
        if (uri == currentUri) return
        savePosition()
        currentUri = uri
        viewModelScope.launch {
            val folder = folderListing(uri)
            val item = MediaItem.Builder().setUri(uri)
            if (settings.autoLoadSubtitles && folder != null) {
                val ref = PlaybackRef.parse(uri)!!
                val delayMs = subtitleDelay(uri)
                _subDelay.value = delayMs
                val matches = SubtitleMatcher.find(ref.path.substringAfterLast('/'), folder)
                _hasExternalSubs.value = matches.isNotEmpty()
                val subs = matches.map { m ->
                    val subUri = ref.withPath(m.file.path)
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(if (delayMs != 0L) shiftedCopy(subUri, m.file.name, delayMs) ?: subUri else subUri))
                        .setMimeType(m.mimeType)
                        .setLanguage(m.language)
                        .setLabel(m.file.name)
                        .setSelectionFlags(
                            if (m.language != null && m.language.startsWith(settings.subtitleLanguage.take(2)) && settings.subtitleLanguage.isNotBlank())
                                C.SELECTION_FLAG_DEFAULT else 0,
                        )
                        .build()
                }
                item.setSubtitleConfigurations(subs)
            }
            val start = if (settings.resume) ResumeStore.get(uri) ?: 0L else 0L
            player.setMediaItem(item.build(), start)
            player.prepare()
            player.playWhenReady = true
        }
    }

    /** SMB/WebDAV/HTTP-autoindex: lists the video's folder (for sidecar subtitles and next-video). */
    private suspend fun folderListing(uri: String): List<RemoteFile>? {
        val ref = PlaybackRef.parse(uri) ?: return null
        val server = ServerStore.get(ref.serverId) ?: return null
        if (ref.path.isEmpty()) return null // direct HTTP link, no folder
        return runCatching { ServerStore.repositoryFor(server.type).list(server, ref.path.substringBeforeLast('/', "")) }.getOrNull()
    }

    /** "Up next" countdown: title + seconds left; null = hidden. */
    data class UpNext(val title: String, val uri: String, val secondsLeft: Int)
    private val _upNext = MutableStateFlow<UpNext?>(null)
    val upNext: StateFlow<UpNext?> = _upNext.asStateFlow()
    private var upNextJob: kotlinx.coroutines.Job? = null

    private fun markWatched(uri: String) {
        val ref = PlaybackRef.parse(uri) ?: return
        com.assassins377.remoteplayer.settings.WatchedStore.set(com.assassins377.remoteplayer.settings.WatchedStore.key(ref.serverId, ref.path), true)
    }

    /** Next episode from the library (series order), else next video in the folder; shown with a countdown. */
    private fun playNext() {
        val uri = currentUri ?: return
        val ref = PlaybackRef.parse(uri) ?: return
        upNextJob?.cancel()
        upNextJob = viewModelScope.launch {
            val server = ServerStore.get(ref.serverId) ?: return@launch
            val (title, nextUri) = nextEpisode(ref, server) ?: run {
                val folder = folderListing(uri) ?: return@launch
                val next = FileSorter.nextVideo(folder, ref.path.substringAfterLast('/')) ?: return@launch
                com.assassins377.remoteplayer.meta.TitleParser.parse(next.name).display to ref.withPath(next.path)
            }
            for (s in COUNTDOWN_SEC downTo 1) { _upNext.value = UpNext(title, nextUri, s); delay(1000) }
            _upNext.value = null
            play(nextUri)
        }
    }

    private suspend fun nextEpisode(ref: PlaybackRef, server: com.assassins377.remoteplayer.data.ServerConfig): Pair<String, String>? = runCatching {
        val dao = com.assassins377.remoteplayer.library.LibraryStore.db.dao()
        val cur = dao.find(ref.serverId, ref.path.trim('/')) ?: return null
        val key = cur.seriesKey ?: return null
        val next = com.assassins377.remoteplayer.library.Episodes.next(dao.episodes(key, listOf(ref.serverId)), cur) ?: return null
        next.title to ServerStore.repositoryFor(server.type).playbackUri(server, RemoteFile(next.name, next.path, false))
    }.getOrNull()

    fun playUpNextNow() { val u = _upNext.value ?: return; upNextJob?.cancel(); _upNext.value = null; play(u.uri) }
    fun cancelUpNext() { upNextJob?.cancel(); _upNext.value = null }

    fun savePosition() {
        val uri = currentUri ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        if (settings.resume) ResumeStore.save(uri, player.currentPosition, dur)
        if (com.assassins377.remoteplayer.settings.WatchedStore.shouldMark(player.currentPosition, dur)) markWatched(uri)
    }

    private val _subDelay = MutableStateFlow(0L)
    /** Current external-subtitle delay in ms (+ = later). */
    val subDelay: StateFlow<Long> = _subDelay.asStateFlow()
    private val _hasExternalSubs = MutableStateFlow(false)
    val hasExternalSubs: StateFlow<Boolean> = _hasExternalSubs.asStateFlow()

    private fun subtitleDelay(uri: String) = com.assassins377.remoteplayer.player.SubtitleDelayStore.get(uri) ?: settings.subtitleOffsetMs.toLong()

    /** Downloads the sidecar file, shifts its cue times and stores it in cacheDir; null on failure (unshifted file is used). */
    private suspend fun shiftedCopy(subUri: String, name: String, ms: Long): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val app = getApplication<Application>()
            val raw = com.assassins377.remoteplayer.player.RemoteBytes.readAll(app, subUri)
            val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString() }
                .getOrElse { String(raw, charset("windows-1251")) }
            val ext = name.substringAfterLast('.', "srt")
            val dir = java.io.File(app.cacheDir, "subs").apply { mkdirs() }
            val out = java.io.File(dir, "${(subUri + ms).hashCode().toUInt()}.$ext")
            out.writeText(com.assassins377.remoteplayer.player.SubtitleShift.shift(text.removePrefix("\uFEFF"), ms, ext))
            android.net.Uri.fromFile(out).toString()
        }.getOrNull()
    }

    /** Changes the subtitle delay for the current file (remembered) and reloads at the same position. */
    fun setSubtitleDelay(ms: Long) {
        val uri = currentUri ?: return
        com.assassins377.remoteplayer.player.SubtitleDelayStore.set(uri, ms)
        _subDelay.value = ms
        savePosition()
        val pos = player.currentPosition; val wasPlaying = player.playWhenReady
        currentUri = null
        play(uri)
        viewModelScope.launch { delay(300); if (player.currentPosition < pos) player.seekTo(pos); player.playWhenReady = wasPlaying }
    }

    fun setSpeed(value: Float) {
        _speed.value = value
        player.setPlaybackSpeed(value)
    }

    fun seekBy(deltaMs: Long) = player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))

    override fun onCleared() {
        savePosition()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        PlayerHolder.detach()
        player.release()
    }
}
