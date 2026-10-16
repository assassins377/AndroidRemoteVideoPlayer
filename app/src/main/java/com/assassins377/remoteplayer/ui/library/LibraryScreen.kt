package com.assassins377.remoteplayer.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import com.assassins377.remoteplayer.library.LibraryQuery
import com.assassins377.remoteplayer.library.LibrarySort
import com.assassins377.remoteplayer.library.LibraryStore
import com.assassins377.remoteplayer.settings.SettingsStore
import com.assassins377.remoteplayer.ui.browser.Poster
import com.assassins377.remoteplayer.ui.browser.headline
import com.assassins377.remoteplayer.ui.browser.rememberMeta
import com.assassins377.remoteplayer.ui.browser.subline
import java.text.DateFormat
import java.util.Date

/** Combined library of all servers: search, sort, server filter; tap plays via the existing player (resume included). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onPlay: (String) -> Unit, onBack: () -> Unit) {
    val st by LibraryStore.state.collectAsStateWithLifecycle()
    val servers by ServerStore.servers.collectAsStateWithLifecycle()
    val settings by SettingsStore.settings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.TITLE) }
    var serverFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val live = remember(servers) { servers.map { it.id }.toSet() }
    val list = remember(st.data, query, sort, serverFilter, live) {
        LibraryQuery.apply(st.data.entries, query, serverFilter?.let { setOf(it) }, sort, live)
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.library)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = { LibraryStore.refresh() }, enabled = !st.scanning) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.library_refresh))
                }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (st.scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.library_scanning) + " " + st.progress, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.library_search_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(LibrarySort.TITLE to R.string.library_sort_title, LibrarySort.YEAR to R.string.library_sort_year,
                    LibrarySort.ADDED to R.string.library_sort_added).forEach { (k, label) ->
                    FilterChip(selected = sort == k, onClick = { sort = k }, label = { Text(stringResource(label)) })
                }
            }
            if (servers.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = serverFilter == null, onClick = { serverFilter = null }, label = { Text(stringResource(R.string.library_all_servers)) })
                servers.forEach { s -> FilterChip(selected = serverFilter == s.id, onClick = { serverFilter = s.id }, label = { Text(s.name) }) }
            }
            if (st.data.updatedAt > 0) Text(
                stringResource(R.string.library_count_fmt, list.size, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(st.data.updatedAt))),
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
            st.data.errors.forEach { (id, msg) ->
                val name = servers.firstOrNull { it.id == id }?.name ?: return@forEach
                Text(stringResource(R.string.library_error_fmt, name, msg), Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            when {
                st.data.entries.isEmpty() && !st.scanning -> Text(stringResource(R.string.library_empty), Modifier.padding(24.dp))
                list.isEmpty() && !st.scanning -> Text(stringResource(R.string.library_nothing_found), Modifier.padding(24.dp))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.key }) { e ->
                        val server = servers.firstOrNull { it.id == e.serverId } ?: return@items
                        val f = remember(e) { e.toRemoteFile() }
                        val (p, m) = rememberMeta(f, settings, e.serverId)
                        ListItem(
                            leadingContent = { Poster(f, m, Modifier.size(48.dp, 72.dp)) },
                            headlineContent = { Text(headline(f, p, m), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    subline(f, p)?.let { Text(it, maxLines = 1) }
                                    Text("/" + e.path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            trailingContent = { AssistChip(onClick = { serverFilter = server.id }, label = { Text(server.name, maxLines = 1) }) },
                            modifier = Modifier.clickable { onPlay(ServerStore.repositoryFor(server.type).playbackUri(server, f)) },
                        )
                    }
                }
            }
        }
    }
}
