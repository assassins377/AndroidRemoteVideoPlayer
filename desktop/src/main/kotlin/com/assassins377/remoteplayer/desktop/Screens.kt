package com.assassins377.remoteplayer.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assassins377.remoteplayer.data.*
import com.assassins377.remoteplayer.library.*
import com.assassins377.remoteplayer.meta.TitleParser
import com.assassins377.remoteplayer.meta.TmdbMeta
import com.assassins377.remoteplayer.settings.FileSorter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, Strings["back"]) } },
        actions = actions)
}

@Composable
fun ServersScreen(nav: Nav) {
    val servers by Env.servers.servers.collectAsState()
    var editing by remember { mutableStateOf<ServerConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column {
        Bar(Strings["servers"], null) {
            TextButton(onClick = { nav.go(Screen.Library) }) { Icon(Icons.Default.VideoLibrary, null); Spacer(Modifier.width(6.dp)); Text(Strings["library"]) }
            IconButton(onClick = { nav.go(Screen.Settings) }) { Icon(Icons.Default.Settings, Strings["settings"]) }
        }
        ContinueRow(nav)
        if (servers.isEmpty()) Text(Strings["empty_servers"], Modifier.padding(24.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(servers.sortedBy { it.name.lowercase() }, key = { it.id }) { s ->
                ListItem(
                    leadingContent = { Icon(if (s.type == ServerType.SMB) Icons.Default.Dns else Icons.Default.Cloud, null) },
                    headlineContent = { Text(s.name) },
                    supportingContent = { Text("${s.type} · ${s.host}${if (s.share.isNotEmpty()) "/" + s.share else ""}") },
                    trailingContent = { IconButton(onClick = { editing = s }) { Icon(Icons.Default.Edit, Strings["edit_server"]) } },
                    modifier = Modifier.clickable { nav.go(Screen.Browser(s)) },
                )
            }
        }
        Row(Modifier.padding(16.dp)) { Button(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(Strings["add_server"]) } }
    }
    if (adding || editing != null) ServerDialog(editing, onDismiss = { adding = false; editing = null })
}

/** Recently watched (resume positions) as quick links. */
@Composable
private fun ContinueRow(nav: Nav) {
    val items = remember { Env.resume.recent(5) }
    val resolved = items.mapNotNull { (key, e) ->
        val ref = PlaybackRef.parse(key) ?: return@mapNotNull null
        val srv = Env.servers.get(ref.serverId) ?: return@mapNotNull null
        Triple(srv, RemoteFile(ref.path.substringAfterLast('/'), ref.path, false), e)
    }
    if (resolved.isEmpty()) return
    Text(Strings["continue"], style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        resolved.forEach { (srv, f, e) ->
            AssistChip(onClick = { nav.go(Screen.Player(srv, f)) },
                label = { Text(TitleParser.parse(f.name).display + (e.progress?.let { " · ${(it * 100).toInt()}%" } ?: ""), maxLines = 1) })
        }
    }
}

@Composable
fun ServerDialog(existing: ServerConfig?, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(existing?.type ?: ServerType.SMB) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var share by remember { mutableStateOf(existing?.share ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var domain by remember { mutableStateOf(existing?.domain ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun build() = ServerConfig(existing?.id ?: UUID.randomUUID().toString(), name.ifBlank { host }, type, host.trim(), share.trim(), user.trim(), pass, domain.trim(), existing?.pinnedCertSha256 ?: "")
    AlertDialog(onDismissRequest = onDismiss, title = { Text(Strings["edit_server"]) },
        text = {
            Column(Modifier.width(460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ServerType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.name) }) }
                }
                OutlinedTextField(name, { name = it }, label = { Text(Strings["name"]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text(Strings[if (type == ServerType.SMB) "host" else "url"]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type == ServerType.SMB) OutlinedTextField(share, { share = it }, label = { Text(Strings["share"]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text(Strings["user"]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text(Strings["password"]) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (type == ServerType.SMB) OutlinedTextField(domain, { domain = it }, label = { Text(Strings["domain"]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Text(Strings.fmt("secrets_backend", Strings.fmt(Env.secrets.backend, Env.dirs.config.path)), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = host.isNotBlank(), onClick = { Env.servers.save(build()); onDismiss() }) { Text(Strings["save"]) } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    status = "…"
                    scope.launch {
                        val s = build()
                        status = runCatching { SmbPool.invalidate(s.id); HttpClients.invalidateAll(); JellyfinRepository.invalidate(s.id); Repositories.forType(s.type).list(s, ""); Strings["test_ok"] }
                            .getOrElse { (it as? SmbError)?.message ?: it.message ?: Strings["error"] }
                    }
                }) { Text(Strings["test"]) }
                if (existing != null) TextButton(onClick = { Env.servers.remove(existing.id); onDismiss() }) { Text(Strings["delete"]) }
                TextButton(onClick = onDismiss) { Text(Strings["cancel"]) }
            }
        })
}

@Composable
fun BrowserScreen(nav: Nav, sc: Screen.Browser) {
    val s by Env.settings.settings.collectAsState()
    var files by remember(sc) { mutableStateOf<List<RemoteFile>?>(null) }
    var error by remember(sc) { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(sc, reload, s.sort, s.showHidden) {
        error = null
        runCatching { Repositories.forType(sc.server.type).list(sc.server, sc.path) }
            .onSuccess { files = FileSorter.sort(it, s.sort, s.showHidden) }
            .onFailure { error = it.message ?: it.javaClass.simpleName; files = emptyList() }
    }
    Column {
        Bar(sc.server.name + (if (sc.path.isNotEmpty()) " / " + sc.path else ""), nav::back) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, Strings["refresh"]) }
        }
        when {
            files == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            error != null -> Column(Modifier.padding(24.dp)) { Text(error!!, color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }) { Text(Strings["refresh"]) } }
            files!!.isEmpty() -> Text(Strings["empty_folder"], Modifier.padding(24.dp))
            else -> LazyColumn {
                items(files!!, key = { it.path }) { f ->
                    FileItem(f) {
                        if (f.isDirectory) nav.go(Screen.Browser(sc.server, f.path.trim('/'))) else if (f.isVideo) nav.go(Screen.Player(sc.server, f))
                    }
                }
            }
        }
    }
}

@Composable
fun FileItem(f: RemoteFile, subtitle: String? = null, onClick: () -> Unit) {
    val p = remember(f.name) { if (f.isVideo) TitleParser.parse(f.name) else null }
    ListItem(
        leadingContent = { Poster(f, p) },
        headlineContent = { Text(p?.display ?: f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle ?: if (f.isDirectory) "" else TitleParser.formatSize(f.size), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun Poster(f: RemoteFile, p: com.assassins377.remoteplayer.meta.ParsedTitle?) {
    val s by Env.settings.settings.collectAsState()
    val meta by produceState<TmdbMeta?>(p?.let { Tmdb.cached(it) }, p?.key, s.tmdbEnabled) {
        if (value == null && p != null && s.tmdbEnabled) { delay(300); value = Tmdb.lookup(p) }
    }
    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, meta?.posterPath) {
        value = meta?.posterUrl("w154")?.let { Tmdb.poster(it) }
    }
    Box(Modifier.size(40.dp, 60.dp), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.small))
        else Icon(if (f.isDirectory) Icons.Default.Folder else Icons.Default.Movie, null, Modifier.fillMaxSize(0.7f))
    }
}

@Composable
fun LibraryScreen(nav: Nav) {
    val servers by Env.servers.servers.collectAsState()
    val st by LibraryStore.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(input) { delay(250); query = input }
    var mode by remember { mutableStateOf(LibraryMode.ALL) }
    var sort by remember { mutableStateOf(LibrarySort.TITLE) }
    val live = servers.map { it.id }.ifEmpty { listOf("") }
    val dao = Env.library.dao()
    val rows by remember(query, live, mode, sort) { dao.observe(LibrarySql.list(query, null, sort, live, 500, mode = mode)) }.collectAsState(emptyList())
    val total by remember(query, live, mode) { dao.observeCount(LibrarySql.count(query, null, live, mode)) }.collectAsState(0)
    Column {
        Bar(Strings["library"], nav::back) {
            if (st.scanning) TextButton(onClick = { LibraryStore.cancel() }) { Text(Strings["cancel_scan"]) }
            else IconButton(onClick = { LibraryStore.refresh() }) { Icon(Icons.Default.Refresh, Strings["refresh"]) }
        }
        if (st.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(Strings.fmt("scanning", st.serversDone, st.progress.size, st.filesFound), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
        st.errors.forEach { (id, e) -> Text("${Env.servers.get(id)?.name ?: id}: $e", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(input, { input = it }, placeholder = { Text(Strings["search"]) }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(LibraryMode.ALL to "all", LibraryMode.MOVIES to "movies", LibraryMode.SERIES to "series").forEach { (m, k) ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(Strings[k]) })
            }
            Spacer(Modifier.width(12.dp))
            listOf(LibrarySort.TITLE to "sort_title", LibrarySort.YEAR to "sort_year", LibrarySort.ADDED to "sort_added").forEach { (o, k) ->
                FilterChip(selected = sort == o, onClick = { sort = o }, label = { Text(Strings[k]) })
            }
            Spacer(Modifier.weight(1f)); Text(Strings.fmt("found", total))
        }
        LazyColumn {
            items(rows, key = { it.id }) { e ->
                val srv = servers.firstOrNull { it.id == e.serverId } ?: return@items
                FileItem(e.toRemoteFile(), "${srv.name} · /${e.path}") { nav.go(Screen.Player(srv, e.toRemoteFile())) }
            }
        }
    }
}
