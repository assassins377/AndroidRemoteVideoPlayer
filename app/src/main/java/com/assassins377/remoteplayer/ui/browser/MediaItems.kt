package com.assassins377.remoteplayer.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
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
import com.assassins377.remoteplayer.R
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.meta.ParsedTitle
import com.assassins377.remoteplayer.meta.TitleParser
import com.assassins377.remoteplayer.meta.TmdbMeta
import com.assassins377.remoteplayer.meta.TmdbRepository
import com.assassins377.remoteplayer.settings.AppSettings
import kotlinx.coroutines.delay

/** Parsed title + TMDB meta for a file. Lookup is debounced (skipped while fast-scrolling) and cached. */
@Composable
fun rememberMeta(f: RemoteFile, s: AppSettings): Pair<ParsedTitle?, TmdbMeta?> {
    if (!f.isVideo) return null to null
    val parsed = remember(f.name) { TitleParser.parse(f.name) }
    val meta by produceState(TmdbRepository.cached(parsed), parsed.key, s.tmdbEnabled, s.tmdbKeyEnc, s.language) {
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
fun FileRow(f: RemoteFile, s: AppSettings, onClick: () -> Unit, onDetails: (RemoteFile, ParsedTitle?, TmdbMeta?) -> Unit) {
    val (p, m) = rememberMeta(f, s)
    ListItem(
        leadingContent = { Poster(f, m, Modifier.width(40.dp).height(60.dp)) },
        headlineContent = { Text(headline(f, p, m), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = subline(f, p)?.let { { Text(it) } },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { if (!f.isDirectory) onDetails(f, p, m) }),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(f: RemoteFile, s: AppSettings, onClick: () -> Unit, onDetails: (RemoteFile, ParsedTitle?, TmdbMeta?) -> Unit) {
    val (p, m) = rememberMeta(f, s)
    Card(Modifier.padding(4.dp).combinedClickable(onClick = onClick, onLongClick = { if (!f.isDirectory) onDetails(f, p, m) })) {
        Column(Modifier.padding(6.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Poster(f, m, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Text(headline(f, p, m), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            subline(f, p)?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(f: RemoteFile, p: ParsedTitle?, m: TmdbMeta?, onPlay: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Poster(f, m?.let { it.copy() }, Modifier.width(110.dp).height(165.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(headline(f, p, m), style = MaterialTheme.typography.titleLarge)
                    m?.originalTitle?.takeIf { it.isNotBlank() && it != m.title }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    subline(f, p)?.let { Text(it) }
                    if (m != null && m.rating > 0) Text("TMDB ★ %.1f".format(m.rating))
                }
            }
            m?.overview?.takeIf { it.isNotBlank() }?.let { Text(it) }
            Text(stringResource(R.string.file_name) + ": " + f.name, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) { Text("▶") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
