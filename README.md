# Android Remote Video Player

Android application for browsing and streaming videos stored on a PC or file server (SMB/CIFS, HTTP, WebDAV).

## Features

- Browse folders and files on local network / file servers
- Support for **SMB/CIFS** shares (Windows, Samba, NAS)
- Direct HTTP/HTTPS video playback
- Modern video player based on **Media3 / ExoPlayer**
- Subtitle support, playback controls, seek, speed
- Material 3 UI with Jetpack Compose
- Dark / Light theme

## Tech Stack

| Component       | Technology                     |
|-----------------|--------------------------------|
| Language        | Kotlin                         |
| UI              | Jetpack Compose + Material 3   |
| Video Player    | AndroidX Media3 (ExoPlayer)    |
| SMB Client      | smbj                           |
| Architecture    | MVVM + Coroutines + Flow       |
| Navigation      | Navigation Compose             |

## Recommended Dependencies

Add to `app/build.gradle.kts`:

```kotlin
dependencies {
    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // SMB
    implementation("com.hierynomus:smbj:0.13.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose")
    implementation("androidx.navigation:navigation-compose")
}
