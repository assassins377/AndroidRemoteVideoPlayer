package com.assassins377.remoteplayer.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.meta.TitleParser
import java.io.File

@Composable
fun DownloadsScreen(nav: Nav) {
    val items by Env.downloads.items.collectAsState()
    var folder by remember { mutableStateOf(Env.downloads.folder.absolutePath) }
    Column {
        Bar(Strings["downloads"], nav::back) {
            IconButton(onClick = { runCatching { Env.downloads.folder.mkdirs(); if (Os.isWindows) java.awt.Desktop.getDesktop().open(Env.downloads.folder) else ProcessBuilder("xdg-open", Env.downloads.folder.absolutePath).start() } }) {
                Icon(Icons.Default.FolderOpen, Strings["open_folder"])
            }
        }
        Row(Modifier.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(folder, { folder = it }, label = { Text(Strings["download_folder"]) }, singleLine = true, modifier = Modifier.weight(1f))
            Button(enabled = folder.isNotBlank() && folder != Env.downloads.folder.absolutePath, onClick = { Env.downloads.folder = File(folder) }) { Text(Strings["save"]) }
        }
        if (items.isEmpty()) Text(Strings["no_downloads"], Modifier.padding(24.dp))
        LazyColumn {
            items(items.reversed(), key = { it.id }) { d ->
                ListItem(
                    headlineContent = { Text(TitleParser.parse(d.name).display, maxLines = 1) },
                    supportingContent = {
                        Column {
                            val st = when (d.status) {
                                DownloadStatus.QUEUED -> Strings["dl_queued"]; DownloadStatus.DONE -> Strings["dl_done"]
                                DownloadStatus.FAILED -> Strings["dl_failed"] + (d.error?.let { ": $it" } ?: ""); DownloadStatus.CANCELLED -> Strings["dl_cancelled"]
                                DownloadStatus.RUNNING -> d.progress?.let { "${(it * 100).toInt()}%" } ?: ""
                            }
                            Text(listOf(st, TitleParser.formatSize(d.bytes) + if (d.total > 0) " / " + TitleParser.formatSize(d.total) else "").filter { it.isNotBlank() }.joinToString(" · "))
                            if (d.status == DownloadStatus.RUNNING) d.progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(top = 4.dp)) } ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    },
                    trailingContent = {
                        Row {
                            if (d.status == DownloadStatus.DONE) Env.servers.get(d.serverId)?.let { srv ->
                                IconButton(onClick = { nav.go(Screen.Player(srv, RemoteFile(d.name, d.path, false, d.total))) }) { Icon(Icons.Default.PlayArrow, null) }
                            }
                            if (d.status == DownloadStatus.FAILED || d.status == DownloadStatus.CANCELLED) IconButton(onClick = { Env.downloads.retry(d.id) }) { Icon(Icons.Default.Refresh, Strings["retry"]) }
                            if (d.status == DownloadStatus.QUEUED || d.status == DownloadStatus.RUNNING) IconButton(onClick = { Env.downloads.cancel(d.id) }) { Icon(Icons.Default.Close, Strings["cancel_download"]) }
                            else IconButton(onClick = { Env.downloads.remove(d.id, deleteFile = d.status != DownloadStatus.DONE) }) { Icon(Icons.Default.Delete, Strings["remove"]) }
                        }
                    },
                )
            }
        }
    }
}
