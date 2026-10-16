# Android Remote Video Player

**RU:** Android-плеер для видео с компьютера или NAS в домашней сети: SMB (Windows, Samba, Synology/QNAP), WebDAV, HTTP. Плеер на Media3 с жестами, дорожками, PiP и звуком в фоне, внешние субтитры, постеры TMDB, избранное, «Продолжить просмотр», общая библиотека и поиск по всем серверам сразу.

**EN:** Android player for videos on your PC or NAS over the home network: SMB (Windows, Samba, Synology/QNAP), WebDAV, HTTP. Media3 player with gestures, track selection, PiP and background audio, external subtitles, TMDB posters, favourites, Continue watching, a combined library with search across all servers.

## ✨ Новое / What's new

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

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug   # debug APK + tests (Robolectric, no emulator)
./gradlew assembleRelease                             # R8-minified; signed if KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD are set
```

Requires JDK 17 and Android SDK 36.

## License

See [LICENSE](LICENSE).
