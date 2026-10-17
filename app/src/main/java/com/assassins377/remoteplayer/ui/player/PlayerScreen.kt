package com.assassins377.remoteplayer.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.util.TypedValue
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.assassins377.remoteplayer.settings.AppSettings
import kotlinx.coroutines.delay

/** Lets MainActivity know a video is on screen (for PiP on minimize). */
object PipState {
    @Volatile var playing = false
    @Volatile var aspect: Pair<Int, Int> = 16 to 9
    /** Compose-observable: true while the activity is in picture-in-picture. */
    val inPip = androidx.compose.runtime.mutableStateOf(false)
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(uri: String, onBack: () -> Unit, vm: PlayerViewModel = viewModel()) {
    val s = vm.settings
    LaunchedEffect(uri) { vm.play(uri) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val inPip by PipState.inPip
    val videoSize by vm.videoSize.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    var showTracks by remember { mutableStateOf(false) }
    // True fullscreen: hide status/navigation bars; swipe from edge shows them temporarily.
    DisposableEffect(activity) {
        val w = activity?.window
        val c = w?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        c?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            c?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    // PiP: correct aspect ratio; auto-enter on Android 12+ when enabled.
    LaunchedEffect(videoSize, s.pipOnMinimize) {
        PipState.aspect = PlayerMath.pipAspect(videoSize.first, videoSize.second)
        if (android.os.Build.VERSION.SDK_INT >= 31 && activity != null) runCatching {
            activity.setPictureInPictureParams(android.app.PictureInPictureParams.Builder()
                .setAspectRatio(PlayerMath.rational(PipState.aspect)).setAutoEnterEnabled(s.pipOnMinimize).build())
        }
    }
    LaunchedEffect(inPip) { if (inPip) playerView?.hideController() }
    // Background audio: keep playing when the app is minimized (MediaSession notification stays); otherwise pause.
    LifecycleStartEffect(vm) { onStopOrDispose { vm.savePosition(); if (!s.backgroundAudio || activity?.isFinishing == true) vm.player.pause() } }
    DisposableEffect(s.lockRotation) {
        PipState.playing = true
        val prev = activity?.requestedOrientation
        if (s.lockRotation) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose {
            PipState.playing = false
            if (prev != null) activity.requestedOrientation = prev
        }
    }
    val speed by vm.speed.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val error by vm.error.collectAsStateWithLifecycle()
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { if (hint != null) { delay(900); hint = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    player = vm.player
                    setShowSubtitleButton(false) // replaced by our own track dialog
                    keepScreenOn = true
                    subtitleView?.apply {
                        setStyle(subtitleStyle(s))
                        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, s.subtitleSizeSp.toFloat())
                        setApplyEmbeddedStyles(false)
                    }
                    playerView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Gesture layer over the video area; leaves top/bottom strips free for the controller buttons and seek bar.
        if (!inPip && (s.swipeGestures || s.doubleTapSeek || s.horizontalSeek)) {
            GestureLayer(s, vm, playerView, activity, onHint = { hint = it },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.7f).align(Alignment.Center))
        }
        hint?.let {
            Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.medium, color = Color.Black.copy(alpha = 0.6f)) {
                Text(it, color = Color.White, modifier = Modifier.padding(12.dp))
            }
        }
        error?.let { msg ->
            Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(msg)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::retry) { Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.retry)) }
                        OutlinedButton(onClick = onBack) { Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.back)) }
                    }
                }
            }
        }
        if (!inPip) Row(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (tracks.audio.size > 1 || tracks.text.isNotEmpty()) FilledTonalButton(onClick = { showTracks = true }) {
              Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.tracks))
          }
          Box {
            FilledTonalButton(onClick = { menu = true }) { Text("${speed}x") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                AppSettings.SPEEDS.forEach { sp ->
                    DropdownMenuItem(text = { Text("${sp}x") }, onClick = { vm.setSpeed(sp); menu = false })
                }
            }
          }
        }
        val upNext by vm.upNext.collectAsStateWithLifecycle()
        upNext?.let { u ->
            Card(Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.up_next_fmt, u.secondsLeft))
                    Text(u.title, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::playUpNextNow) { Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.up_next_play)) }
                        OutlinedButton(onClick = vm::cancelUpNext) { Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.cancel)) }
                    }
                }
            }
        }
        val subDelay by vm.subDelay.collectAsStateWithLifecycle()
        val hasExt by vm.hasExternalSubs.collectAsStateWithLifecycle()
        if (showTracks) TrackDialog(tracks, onSelect = vm::selectTrack, onDismiss = { showTracks = false },
            subDelayMs = subDelay.takeIf { hasExt }, onSubDelay = vm::setSubtitleDelay)
    }
}

@OptIn(UnstableApi::class)
fun subtitleStyle(s: AppSettings) = CaptionStyleCompat(
    s.subtitleColor.toInt(), s.subtitleBackground.toInt(), android.graphics.Color.TRANSPARENT,
    CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null,
)

@OptIn(UnstableApi::class)
@Composable
private fun GestureLayer(
    s: AppSettings, vm: PlayerViewModel, view: PlayerView?, activity: Activity?,
    onHint: (String) -> Unit, modifier: Modifier,
) {
    val ctx = LocalContext.current
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    Box(
        modifier
            .pointerInput(s) {
                detectTapGestures(
                    onTap = { view?.let { if (it.isControllerFullyVisible) it.hideController() else it.showController() } },
                    onDoubleTap = { o ->
                        if (!s.doubleTapSeek) return@detectTapGestures
                        val fwd = o.x > size.width / 2
                        vm.seekBy(if (fwd) s.seekStepMs else -s.seekStepMs)
                        onHint((if (fwd) "+" else "−") + com.assassins377.remoteplayer.AppStrings.get(com.assassins377.remoteplayer.R.string.seconds_short, "%d с", s.seekStepSec))
                    },
                )
            }
            .pointerInput(s) {
                if (!s.horizontalSeek) return@pointerInput
                var start = 0L; var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start = vm.player.currentPosition; drag = 0f },
                    onDragEnd = {
                        vm.player.seekTo(PlayerMath.swipeTarget(start, drag, size.width.toFloat(), vm.player.duration.coerceAtLeast(0)))
                    },
                ) { change, dx ->
                    change.consume(); drag += dx
                    val target = PlayerMath.swipeTarget(start, drag, size.width.toFloat(), vm.player.duration.coerceAtLeast(0))
                    onHint(PlayerMath.formatDelta(target - start) + "  →  " + PlayerMath.formatTime(target) +
                        (vm.player.duration.takeIf { it > 0 }?.let { " / " + PlayerMath.formatTime(it) } ?: ""))
                }
            }
            .pointerInput(s) {
                if (!s.swipeGestures) return@pointerInput
                var left = false
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { o -> left = o.x < size.width / 2; acc = 0f },
                ) { _, dy ->
                    acc -= dy / size.height
                    if (left) {
                        val w = activity?.window ?: return@detectVerticalDragGestures
                        val cur = w.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
                        val v = (cur - dy / size.height).coerceIn(0.01f, 1f)
                        w.attributes = w.attributes.apply { screenBrightness = v }
                        onHint(com.assassins377.remoteplayer.AppStrings.get(com.assassins377.remoteplayer.R.string.brightness_fmt, "Яркость %d%%", (v * 100).toInt()))
                    } else {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val steps = (acc * max * 1.5f).toInt()
                        if (steps != 0) {
                            acc = 0f
                            val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            onHint(com.assassins377.remoteplayer.AppStrings.get(com.assassins377.remoteplayer.R.string.volume_fmt, "Громкость %d%%", v * 100 / max))
                        }
                    }
                }
            },
    )
}

@Composable
private fun TrackDialog(t: TrackOptions, onSelect: (Int, TrackOption?) -> Unit, onDismiss: () -> Unit,
                        subDelayMs: Long? = null, onSubDelay: (Long) -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.close)) } },
        title = { Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.tracks)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                if (t.audio.isNotEmpty()) {
                    Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.audio_tracks), style = MaterialTheme.typography.titleSmall)
                    t.audio.forEach { o -> TrackRow(o.label, o.selected) { onSelect(androidx.media3.common.C.TRACK_TYPE_AUDIO, o) } }
                }
                Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.subtitle_tracks), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp))
                TrackRow(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.subs_off), t.textOff) { onSelect(androidx.media3.common.C.TRACK_TYPE_TEXT, null) }
                t.text.forEach { o -> TrackRow(o.label, o.selected) { onSelect(androidx.media3.common.C.TRACK_TYPE_TEXT, o) } }
                if (subDelayMs != null) {
                    Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.sub_delay_fmt, PlayerMath.formatDelay(subDelayMs)),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(-500L, -100L, 100L, 500L).forEach { d ->
                            OutlinedButton(onClick = { onSubDelay(subDelayMs + d) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(PlayerMath.formatDelay(d)) }
                        }
                    }
                    TextButton(onClick = { onSubDelay(0) }) { Text(androidx.compose.ui.res.stringResource(com.assassins377.remoteplayer.R.string.sub_delay_reset)) }
                }
            }
        },
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
