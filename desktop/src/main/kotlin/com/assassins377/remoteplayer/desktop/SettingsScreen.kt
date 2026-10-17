package com.assassins377.remoteplayer.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.assassins377.remoteplayer.library.LibraryStore
import com.assassins377.remoteplayer.settings.AppLanguage
import com.assassins377.remoteplayer.settings.AppSettings
import com.assassins377.remoteplayer.settings.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: Nav) {
    val s by Env.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    fun set(f: (AppSettings) -> AppSettings) = Env.settings.update(f)
    Column {
        Bar(Strings["settings"], nav::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(Strings["language"], style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(AppLanguage.SYSTEM to Strings["lang_system"], AppLanguage.RU to "Русский", AppLanguage.EN to "English").forEach { (l, t) ->
                    FilterChip(selected = s.language == l, onClick = { set { it.copy(language = l) } }, label = { Text(t) })
                }
            }
            Text(Strings["theme"], style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ThemeMode.SYSTEM to "theme_system", ThemeMode.LIGHT to "theme_light", ThemeMode.DARK to "theme_dark").forEach { (m, k) ->
                    FilterChip(selected = s.theme == m, onClick = { set { it.copy(theme = m) } }, label = { Text(Strings[k]) })
                }
            }
            Toggle(Strings["resume"], s.resume) { v -> set { it.copy(resume = v) } }
            Toggle(Strings["auto_subs"], s.autoLoadSubtitles) { v -> set { it.copy(autoLoadSubtitles = v) } }
            Toggle(Strings["show_hidden"], s.showHidden) { v -> set { it.copy(showHidden = v) } }
            HorizontalDivider()
            Toggle(Strings["tmdb"], s.tmdbEnabled) { v -> set { it.copy(tmdbEnabled = v) } }
            var key by remember { mutableStateOf(if (Tmdb.hasKey()) "••••••••" else "") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it }, label = { Text(Strings["tmdb_key"]) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                Button(enabled = key.isNotBlank() && !key.startsWith("•"), onClick = { Tmdb.setKey(key); key = "••••••••" }) { Text(Strings["save"]) }
            }
            HorizontalDivider()
            var roots by remember { mutableStateOf(s.libraryRoots) }
            OutlinedTextField(roots, { roots = it }, label = { Text(Strings["roots"]) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = roots != s.libraryRoots, onClick = { set { it.copy(libraryRoots = roots) } }) { Text(Strings["save"]) }
                Text(Strings["depth"])
                AppSettings.LIBRARY_DEPTHS.forEach { d -> FilterChip(selected = s.libraryMaxDepth == d, onClick = { set { it.copy(libraryMaxDepth = d) } }, label = { Text("$d") }) }
            }
            Toggle(Strings["refresh_on_start"], s.libraryRefreshOnStart) { v -> set { it.copy(libraryRefreshOnStart = v) } }
            OutlinedButton(onClick = { scope.launch { LibraryStore.clear() } }) { Text(Strings["clear_library"]) }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Strings["timeout"]); listOf(10, 20, 40, 60).forEach { t -> FilterChip(selected = s.timeoutSec == t, onClick = { set { it.copy(timeoutSec = t) } }, label = { Text("$t") }) }
                Spacer(Modifier.width(16.dp))
                Text(Strings["retries"]); listOf(1, 2, 3, 5).forEach { r -> FilterChip(selected = s.retryCount == r, onClick = { set { it.copy(retryCount = r) } }, label = { Text("$r") }) }
            }
            Text(Strings.fmt("secrets_backend", Strings.fmt(Env.secrets.backend, Env.dirs.config.path)), style = MaterialTheme.typography.bodySmall)
            Text(Strings["about"] + " · " + (System.getProperty("jpackage.app-version") ?: "dev"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Toggle(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Switch(value, onChange); Spacer(Modifier.width(12.dp)); Text(text) }
}
