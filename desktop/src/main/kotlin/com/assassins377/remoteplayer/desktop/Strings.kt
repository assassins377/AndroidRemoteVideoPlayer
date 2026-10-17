package com.assassins377.remoteplayer.desktop

import com.assassins377.remoteplayer.settings.AppLanguage
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** UI strings (RU/EN) + Android string resources (for shared-code messages: errors, size units). */
object Strings {
    @Volatile var lang: String = "ru"

    fun apply(l: AppLanguage) { lang = when (l) { AppLanguage.RU -> "ru"; AppLanguage.EN -> "en"; else -> if (Locale.getDefault().language == "ru") "ru" else "en" } }

    private val androidCache = HashMap<String, Map<String, String>>()
    private fun androidMap(l: String): Map<String, String> = synchronized(androidCache) {
        androidCache.getOrPut(l) {
            runCatching {
                Strings::class.java.getResourceAsStream("/android/strings-$l.xml")!!.use { s ->
                    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(s)
                    val n = doc.getElementsByTagName("string")
                    (0 until n.length).associate { i ->
                        val e = n.item(i) as org.w3c.dom.Element
                        e.getAttribute("name") to e.textContent.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
                    }
                }
            }.getOrDefault(emptyMap())
        }
    }

    fun android(key: String): String? = androidMap(lang)[key]

    operator fun get(key: String): String = (if (lang == "ru") RU[key] else EN[key]) ?: android(key) ?: key
    fun fmt(key: String, vararg a: Any) = get(key).format(*a)

    private val RU = mapOf(
        "servers" to "Серверы", "add_server" to "Добавить сервер", "edit_server" to "Сервер", "library" to "Библиотека",
        "settings" to "Настройки", "name" to "Название", "type" to "Тип", "host" to "Хост (или хост:порт)", "url" to "Адрес (URL)",
        "share" to "Общий ресурс", "user" to "Логин", "password" to "Пароль", "domain" to "Домен", "save" to "Сохранить",
        "cancel" to "Отмена", "delete" to "Удалить", "test" to "Проверить", "test_ok" to "Подключение работает", "back" to "Назад",
        "empty_servers" to "Серверов пока нет. Добавьте SMB, WebDAV, HTTP или Jellyfin.", "empty_folder" to "Папка пуста",
        "search" to "Поиск", "refresh" to "Обновить", "cancel_scan" to "Остановить", "found" to "Найдено: %d",
        "scanning" to "Индексация: %d из %d серверов, %d видео", "all" to "Все", "movies" to "Фильмы", "series" to "Сериалы",
        "sort_title" to "По названию", "sort_year" to "По году", "sort_added" to "По дате", "language" to "Язык",
        "lang_system" to "Как в системе", "theme" to "Тема", "theme_system" to "Системная", "theme_light" to "Светлая",
        "theme_dark" to "Тёмная", "resume" to "Продолжать с места остановки", "auto_subs" to "Подключать субтитры из папки",
        "show_hidden" to "Показывать скрытые файлы", "tmdb" to "Постеры и описания TMDB", "tmdb_key" to "Ключ TMDB (v3 или v4)",
        "roots" to "Папки библиотеки (по одной в строке, пусто = весь ресурс)", "depth" to "Глубина сканирования",
        "timeout" to "Таймаут сети, с", "retries" to "Повторы", "secrets_backend" to "Пароли хранятся: %s",
        "secret-service" to "в системном хранилище (Secret Service)", "encrypted-file" to "в зашифрованном файле (%s)",
        "audio" to "Звук", "subtitles" to "Субтитры", "off" to "Выкл.", "audio_delay" to "Задержка звука", "sub_delay" to "Задержка субтитров",
        "speed" to "Скорость", "vlc_missing" to "Не найден VLC (libvlc). Установите: sudo apt install vlc", "player_error" to "Ошибка воспроизведения",
        "clear_library" to "Очистить библиотеку", "refresh_on_start" to "Обновлять библиотеку при запуске", "continue" to "Продолжить просмотр",
        "error" to "Ошибка", "about" to "Remote Video Player для Linux", "fullscreen" to "Во весь экран",
    )
    private val EN = mapOf(
        "servers" to "Servers", "add_server" to "Add server", "edit_server" to "Server", "library" to "Library",
        "settings" to "Settings", "name" to "Name", "type" to "Type", "host" to "Host (or host:port)", "url" to "Address (URL)",
        "share" to "Share", "user" to "Username", "password" to "Password", "domain" to "Domain", "save" to "Save",
        "cancel" to "Cancel", "delete" to "Delete", "test" to "Test", "test_ok" to "Connection works", "back" to "Back",
        "empty_servers" to "No servers yet. Add SMB, WebDAV, HTTP or Jellyfin.", "empty_folder" to "Folder is empty",
        "search" to "Search", "refresh" to "Refresh", "cancel_scan" to "Stop", "found" to "Found: %d",
        "scanning" to "Indexing: %d of %d servers, %d videos", "all" to "All", "movies" to "Movies", "series" to "Series",
        "sort_title" to "By title", "sort_year" to "By year", "sort_added" to "By date", "language" to "Language",
        "lang_system" to "System", "theme" to "Theme", "theme_system" to "System", "theme_light" to "Light",
        "theme_dark" to "Dark", "resume" to "Resume where you left off", "auto_subs" to "Load subtitles from the folder",
        "show_hidden" to "Show hidden files", "tmdb" to "TMDB posters and details", "tmdb_key" to "TMDB key (v3 or v4)",
        "roots" to "Library folders (one per line, empty = whole share)", "depth" to "Scan depth",
        "timeout" to "Network timeout, s", "retries" to "Retries", "secrets_backend" to "Passwords are stored %s",
        "secret-service" to "in the system keyring (Secret Service)", "encrypted-file" to "in an encrypted file (%s)",
        "audio" to "Audio", "subtitles" to "Subtitles", "off" to "Off", "audio_delay" to "Audio delay", "sub_delay" to "Subtitle delay",
        "speed" to "Speed", "vlc_missing" to "VLC (libvlc) not found. Install it: sudo apt install vlc", "player_error" to "Playback error",
        "clear_library" to "Clear library", "refresh_on_start" to "Refresh library on start", "continue" to "Continue watching",
        "error" to "Error", "about" to "Remote Video Player for Linux", "fullscreen" to "Full screen",
    )
    /** For tests: every RU key has an EN translation. */
    fun missing(): Set<String> = RU.keys - EN.keys + (EN.keys - RU.keys)
}
