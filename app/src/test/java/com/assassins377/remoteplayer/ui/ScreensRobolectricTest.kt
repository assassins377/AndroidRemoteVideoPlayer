package com.assassins377.remoteplayer.ui

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.assassins377.remoteplayer.data.*
import com.assassins377.remoteplayer.settings.SettingsStore
import com.assassins377.remoteplayer.ui.browser.FileBrowserScreen
import com.assassins377.remoteplayer.ui.servers.ServerEditScreen
import com.assassins377.remoteplayer.ui.servers.ServerListScreen
import com.assassins377.remoteplayer.ui.settings.SettingsScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plain Application: avoids Keystore-backed init of the real app class (not available in Robolectric). */
class TestApp : Application()

private object PlainSecrets : SecretStore {
    override fun encrypt(plain: String) = plain
    override fun decrypt(cipherText: String) = cipherText
}

/** Fake repository: fixed listing per path, records requested paths. */
class FakeRepo : RemoteFileRepository {
    val requested = mutableListOf<String>()
    override suspend fun list(server: ServerConfig, path: String): List<RemoteFile> {
        requested += path
        return when (path) {
            "" -> listOf(
                RemoteFile("Movies", "Movies", true),
                RemoteFile("The.Matrix.1999.1080p.BluRay.mkv", "The.Matrix.1999.1080p.BluRay.mkv", false, size = 4_500_000_000),
                RemoteFile("Брат.1997.720p.mkv", "Брат.1997.720p.mkv", false, size = 1_500_000_000),
                RemoteFile(".hidden.mkv", ".hidden.mkv", false, hidden = true),
            )
            "Movies" -> listOf(RemoteFile("Inception.2010.mkv", "Movies/Inception.2010.mkv", false, size = 10))
            else -> throw SmbError(SmbErrorKind.NOT_FOUND, null)
        }
    }
    override fun playbackUri(server: ServerConfig, file: RemoteFile) = "fake://${file.path}"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "ru")
class ScreensRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val fake = FakeRepo()

    companion object { private var inited = false }

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        com.assassins377.remoteplayer.AppStrings.context = app
        if (!inited) { SettingsStore.init(app); ServerStore.init(app, PlainSecrets); inited = true }
        runBlocking { ServerStore.servers.value.forEach { ServerStore.remove(it.id) } }
        ServerStore.repositoryOverride = { fake }
    }

    private fun addServer(name: String = "Мой NAS", id: String = "s1") = runBlocking {
        ServerStore.upsert(ServerConfig(id, name, ServerType.SMB, "192.168.1.5", "media", "user", "pw"))
    }

    @Test fun serverListEmptyStateAndClicks() {
        var added = false; var settings = false
        compose.setContent { ServerListScreen(onAdd = { added = true }, onOpen = {}, onEdit = {}, onSettings = { settings = true }) }
        compose.onNodeWithText("Нет серверов", substring = true).assertExists()
        compose.onNodeWithContentDescription("Добавить").performClick()
        compose.onNodeWithContentDescription("Настройки").performClick()
        assertTrue(added && settings)
    }

    @Test fun serverListShowsServersAndOpens() {
        addServer()
        var opened: ServerConfig? = null
        compose.setContent { ServerListScreen(onAdd = {}, onOpen = { opened = it }, onEdit = {}) }
        compose.onNodeWithText("Мой NAS").assertExists().performClick()
        compose.onNodeWithText("SMB · 192.168.1.5/media").assertExists()
        assertEquals("s1", opened?.id)
    }

    @Test fun editServerValidationAndSave() {
        var done = false
        compose.setContent { ServerEditScreen(serverId = null, onDone = { done = true }) }
        compose.onNodeWithText("Новый сервер").assertExists()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithText("Хост (192.168.1.10)").performTextInput("10.0.0.2")
        compose.onNodeWithText("Сохранить").assertIsNotEnabled() // SMB needs a share
        compose.onNodeWithText("Общая папка (share)").performTextInput("video")
        compose.onNodeWithText("Сохранить").assertIsEnabled().performScrollTo().performClick()
        compose.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20); done }
        val saved = ServerStore.servers.value.single()
        assertEquals("10.0.0.2", saved.host); assertEquals("video", saved.share); assertTrue(saved.id.isNotBlank())
    }

    @Test fun browserShowsCleanTitlesNavigatesAndSearches() {
        addServer()
        var played: String? = null
        compose.setContent { FileBrowserScreen(serverId = "s1", onPlay = { played = it }, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("The Matrix (1999)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1080p · 4,2 ГБ").assertExists()
        compose.onNodeWithText("Брат (1997)").assertExists()
        compose.onAllNodesWithText(".hidden.mkv").assertCountEquals(0) // hidden files off by default
        // search
        compose.onNodeWithContentDescription("Поиск").performClick()
        compose.onNodeWithText("Поиск в папке").performTextInput("брат")
        compose.onAllNodesWithText("The Matrix (1999)").assertCountEquals(0)
        compose.onNodeWithText("Брат (1997)").performClick()
        assertEquals("fake://Брат.1997.720p.mkv", played)
        // clear search, open folder
        compose.onNodeWithContentDescription("Поиск").performClick()
        compose.onNodeWithText("Movies").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Inception (2010)").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("", "Movies"), fake.requested)
    }

    @Test fun browserErrorShowsRussianMessageAndRetry() {
        addServer()
        compose.setContent { FileBrowserScreen(serverId = "s1", startPath = "missing", onPlay = {}, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Папка, файл или общий ресурс не найдены", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Повторить").performClick()
        compose.waitUntil(5_000) { fake.requested.size >= 2 }
    }

    @Test fun settingsTogglePersists() {
        compose.setContent { SettingsScreen(onBack = {}) }
        compose.onNodeWithText("Воспроизведение").assertExists()
        val before = SettingsStore.current.resume
        compose.onNodeWithText("Продолжать с места остановки").performClick()
        compose.waitUntil(5_000) { SettingsStore.current.resume != before }
        compose.onNodeWithText("Продолжать с места остановки").performClick()
        compose.waitUntil(5_000) { SettingsStore.current.resume == before }
    }
}
