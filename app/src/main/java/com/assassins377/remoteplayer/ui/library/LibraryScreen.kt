package com.assassins377.remoteplayer.ui.library

import kotlinx.coroutines.flow.catch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assassins377.remoteplayer.R
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.library.*
import com.assassins377.remoteplayer.settings.SettingsStore
import com.assassins377.remoteplayer.ui.browser.Poster
import com.assassins377.remoteplayer.ui.browser.headline
import com.assassins377.remoteplayer.ui.browser.rememberMeta
import com.assassins377.remoteplayer.ui.browser.subline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.DateFormat
import java.util.Date

private const val PAGE = 200

/** Combined library: FTS search (debounced), SQL sort/filter, LIMIT-based paging, scan progress with cancel. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onPlay: (String) -> Unit, onBack: () -> Unit, onOpenSeries: (String) -> Unit = {}) {
    val st by LibraryStore.state.collectAsStateWithLifecycle()
    val servers by ServerStore.servers.collectAsStateWithLifecycle()
    val settings by SettingsStore.settings.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var query by remember { mutableStateOf(input) }
    LaunchedEffect(input) { if (input.isNotEmpty()) delay(250); query = input }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.TITLE) }
    var serverFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf(LibraryMode.ALL) }
    var limit by remember(query, sort, serverFilter, mode) { mutableIntStateOf(PAGE) }
    val live = remember(servers) { servers.map { it.id } }
    val filter = serverFilter?.let { listOf(it) }
    val dao = LibraryStore.db.dao()
    val list by remember(query, sort, filter, live, limit, mode) { dao.observe(LibrarySql.list(query, filter, sort, live, limit, mode = mode)) }
        .collectAsStateWithLifecycle(initialValue = null)
    val seriesList by remember(query, sort, filter, live, limit, mode) {
        if (mode == LibraryMode.SERIES) dao.observeSeries(LibrarySql.series(query, filter, sort, live, limit)) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val total by remember(query, filter, live, mode) { dao.observeCount(LibrarySql.count(query, filter, live, mode)) }.collectAsStateWithLifecycle(initialValue = 0)
    val listState = rememberLazyListState()
    val favs by com.assassins377.remoteplayer.settings.FavoritesStore.items.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<Triple<String, com.assassins377.remoteplayer.data.RemoteFile, Pair<com.assassins377.remoteplayer.meta.ParsedTitle?, com.assassins377.remoteplayer.meta.TmdbMeta?>>?>(null) }
    details?.let { (sid, f, pm) ->
        com.assassins377.remoteplayer.ui.browser.DetailsSheet(sid, f, pm.first, pm.second,
            onPlay = { details = null; ServerStore.get(sid)?.let { srv -> onPlay(ServerStore.repositoryFor(srv.type).playbackUri(srv, f)) } },
            onDismiss = { details = null })
    }
    LaunchedEffect(listState, list) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
            val n = list?.size ?: 0
            if (n >= limit && last >= n - 30) limit += PAGE
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.library)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (st.scanning) IconButton(onClick = { LibraryStore.cancel() }) { Icon(Icons.Default.Close, stringResource(R.string.library_cancel)) }
                else IconButton(onClick = { LibraryStore.refresh() }) { Icon(Icons.Default.Refresh, stringResource(R.string.library_refresh)) }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (st.scanning) {
                val n = st.progress.size.coerceAtLeast(1)
                LinearProgressIndicator(progress = { st.serversDone.toFloat() / n }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.library_progress_fmt, st.serversDone, st.progress.size, st.filesFound),
                    Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
                st.progress.values.filter { !it.done }.forEach { p ->
                    Text(stringResource(R.string.library_server_progress_fmt, p.name, p.folders, p.files),
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            var focused by remember { mutableStateOf(false) }
            val recent by RecentSearches.items.collectAsStateWithLifecycle()
            // Same filters as the list; re-emits as scan batches arrive.
            val suggestions by remember(query, live, filter, mode) {
                LibrarySql.suggestions(query, live, servers = filter, mode = mode)?.let { q -> dao.observeStrings(q).catch { emit(emptyList()) } }
                    ?: kotlinx.coroutines.flow.flowOf(emptyList())
            }.collectAsStateWithLifecycle(initialValue = emptyList())
            OutlinedTextField(input, { input = it }, placeholder = { Text(stringResource(R.string.library_search_hint)) },
                singleLine = true,
                trailingIcon = { if (input.isNotEmpty()) IconButton(onClick = { input = "" }) { Icon(Icons.Default.Close, stringResource(R.string.clear)) } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { RecentSearches.add(input) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .onFocusChanged { focused = it.isFocused })
            val chips = if (input.isBlank()) recent.takeIf { focused }.orEmpty() else suggestions.filterNot { it.equals(input.trim(), true) }
            if (chips.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { c -> SuggestionChip(onClick = { input = c; query = c; RecentSearches.add(c) }, label = { Text(c, maxLines = 1) }) }
                if (input.isBlank()) TextButton(onClick = { RecentSearches.clear() }) { Text(stringResource(R.string.clear)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(LibraryMode.ALL to R.string.library_mode_all, LibraryMode.MOVIES to R.string.library_mode_movies,
                    LibraryMode.SERIES to R.string.library_mode_series).forEach { (k, label) ->
                    FilterChip(selected = mode == k, onClick = { mode = k }, label = { Text(stringResource(label)) })
                }
            }
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
            val info = buildList {
                add(stringResource(R.string.library_total_fmt, total))
                if (st.updatedAt > 0) add(stringResource(R.string.library_updated_fmt, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(st.updatedAt))))
            }.joinToString(" · ")
            Text(info, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
            st.errors.forEach { (id, msg) ->
                val name = servers.firstOrNull { it.id == id }?.name ?: return@forEach
                Text(stringResource(R.string.library_error_fmt, name, msg), Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val rows = list
            when {
                mode == LibraryMode.SERIES -> if (seriesList.isEmpty() && !st.scanning) Text(stringResource(R.string.library_nothing_found), Modifier.padding(24.dp))
                    else LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(seriesList, key = { it.seriesKey }) { sr -> SeriesItem(sr, settings) { if (query.isNotBlank()) RecentSearches.add(query); onOpenSeries(sr.seriesKey) } }
                    }
                rows == null -> {}
                rows.isEmpty() && !st.scanning -> Text(stringResource(if (query.isBlank() && serverFilter == null) R.string.library_empty else R.string.library_nothing_found), Modifier.padding(24.dp))
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(rows, key = { it.id }) { e ->
                        val server = servers.firstOrNull { it.id == e.serverId } ?: return@items
                        val f = remember(e) { e.toRemoteFile() }
                        val (p, m) = rememberMeta(f, settings, e.serverId)
                        ListItem(
                            leadingContent = { Poster(f, m, Modifier.size(48.dp, 72.dp), e.serverId) },
                            headlineContent = { Text(headline(f, p, m), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    subline(f, p)?.let { Text(it, maxLines = 1) }
                                    Text("/" + e.path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    val starred = favs.any { it.serverId == e.serverId && it.path.trim('/') == e.path }
                                    IconButton(onClick = { com.assassins377.remoteplayer.settings.FavoritesStore.toggle(com.assassins377.remoteplayer.settings.Favorite(e.serverId, e.path, e.name, false)) }) {
                                        Icon(if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                                            stringResource(if (starred) R.string.remove_favorite else R.string.add_favorite),
                                            tint = if (starred) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                    }
                                    AssistChip(onClick = { serverFilter = server.id }, label = { Text(server.name, maxLines = 1) })
                                }
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { if (query.isNotBlank()) RecentSearches.add(query); onPlay(ServerStore.repositoryFor(server.type).playbackUri(server, f)) },
                                onLongClick = { details = Triple(e.serverId, f, p to m) },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesItem(sr: SeriesRow, settings: com.assassins377.remoteplayer.settings.AppSettings, onClick: () -> Unit) {
    val p = remember(sr.seriesTitle) { com.assassins377.remoteplayer.meta.ParsedTitle(sr.seriesTitle, forceTv = true) }
    val meta by produceState(com.assassins377.remoteplayer.meta.TmdbRepository.cached(p), p.key, settings.tmdbEnabled) {
        if (value == null && settings.tmdbEnabled) { delay(300); value = com.assassins377.remoteplayer.meta.TmdbRepository.lookup(p) }
    }
    val f = com.assassins377.remoteplayer.data.RemoteFile(sr.seriesTitle, sr.samplePath, true)
    ListItem(
        leadingContent = { Poster(f, meta, Modifier.size(48.dp, 72.dp)) },
        headlineContent = { Text(meta?.title?.ifBlank { null } ?: sr.seriesTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(R.string.series_counts_fmt, sr.seasons, sr.episodes)) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
