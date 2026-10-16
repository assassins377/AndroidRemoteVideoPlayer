package com.assassins377.remoteplayer.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.assassins377.remoteplayer.meta.OverrideStore
import com.assassins377.remoteplayer.meta.TmdbOverride
import com.assassins377.remoteplayer.R
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.meta.ParsedTitle
import com.assassins377.remoteplayer.meta.TitleParser
import com.assassins377.remoteplayer.meta.TmdbMeta
import com.assassins377.remoteplayer.meta.TmdbRepository
import com.assassins377.remoteplayer.settings.AppSettings
import kotlinx.coroutines.delay

/**
 * Parsed title + TMDB meta for a file (or a series folder). Manual override wins; otherwise lookup is
 * debounced (skipped while fast-scrolling) and cached.
 */
@Composable
fun rememberMeta(f: RemoteFile, s: AppSettings, serverId: String): Pair<ParsedTitle?, TmdbMeta?> {
    val parsed = remember(f.name, f.isDirectory) {
        when { f.isDirectory -> TitleParser.parseFolder(f.name); f.isVideo -> TitleParser.parse(f.name); else -> null }
    } ?: return null to null
    val version by TmdbRepository.overridesVersion.collectAsState()
    val ov = remember(version, f.path) { TmdbRepository.override(OverrideStore.key(serverId, f.path)) }
    if (ov != null) return parsed to ov.meta
    val meta by produceState(TmdbRepository.cached(parsed), parsed.key, s.tmdbEnabled, s.tmdbKeyEnc, s.language, version) {
        if (value == null && s.tmdbEnabled && s.tmdbKeyEnc.isNotBlank()) {
            delay(350) // item must stay on screen; cancelled when scrolled away
            value = TmdbRepository.lookup(parsed)
        }
    }
    return parsed to meta
}

fun headline(f: RemoteFile, p: ParsedTitle?, m: TmdbMeta?): String = when {
    p == null -> f.name
    m != null -> buildString {
        append(m.title.ifBlank { p.title })
        (m.year ?: p.year)?.let { append(" ($it)") }
        p.episodeTag?.let { append(" ").append(it) }
    }
    else -> p.display
}

fun subline(f: RemoteFile, p: ParsedTitle?): String? = when {
    f.isDirectory -> null
    p != null -> TitleParser.subtitleLine(p, f.size)
    else -> TitleParser.formatSize(f.size)
}

@Composable
fun Poster(f: RemoteFile, m: TmdbMeta?, modifier: Modifier) {
    val fallback = @Composable {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(if (f.isDirectory) Icons.Default.Folder else Icons.Default.Movie, null, Modifier.fillMaxSize(0.6f))
        }
    }
    val url = m?.posterUrl()
    if (url == null) fallback() else SubcomposeAsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.clip(MaterialTheme.shapes.small),
        loading = { fallback() }, error = { fallback() },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    f: RemoteFile, s: AppSettings, serverId: String, onClick: () -> Unit, onDetails: (RemoteFile, ParsedTitle?, TmdbMeta?) -> Unit,
    starred: Boolean = false, onStar: (() -> Unit)? = null,
) {
    val (p, m) = rememberMeta(f, s, serverId)
    ListItem(
        leadingContent = { Poster(f, m, Modifier.width(40.dp).height(60.dp)) },
        headlineContent = { Text(headline(f, p, m), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = subline(f, p)?.let { { Text(it) } },
        trailingContent = onStar?.let { star -> {
            IconButton(onClick = star) {
                Icon(if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                    stringResource(if (starred) R.string.remove_favorite else R.string.add_favorite),
                    tint = if (starred) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
        } },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { if (p != null) onDetails(f, p, m) }),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(f: RemoteFile, s: AppSettings, serverId: String, onClick: () -> Unit, onDetails: (RemoteFile, ParsedTitle?, TmdbMeta?) -> Unit) {
    val (p, m) = rememberMeta(f, s, serverId)
    Card(Modifier.padding(4.dp).combinedClickable(onClick = onClick, onLongClick = { if (p != null) onDetails(f, p, m) })) {
        Column(Modifier.padding(6.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Poster(f, m, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Text(headline(f, p, m), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            subline(f, p)?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(serverId: String, f: RemoteFile, p: ParsedTitle?, m: TmdbMeta?, onPlay: () -> Unit, onDismiss: () -> Unit) {
    val key = OverrideStore.key(serverId, f.path)
    val version by TmdbRepository.overridesVersion.collectAsState()
    val ov = remember(version) { TmdbRepository.override(key) }
    val meta = if (ov != null) ov.meta else m
    var searching by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Poster(f, meta, Modifier.width(110.dp).height(165.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(headline(f, p, meta), style = MaterialTheme.typography.titleLarge)
                    meta?.originalTitle?.takeIf { it.isNotBlank() && it != meta.title }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    subline(f, p)?.let { Text(it) }
                    if (meta != null && meta.rating > 0) Text("TMDB ★ %.1f".format(meta.rating))
                    if (ov != null) Text(stringResource(if (ov.hidden) R.string.tmdb_hidden_badge else R.string.tmdb_manual_badge),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            meta?.overview?.takeIf { it.isNotBlank() }?.let { Text(it) }
            Text(stringResource(R.string.file_name) + ": " + f.name, style = MaterialTheme.typography.bodySmall)
            if (!f.isDirectory) Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) { Text("▶") }
            if (p != null) {
                OutlinedButton(onClick = { searching = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tmdb_wrong_match)) }
                if (ov != null) TextButton(onClick = { TmdbRepository.setOverride(key, null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tmdb_reset_auto))
                }
                if (ov?.hidden != true) TextButton(onClick = { TmdbRepository.setOverride(key, TmdbOverride(null, p.isSeries)) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tmdb_hide))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (searching && p != null) MatchDialog(p, onPick = { picked, tv ->
        TmdbRepository.setOverride(key, TmdbOverride(picked, tv)); searching = false
    }, onDismiss = { searching = false })
}

/** Manual TMDB search: editable query (prefilled), optional year, movie/tv toggle, results with posters. */
@Composable
fun MatchDialog(p: ParsedTitle, onPick: (TmdbMeta, Boolean) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(p.title) }
    var year by remember { mutableStateOf(if (p.isSeries) "" else p.year?.toString().orEmpty()) }
    var tv by remember { mutableStateOf(p.isSeries) }
    var results by remember { mutableStateOf<List<TmdbMeta>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var trigger by remember { mutableIntStateOf(1) }
    LaunchedEffect(trigger) {
        if (query.isBlank()) return@LaunchedEffect
        loading = true; error = null
        runCatching { TmdbRepository.search(query.trim(), year.toIntOrNull(), tv) }
            .onSuccess { results = it }.onFailure { error = it.message ?: it.toString() }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tmdb_wrong_match)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.tmdb_query)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, singleLine = true,
                        label = { Text(stringResource(R.string.tmdb_year)) }, modifier = Modifier.width(96.dp))
                    FilterChip(!tv, { tv = false; trigger++ }, label = { Text(stringResource(R.string.tmdb_movie)) })
                    FilterChip(tv, { tv = true; trigger++ }, label = { Text(stringResource(R.string.tmdb_tv)) })
                }
                Button(onClick = { trigger++ }, enabled = query.isNotBlank() && !loading) { Text(stringResource(R.string.tmdb_search)) }
                when {
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    results?.isEmpty() == true -> Text(stringResource(R.string.tmdb_no_results))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results.orEmpty(), key = { it.id }) { r ->
                        ListItem(
                            leadingContent = {
                                val url = r.posterUrl("w92")
                                if (url != null) AsyncImage(url, null, Modifier.width(40.dp).height(60.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
                                else Icon(Icons.Default.Movie, null, Modifier.width(40.dp).height(60.dp))
                            },
                            headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(listOfNotNull(r.year?.toString(), r.originalTitle.takeIf { it.isNotBlank() && it != r.title }).joinToString(" · ")) },
                            modifier = Modifier.clickable { onPick(r, tv) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } },
    )
}
