package com.assassins377.remoteplayer.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.assassins377.remoteplayer.R
import com.assassins377.remoteplayer.meta.TitleParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onPlay: (String) -> Unit, onBack: () -> Unit) {
    val items by DownloadStore.items.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val infos by remember { WorkManager.getInstance(ctx).getWorkInfosByTagFlow(DownloadStore.TAG) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirm by remember { mutableStateOf<DownloadItem?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.downloads)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            Text(stringResource(R.string.downloads_storage_fmt, TitleParser.formatSize(DownloadStore.usedBytes()), TitleParser.formatSize(DownloadStore.freeBytes())),
                Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall)
            if (items.isEmpty()) Text(stringResource(R.string.downloads_empty), Modifier.padding(24.dp))
            LazyColumn {
                items(items.sortedByDescending { it.addedAt }, key = { it.uri }) { d ->
                    val info = infos.filter { DownloadStore.tagFor(d.uri) in it.tags }.maxByOrNull { if (it.state.isFinished) 0 else 1 }
                    val done = info?.progress?.getLong("done", -1) ?: -1; val total = info?.progress?.getLong("total", -1) ?: -1
                    val status = when {
                        d.done -> TitleParser.formatSize(d.totalBytes.coerceAtLeast(0))
                        info?.state == WorkInfo.State.RUNNING && total > 0 -> "${done * 100 / total}% · ${TitleParser.formatSize(done)} / ${TitleParser.formatSize(total)}"
                        info?.state == WorkInfo.State.FAILED -> stringResource(R.string.download_failed)
                        info?.state == WorkInfo.State.CANCELLED -> stringResource(R.string.download_failed)
                        else -> stringResource(R.string.download_waiting)
                    }
                    ListItem(
                        headlineContent = { Text(TitleParser.parse(d.name).display, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text(status)
                                if (!d.done && total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                                if (info?.state == WorkInfo.State.FAILED || info?.state == WorkInfo.State.CANCELLED)
                                    TextButton(onClick = { DownloadStore.enqueue(d.uri, d.name) }) { Text(stringResource(R.string.retry)) }
                            }
                        },
                        trailingContent = { IconButton(onClick = { confirm = d }) { Icon(Icons.Default.Delete, stringResource(R.string.download_delete)) } },
                        modifier = Modifier.clickable(enabled = d.done) { onPlay(d.uri) },
                    )
                }
            }
        }
    }
    confirm?.let { d ->
        AlertDialog(onDismissRequest = { confirm = null },
            confirmButton = { TextButton(onClick = { DownloadStore.remove(d.uri); confirm = null }) { Text(stringResource(R.string.download_delete)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
            text = { Text(stringResource(R.string.download_delete_q)) })
    }
}
