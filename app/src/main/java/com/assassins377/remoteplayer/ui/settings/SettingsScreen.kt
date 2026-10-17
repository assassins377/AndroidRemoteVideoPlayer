package com.assassins377.remoteplayer.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assassins377.remoteplayer.R
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.data.SmbPool
import com.assassins377.remoteplayer.settings.*
import com.assassins377.remoteplayer.meta.TmdbRepository
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LANGS = listOf("", "ru", "en", "ja", "uk", "de", "fr")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val s by SettingsStore.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val set = SettingsStore::set
    val sec = stringResource(R.string.seconds_fmt)
    val kb = stringResource(R.string.kb_fmt)
    val any = stringResource(R.string.lang_any)
    val done = stringResource(R.string.done)
    val importedFmt = stringResource(R.string.imported_fmt)
    val importFailed = stringResource(R.string.import_failed)
    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(ServerStore.exportJson().toByteArray()) } } }
                .onSuccess { toast(done) }.onFailure { toast(it.message ?: "error") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
                ServerStore.importJson(text)
            }.onSuccess { toast(importedFmt.format(it)) }.onFailure { toast(importFailed) }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Section(R.string.sec_playback)
            Choice(R.string.default_speed, AppSettings.SPEEDS, s.defaultSpeed, { "${it}x" }) { set("defaultSpeed", it) }
            Choice(R.string.seek_step, AppSettings.SEEK_STEPS, s.seekStepSec, { sec.format(it) }) { set("seekStepSec", it) }
            Toggle(R.string.resume, s.resume) { set("resume", it) }
            Toggle(R.string.autoplay_next, s.autoplayNext) { set("autoplayNext", it) }
            Choice(R.string.audio_lang, LANGS, s.audioLanguage, { it.ifEmpty { any } }) { set("audioLanguage", it) }
            val dec = mapOf(DecoderMode.AUTO to R.string.decoder_auto, DecoderMode.HARDWARE to R.string.decoder_hw, DecoderMode.SOFTWARE to R.string.decoder_sw)
                .mapValues { stringResource(it.value) }
            Choice(R.string.decoder, DecoderMode.values().toList(), s.decoder, { dec.getValue(it) }) { set("decoder", it) }

            Section(R.string.sec_subtitles)
            Choice(R.string.sub_lang, LANGS, s.subtitleLanguage, { it.ifEmpty { any } }) { set("subtitleLanguage", it) }
            Toggle(R.string.sub_autoload, s.autoLoadSubtitles) { set("autoLoadSubtitles", it) }
            Choice(R.string.sub_size, AppSettings.SUB_SIZES, s.subtitleSizeSp, { "$it sp" }) { set("subtitleSizeSp", it) }
            val colors = listOf(0xFFFFFFFF to R.string.color_white, 0xFFFFEB3B to R.string.color_yellow, 0xFF76FF03 to R.string.color_green)
                .map { it.first to stringResource(it.second) }.toMap()
            Choice(R.string.sub_color, colors.keys.toList(), s.subtitleColor, { colors[it] ?: "#%08X".format(it) }) { set("subtitleColor", it) }
            val bgs = listOf(0x00000000L to R.string.bg_none, 0x80000000 to R.string.bg_half, 0xFF000000 to R.string.bg_black)
                .map { it.first to stringResource(it.second) }.toMap()
            Choice(R.string.sub_bg, bgs.keys.toList(), s.subtitleBackground, { bgs[it] ?: "#%08X".format(it) }) { set("subtitleBackground", it) }
            Text(stringResource(R.string.sub_offset_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            Section(R.string.sec_network)
            Choice(R.string.buffer, AppSettings.BUFFER_SEC, s.bufferMaxSec, { sec.format(it) }) { set("bufferMaxSec", it) }
            Choice(R.string.read_ahead, AppSettings.READ_AHEAD_KB, s.readAheadKb, { kb.format(it) }) { set("readAheadKb", it) }
            Choice(R.string.timeout, listOf(5, 10, 20, 30, 60), s.timeoutSec, { sec.format(it) }) { set("timeoutSec", it) }
            Choice(R.string.retries, listOf(1, 2, 3, 5), s.retryCount, { it.toString() }) { set("retryCount", it) }
            val smbAuto = stringResource(R.string.smb_auto)
            Choice(R.string.smb_dialect, SmbDialect.values().toList(), s.smbDialect, { if (it == SmbDialect.AUTO) smbAuto else it.name }) { set("smbDialect", it) }
            Toggle(R.string.smb_encryption, s.smbEncryption) { set("smbEncryption", it) }

            Section(R.string.sec_ui)
            val themes = mapOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark)
                .mapValues { stringResource(it.value) }
            Choice(R.string.theme, ThemeMode.values().toList(), s.theme, { themes.getValue(it) }) { set("theme", it) }
            Toggle(R.string.amoled, s.amoled) { set("amoled", it) }
            val sorts = mapOf(SortOrder.NAME to R.string.sort_name, SortOrder.DATE to R.string.sort_date, SortOrder.SIZE to R.string.sort_size)
                .mapValues { stringResource(it.value) }
            Choice(R.string.sort, SortOrder.values().toList(), s.sort, { sorts.getValue(it) }) { set("sort", it) }
            Toggle(R.string.show_hidden, s.showHidden) { set("showHidden", it) }
            Toggle(R.string.grid, s.grid) { set("grid", it) }
            val sysLang = stringResource(R.string.lang_system)
            Choice(R.string.language, AppLanguage.values().toList(), s.language, {
                when (it) { AppLanguage.SYSTEM -> sysLang; AppLanguage.RU -> "Русский"; AppLanguage.EN -> "English" }
            }) {
                set("language", it)
                Locales.apply(ctx, it)
                if (android.os.Build.VERSION.SDK_INT < 33) (ctx as? android.app.Activity)?.recreate()
            }

            Section(R.string.sec_controls)
            Toggle(R.string.swipe, s.swipeGestures) { set("swipeGestures", it) }
            Toggle(R.string.double_tap, s.doubleTapSeek) { set("doubleTapSeek", it) }
            Toggle(R.string.pip, s.pipOnMinimize) { set("pipOnMinimize", it) }
            Toggle(R.string.lock_rotation, s.lockRotation) { set("lockRotation", it) }
            Toggle(R.string.horizontal_seek, s.horizontalSeek) { set("horizontalSeek", it) }
            Toggle(R.string.background_audio, s.backgroundAudio) { set("backgroundAudio", it) }

            Section(R.string.sec_tmdb)
            Toggle(R.string.tmdb_enabled, s.tmdbEnabled) { set("tmdbEnabled", it); TmdbRepository.resetAuth() }
            var keyInput by remember { mutableStateOf("") }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (s.tmdbKeyEnc.isNotBlank()) R.string.tmdb_key_set else R.string.tmdb_key_none),
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(keyInput, { keyInput = it }, label = { Text(stringResource(R.string.tmdb_key)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = keyInput.isNotBlank(), onClick = {
                        set("tmdbKeyEnc", TmdbRepository.encryptKey(keyInput)); keyInput = ""
                        TmdbRepository.resetAuth(); toast(done)
                    }) { Text(stringResource(R.string.tmdb_save)) }
                    if (s.tmdbKeyEnc.isNotBlank()) OutlinedButton(onClick = { set("tmdbKeyEnc", "") }) { Text(stringResource(R.string.tmdb_remove)) }
                }
                Text(stringResource(R.string.tmdb_help), style = MaterialTheme.typography.bodySmall)
            }

            Section(R.string.sec_library)
            Toggle(R.string.library_refresh_on_start, s.libraryRefreshOnStart) { set("libraryRefreshOnStart", it) }
            Choice(R.string.library_depth, AppSettings.LIBRARY_DEPTHS, s.libraryMaxDepth, { it.toString() }) { set("libraryMaxDepth", it) }
            var rootsInput by remember(s.libraryRoots) { mutableStateOf(s.libraryRoots) }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(rootsInput, { rootsInput = it }, label = { Text(stringResource(R.string.library_roots)) },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.library_roots_help), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = rootsInput != s.libraryRoots, onClick = { set("libraryRoots", rootsInput); toast(done) }) { Text(stringResource(R.string.save)) }
                    OutlinedButton(onClick = { com.assassins377.remoteplayer.library.LibraryStore.refresh(); toast(done) }) { Text(stringResource(R.string.library_refresh)) }
                }
            }
            Action(R.string.library_clear) { scope.launch { com.assassins377.remoteplayer.library.LibraryStore.clear() }; toast(done) }

            Section(R.string.sec_data)
            Action(R.string.clear_cache) {
                scope.launch(Dispatchers.IO) { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }; SmbPool.invalidateAll(); TmdbRepository.clearCache() }
                toast(done)
            }
            Action(R.string.clear_history) { ResumeStore.clear(); toast(done) }
            Action(R.string.export_servers) { exportLauncher.launch("remoteplayer-servers.json") }
            Action(R.string.import_servers) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun Toggle(title: Int, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        trailingContent = { Switch(checked = value, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!value) },
    )
}

@Composable
private fun Action(title: Int, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(stringResource(title)) }, modifier = Modifier.clickable(onClick = onClick))
}

@Composable
private fun <T> Choice(title: Int, options: List<T>, value: T, label: (T) -> String, onSelect: (T & Any) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(label(value)) },
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(label(o)) }, onClick = { open = false; if (o != null) onSelect(o) })
            }
        }
    }
}
