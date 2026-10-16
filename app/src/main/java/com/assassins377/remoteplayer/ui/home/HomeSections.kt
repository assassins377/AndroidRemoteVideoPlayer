package com.assassins377.remoteplayer.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.assassins377.remoteplayer.R
import com.assassins377.remoteplayer.data.PlaybackRef
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.meta.TitleParser
import com.assassins377.remoteplayer.meta.TmdbRepository
import com.assassins377.remoteplayer.settings.Favorite
import com.assassins377.remoteplayer.settings.FavoritesStore
import com.assassins377.remoteplayer.settings.ResumeEntry
import com.assassins377.remoteplayer.settings.ResumeStore

/** One "continue watching" card model; pure mapping for tests. */
data class ContinueItem(val uri: String, val title: String, val serverName: String, val progress: Float?, val path: String)

object ContinueItems {
    fun build(entries: List<Pair<String, ResumeEntry>>, serverName: (String) -> String?): List<ContinueItem> = entries.mapNotNull { (uri, e) ->
        val ref = PlaybackRef.parse(uri) ?: return@mapNotNull null
        val name = serverName(ref.serverId) ?: return@mapNotNull null // server deleted
        val file = ref.path.substringAfterLast('/').ifEmpty { name }
        ContinueItem(uri, TitleParser.parse(file).display, name, e.progress, ref.path)
    }
}

/** Horizontal "Continue watching" row with progress bars, shown on the server list. */
@Composable
fun ContinueWatchingRow(onPlay: (String) -> Unit) {
    val version by ResumeStore.version.collectAsStateWithLifecycle()
    val servers by ServerStore.servers.collectAsStateWithLifecycle()
    val items = remember(version, servers) { ContinueItems.build(ResumeStore.recent(15)) { id -> servers.firstOrNull { it.id == id }?.name } }
    if (items.isEmpty()) return
    Column {
        Text(stringResource(R.string.continue_watching), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.uri }) { item ->
                val poster = remember(item.path) { TmdbRepository.cached(TitleParser.parse(item.path.substringAfterLast('/')))?.posterUrl("w185") }
                Card(Modifier.width(150.dp).clickable { onPlay(item.uri) }) {
                    Box {
                        if (poster != null) AsyncImage(poster, null, Modifier.fillMaxWidth().height(84.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
                        else Box(Modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.Movie, null, Modifier.size(40.dp)) }
                        IconButton(onClick = { ResumeStore.remove(item.uri) }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                            Icon(Icons.Default.Close, stringResource(R.string.remove_from_history), Modifier.size(16.dp))
                        }
                    }
                    item.progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
                    Column(Modifier.padding(8.dp)) {
                        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(item.serverName, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(onOpenFolder: (Favorite) -> Unit, onPlay: (String) -> Unit, onBack: () -> Unit) {
    val favs by FavoritesStore.items.collectAsStateWithLifecycle()
    val servers by ServerStore.servers.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.favorites)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        val visible = favs.filter { f -> servers.any { it.id == f.serverId } }
        if (visible.isEmpty()) Text(stringResource(R.string.no_favorites), Modifier.padding(pad).padding(24.dp))
        else LazyColumn(Modifier.padding(pad)) {
            items(visible, key = { it.key }) { f ->
                val server = servers.first { it.id == f.serverId }
                ListItem(
                    leadingContent = { Icon(if (f.isDirectory) Icons.Default.Folder else Icons.Default.Movie, null) },
                    headlineContent = { Text(if (f.isDirectory) f.name else TitleParser.parse(f.name).display, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${server.name} · /${f.path}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { FavoritesStore.toggle(f) }) {
                            Icon(Icons.Default.Star, stringResource(R.string.remove_favorite), tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.clickable {
                        if (f.isDirectory) onOpenFolder(f)
                        else onPlay(ServerStore.repositoryFor(server.type).playbackUri(server, RemoteFile(f.name, f.path, false)))
                    },
                )
            }
        }
    }
}
