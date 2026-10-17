# Android Remote Video Player

**RU:** Android-плеер для видео с компьютера или NAS в домашней сети: SMB (Windows, Samba, Synology/QNAP), WebDAV, HTTP. Плеер на Media3 с жестами, дорожками, PiP и звуком в фоне, внешние субтитры, постеры TMDB, избранное, «Продолжить просмотр», общая библиотека и поиск по всем серверам сразу.

**EN:** Android player for videos on your PC or NAS over the home network: SMB (Windows, Samba, Synology/QNAP), WebDAV, HTTP. Media3 player with gestures, track selection, PiP and background audio, external subtitles, TMDB posters, favourites, Continue watching, a combined library with search across all servers.

## ✨ Новое / What's new

- Порт SMB, звук после «Назад», виджет / SMB port, background audio after Back, widget
- Избранное и «Не тот фильм?» в библиотеке / Favourites & match fix in Library
- Подсказки и фразовый поиск / Search suggestions & phrases
- Jellyfin: вход, библиотеки, просмотрено (Plex — нет) / Jellyfin direct support
- Загрузки для просмотра без сети / Offline downloads
- Трансляция: Chromecast и DLNA через локальный прокси / Casting: Chromecast + DLNA via local proxy
- Android TV: лаунчер, пульт, сетка / Android TV support
- Карточка фильма: актёры, жанры, трейлер / Details: cast, genres, trailer
- Кадры из видео, если нет постера / Video frame thumbnails
- Задержка субтитров для каждого файла / Per-file subtitle delay
- Сериалы: сезоны/серии, отметки «просмотрено», следующая серия с отсчётом / Series grouping, watched marks, up-next countdown
## 📥 Скачать / Download

Последний APK — на странице **[Releases](https://github.com/assassins377/AndroidRemoteVideoPlayer/releases)** (Android 8.0+).
Get the latest APK from **[Releases](https://github.com/assassins377/AndroidRemoteVideoPlayer/releases)** (Android 8.0+).

## 📖 Руководство / Guide

- 🇷🇺 [Руководство пользователя (установка, подключение серверов, настройки, FAQ)](docs/GUIDE.ru.md)
- 🇬🇧 [User guide (installation, connecting servers, settings, FAQ)](docs/GUIDE.en.md)

История изменений / Changelog: [CHANGELOG.md](CHANGELOG.md)

## 🛠 Сборка / Building

| Component | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Player | AndroidX Media3 (ExoPlayer, MediaSession) |
| SMB | smbj |
| WebDAV / HTTP | OkHttp |
| Storage | DataStore, Android Keystore |
| Library index | Room KMP (FTS4): framework SQLite on Android, bundled SQLite on desktop |

**Modules:** `app` — Android UI/player; `shared` — Kotlin Multiplatform (Android + JVM): SMB/WebDAV/HTTP/Jellyfin clients, TMDB client + cache, title/series parsing, library index and search. Platform glue: `CoreSettings`, `CoreStrings` (strings by resource name), `KeyValue`.

```bash
./gradlew assembleDebug testFullDebugUnitTest lintFullDebug   # debug APKs (full + fdroid) + tests (Robolectric, no emulator)
./gradlew :shared:jvmTest                             # shared module on the desktop JVM (bundled SQLite)
./gradlew assembleFullRelease assembleFdroidRelease    # R8-minified; signed if KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD are set
```

Requires JDK 17 and Android SDK 36.

## License

See [LICENSE](LICENSE).

## Linux (desktop)

The `desktop` module (Compose for Desktop) shares the `shared` module with the Android app.

- Player dependency: system VLC (`sudo apt install vlc`, libvlc 3.x, used via vlcj). Without it the player screen shows an error.
- Passwords are stored in the Secret Service via `secret-tool` (package `libsecret-tools`). If unavailable, an encrypted file (AES-256-GCM) `~/.config/remoteplayer/secrets.properties` with key `secret.key` (mode 600) is used. Force with `REMOTEPLAYER_SECRETS=file`.
- Data: `$XDG_CONFIG_HOME/remoteplayer` (servers, settings), `$XDG_DATA_HOME/remoteplayer` (library, positions), `$XDG_CACHE_HOME/remoteplayer` (posters).
- Extra libvlc args: `REMOTEPLAYER_VLC_ARGS`.
- Run from source: `./gradlew :desktop:run`.

## F-Droid

Product flavors `full` (Google Cast) and `fdroid` (no proprietary dependencies, DLNA-only casting); fastlane metadata in `fastlane/metadata/android`; draft fdroiddata recipe and reproducible-build notes in [docs/fdroid](docs/fdroid/README.md).
