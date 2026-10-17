package com.assassins377.remoteplayer.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.library.LibraryStore
import com.assassins377.remoteplayer.settings.ThemeMode

/** Screens (simple back stack). */
sealed interface Screen {
    data object Servers : Screen
    data class Browser(val server: ServerConfig, val path: String = "") : Screen
    data object Library : Screen
    data object Settings : Screen
    data class Player(val server: ServerConfig, val file: RemoteFile, val playlist: List<RemoteFile> = emptyList()) : Screen
    data class Series(val server: ServerConfig, val path: String) : Screen
}

class Nav(start: Screen) {
    val stack = mutableStateListOf(start)
    val current get() = stack.last()
    fun go(s: Screen) { stack += s }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }
}

/** Shared player key events from the window (space/arrows/F/Esc), routed to the player screen. */
object Keys { var handler: ((KeyEvent) -> Boolean)? = null }

fun main(args: Array<String>) {
    Env.init()
    Strings.apply(Env.settings.current.language)
    if (Env.settings.current.libraryRefreshOnStart) LibraryStore.refresh()
    val smoke = args.contains("--smoke") // CI/xvfb: render then exit
    application {
        val state = rememberWindowState(width = 1100.dp, height = 720.dp)
        val icon = remember { runCatching { BitmapPainter(Main::class.java.getResourceAsStream("/icon.png")!!.use(::loadImageBitmap)) }.getOrNull() }
        Window(onCloseRequest = ::exitApplication, state = state, title = "Remote Video Player", icon = icon,
            onPreviewKeyEvent = { e -> Keys.handler?.invoke(e) ?: false }) {
            App(state)
            if (smoke) LaunchedEffect(Unit) { kotlinx.coroutines.delay((System.getenv("REMOTEPLAYER_SMOKE_DELAY_MS") ?: "4000").toLong()); Smoke.capture(window); exitApplication() }
        }
    }
}

private object Main

@Composable
fun App(window: WindowState? = null, nav: Nav = remember { Nav(Screen.Servers).also(Smoke::open) }) {
    val s by Env.settings.settings.collectAsState()
    LaunchedEffect(s.language) { Strings.apply(s.language) }
    val dark = when (s.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; else -> isSystemInDarkTheme() }
    key(s.language) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface {
                when (val sc = nav.current) {
                    Screen.Servers -> ServersScreen(nav)
                    is Screen.Browser -> BrowserScreen(nav, sc)
                    Screen.Library -> LibraryScreen(nav)
                    Screen.Settings -> SettingsScreen(nav)
                    is Screen.Player -> PlayerScreen(nav, sc, window)
                    is Screen.Series -> SeriesScreen(nav, sc)
                }
            }
        }
    }
}
