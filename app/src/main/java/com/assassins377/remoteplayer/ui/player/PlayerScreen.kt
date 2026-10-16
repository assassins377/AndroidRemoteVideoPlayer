package com.assassins377.remoteplayer.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.util.TypedValue
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
object PipState { @Volatile var playing = false }

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
    LifecycleStartEffect(vm) { onStopOrDispose { vm.savePosition(); vm.player.pause() } }
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
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
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { if (hint != null) { delay(900); hint = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    player = vm.player
                    setShowSubtitleButton(true)
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
        if (s.swipeGestures || s.doubleTapSeek) {
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
                        Button(onClick = vm::retry) { Text("Повторить") }
                        OutlinedButton(onClick = onBack) { Text("Назад") }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            FilledTonalButton(onClick = { menu = true }) { Text("${speed}x") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                AppSettings.SPEEDS.forEach { sp ->
                    DropdownMenuItem(text = { Text("${sp}x") }, onClick = { vm.setSpeed(sp); menu = false })
                }
            }
        }
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
                        onHint((if (fwd) "+" else "−") + "${s.seekStepSec} с")
                    },
                )
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
                        onHint("Яркость ${(v * 100).toInt()}%")
                    } else {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val steps = (acc * max * 1.5f).toInt()
                        if (steps != 0) {
                            acc = 0f
                            val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            onHint("Громкость ${v * 100 / max}%")
                        }
                    }
                }
            },
    )
}
