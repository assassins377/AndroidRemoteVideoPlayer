package com.assassins377.remoteplayer.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picks a DLNA renderer or Chromecast on the LAN. */
@Composable
fun CastDialog(onPick: (CastTarget) -> Unit, onDismiss: () -> Unit) {
    var found by remember { mutableStateOf<List<CastTarget>?>(null) }
    var scan by remember { mutableStateOf(0) }
    LaunchedEffect(scan) {
        found = null
        found = withContext(Dispatchers.IO) {
            val dlna = async { runCatching { Dlna.discover().map(::DlnaTarget) }.getOrDefault(emptyList()) }
            val cc = async { CastClient.discover().map(::ChromecastTarget) }
            cc.await() + dlna.await()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings["cast_to"]) },
        text = {
            Column(Modifier.widthIn(min = 320.dp)) {
                when {
                    found == null -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(Strings["cast_searching"], Modifier.padding(top = 8.dp)) }
                    found!!.isEmpty() -> Text(Strings["cast_none"])
                    else -> found!!.forEach { r ->
                        ListItem(headlineContent = { Text(r.name) }, supportingContent = { Text("${r.kind} · ${r.host}") },
                            leadingContent = { Icon(if (r is ChromecastTarget) Icons.Default.Cast else Icons.Default.Tv, null) }, modifier = Modifier.clickable { onPick(r) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { scan++ }) { Text(Strings["refresh"]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings["close"]) } },
    )
}

/** Remote-control overlay while the video plays on a renderer. */
@Composable
fun CastOverlay(r: CastTarget, onStop: (positionMs: Long) -> Unit, modifier: Modifier = Modifier) {
    var pos by remember { mutableStateOf(0L to 0L) }
    var paused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun act(f: () -> Unit) = scope.launch(Dispatchers.IO) { runCatching(f).onFailure { error = it.message } }
    LaunchedEffect(r) { while (true) { withContext(Dispatchers.IO) { runCatching { pos = r.position() } }; delay(1000) } }
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.CastConnected, null, tint = Color.White, modifier = Modifier.size(64.dp))
        Text(Strings.fmt("casting_to", r.name), color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(PlayerMath.fmt(pos.first) + " / " + PlayerMath.fmt(pos.second), color = Color.White)
        if (pos.second > 0) Slider(pos.first.toFloat() / pos.second, { v -> act { r.seek((v * pos.second).toLong()) } }, Modifier.widthIn(max = 480.dp))
        Row {
            IconButton(onClick = { act { if (paused) r.resume() else r.pause(); paused = !paused } }) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = Color.White) }
            IconButton(onClick = { val p = pos.first; act { r.stop() }; onStop(p) }) { Icon(Icons.Default.Stop, Strings["cast_stop"], tint = Color.White) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
