package com.assassins377.remoteplayer.ui.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.assassins377.remoteplayer.player.RoutingDataSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the ExoPlayer so playback survives configuration changes. */
@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSourceFactory(DefaultDataSource.Factory(app))))
        .build()

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
