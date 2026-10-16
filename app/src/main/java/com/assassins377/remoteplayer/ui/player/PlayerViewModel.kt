package com.assassins377.remoteplayer.ui.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.assassins377.remoteplayer.data.SmbError
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.assassins377.remoteplayer.player.RoutingDataSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the ExoPlayer so playback survives configuration changes. */
@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    /** Bigger buffers than default: LAN bitrate for 4K/remux MKV can spike; prefer time-based buffering. */
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 30_000,
            /* maxBufferMs = */ 120_000,
            /* bufferForPlaybackMs = */ 2_500,
            /* bufferForPlaybackAfterRebufferMs = */ 5_000,
        )
        .setTargetBufferBytes(256 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSourceFactory(DefaultDataSource.Factory(app))))
        .setLoadControl(loadControl)
        .build()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                var c: Throwable? = error
                while (c != null && c !is SmbError) c = c.cause
                _error.value = (c as? SmbError)?.message ?: "Ошибка воспроизведения: ${error.errorCodeName}"
            }
        })
    }

    fun retry() {
        _error.value = null
        player.prepare()
        player.playWhenReady = true
    }

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    private var currentUri: String? = null

    fun play(uri: String) {
        if (uri == currentUri) return
        currentUri = uri
        // TODO subtitles: MediaItem.SubtitleConfiguration for sidecar .srt/.vtt next to the video.
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    fun setSpeed(value: Float) {
        _speed.value = value
        player.setPlaybackSpeed(value)
    }

    override fun onCleared() = player.release()
}
