package com.assassins377.remoteplayer.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.data.Repositories
import com.assassins377.remoteplayer.meta.Episode
import com.assassins377.remoteplayer.meta.SeriesGrouper
import com.assassins377.remoteplayer.meta.TitleParser
import com.assassins377.remoteplayer.settings.WatchedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Seasons/episodes of a series folder (season subfolders are listed one level deep). */
@Composable
fun SeriesScreen(nav: Nav, sc: Screen.Series) {
    var seasons by remember(sc) { mutableStateOf<Map<Int, List<Episode>>?>(null) }
    var error by remember(sc) { mutableStateOf<String?>(null) }
    val watched by WatchedStore.items.collectAsState()
    LaunchedEffect(sc) {
        runCatching {
            withContext(Dispatchers.IO) {
                val repo = Repositories.forType(sc.server.type)
                val top = repo.list(sc.server, sc.path)
                top + top.filter { it.isDirectory && !it.hidden }.flatMap { d -> runCatching { repo.list(sc.server, d.path.trim('/')) }.getOrDefault(emptyList()) }
            }
        }.onSuccess { seasons = SeriesGrouper.group(it) }.onFailure { error = it.message ?: it.javaClass.simpleName; seasons = emptyMap() }
    }
    val playlist = remember(seasons) { seasons?.let(SeriesGrouper::playlist) ?: emptyList() }
    fun key(f: RemoteFile) = WatchedStore.key(sc.server.id, f.path)
    val title = remember(sc) { sc.path.substringAfterLast('/').ifEmpty { sc.server.name }.let { TitleParser.parseFolder(it)?.title ?: it } }
    Column {
        Bar(title, nav::back) {
            playlist.firstOrNull { key(it) !in watched }?.let { f ->
                TextButton(onClick = { nav.go(Screen.Player(sc.server, f, playlist)) }) { Icon(Icons.Default.PlayArrow, null); Text(Strings["play_next_unwatched"]) }
            }
        }
        when {
            seasons == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            error != null -> Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            seasons!!.isEmpty() -> Text(Strings["empty_folder"], Modifier.padding(24.dp))
            else -> LazyColumn {
                seasons!!.forEach { (season, eps) ->
                    item(key = "s$season") {
                        val done = eps.count { key(it.file) in watched }
                        Text(Strings.fmt("season", season) + "  ·  $done/${eps.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 12.dp))
                    }
                    items(eps, key = { it.file.path }) { e ->
                        val w = key(e.file) in watched
                        ListItem(
                            headlineContent = { Text((e.episode?.let { "%02d. ".format(it) } ?: "") + e.parsed.display) },
                            supportingContent = { Text(TitleParser.subtitleLine(e.parsed, e.file.size)) },
                            trailingContent = {
                                IconButton(onClick = { WatchedStore.toggle(key(e.file)) }) {
                                    Icon(if (w) Icons.Default.CheckCircle else Icons.Outlined.Circle, Strings["mark_watched"])
                                }
                            },
                            modifier = Modifier.clickable { nav.go(Screen.Player(sc.server, e.file, playlist)) },
                        )
                    }
                }
            }
        }
    }
}
