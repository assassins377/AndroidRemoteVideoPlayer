package com.assassins377.remoteplayer.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(uri: String, onBack: () -> Unit, vm: PlayerViewModel = viewModel()) {
    LaunchedEffect(uri) { vm.play(uri) }
    LifecycleStartEffect(vm) { onStopOrDispose { vm.player.pause() } }
    val speed by vm.speed.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = vm.player
                    setShowSubtitleButton(true) // built-in subtitle/track selection; seek bar included
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            FilledTonalButton(onClick = { menu = true }) { Text("${speed}x") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SPEEDS.forEach { s ->
                    DropdownMenuItem(text = { Text("${s}x") }, onClick = { vm.setSpeed(s); menu = false })
                }
            }
        }
    }
}
