package com.assassins377.remoteplayer.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assassins377.remoteplayer.R
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.library.LibraryStore
import com.assassins377.remoteplayer.library.toRemoteFile
import com.assassins377.remoteplayer.settings.ResumeStore
import com.assassins377.remoteplayer.settings.WatchedStore

/** Seasons (tabs) and episodes of one series; watched marks (long-press toggles), resume progress. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SeriesScreen(seriesKey: String, onPlay: (String) -> Unit, onBack: () -> Unit) {
    val servers by ServerStore.servers.collectAsStateWithLifecycle()
    val live = remember(servers) { servers.map { it.id } }
    val episodes by remember(seriesKey, live) { LibraryStore.db.dao().observeEpisodes(seriesKey, live) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val watched by WatchedStore.items.collectAsStateWithLifecycle()
    val resumeVersion by ResumeStore.version.collectAsStateWithLifecycle()
    val seasons = remember(episodes) { episodes.mapNotNull { it.season }.distinct().sorted() }
    var season by rememberSaveable { mutableStateOf<Int?>(null) }
    val current = season?.takeIf { it in seasons } ?: seasons.firstOrNull()
    val title = episodes.firstOrNull()?.seriesTitle ?: seriesKey
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad)) {
            if (seasons.size > 1) ScrollableTabRow(selectedTabIndex = seasons.indexOf(current).coerceAtLeast(0), edgePadding = 8.dp) {
                seasons.forEach { s -> Tab(selected = s == current, onClick = { season = s }, text = { Text(stringResource(R.string.season_fmt, s)) }) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(episodes.filter { it.season == current }, key = { it.id }) { e ->
                    val server = servers.firstOrNull { it.id == e.serverId } ?: return@items
                    val f = remember(e) { e.toRemoteFile() }
                    val uri = remember(e, server) { ServerStore.repositoryFor(server.type).playbackUri(server, f) }
                    val key = WatchedStore.key(e.serverId, e.path)
                    val isWatched = key in watched
                    val progress = remember(resumeVersion, uri) { ResumeStore.recent(500).firstOrNull { it.first == uri }?.second?.progress }
                    ListItem(
                        leadingContent = {
                            IconButton(onClick = { WatchedStore.toggle(key) }) {
                                Icon(if (isWatched) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    stringResource(if (isWatched) R.string.mark_unwatched else R.string.mark_watched),
                                    tint = if (isWatched) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                            }
                        },
                        headlineContent = { Text((e.episode?.let { stringResource(R.string.episode_fmt, it) + " · " } ?: "") + e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text(server.name, style = MaterialTheme.typography.labelSmall)
                                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
                            }
                        },
                        modifier = Modifier.combinedClickable(onClick = { onPlay(uri) }, onLongClick = { WatchedStore.toggle(key) }),
                    )
                }
            }
        }
    }
}
