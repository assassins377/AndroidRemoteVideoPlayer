# F-Droid readiness

Status: **prepared, not submitted.** The draft metadata for fdroiddata is in
[`com.assassins377.remoteplayer.yml`](com.assassins377.remoteplayer.yml).

## Free-software build (`fdroid` flavor)

The Android app has two product flavors (dimension `distribution`):

| Flavor | Chromecast | Proprietary deps | Notes |
|---|---|---|---|
| `full` (default) | Google Cast via `media3-cast` | `play-services-cast-framework`, `play-services-base/basement/tasks/flags`, `firebase-encoders*` | GitHub releases |
| `fdroid` | no (DLNA casting still works) | none | F-Droid |

Only `full` depends on `media3-cast`; the Cast code and the `OPTIONS_PROVIDER_CLASS_NAME` manifest entry
live in `app/src/full/`. `app/src/fdroid/` has a no-op `ChromecastImpl`. Check:

```bash
./gradlew :app:dependencies --configuration fdroidReleaseRuntimeClasspath | grep -E "gms|firebase|play"   # → nothing
```

All other dependencies are free software: AndroidX/Media3/Room (Apache-2.0), OkHttp (Apache-2.0), smbj and
smbj-rpc (Apache-2.0), Bouncy Castle (MIT), Coil (Apache-2.0), kotlinx (Apache-2.0).

Anti-feature: **NonFreeNet** — TMDB posters/details (optional, needs a user-supplied API key).

## Reproducible builds

- `dependenciesInfo { includeInApk = false; includeInBundle = false }` — no Google-encrypted dependency
  metadata block in the APK signing block (F-Droid requirement).
- No build timestamps, git hashes or random values are embedded (versionName/versionCode are static).
- Toolchain: JDK 17 (Temurin in CI), Gradle wrapper, AGP 8.10.1, Kotlin 2.1.21; no NDK.
- Verified locally: two `clean :app:assembleFdroidRelease --no-build-cache` builds produced byte-identical
  unsigned APKs (SHA-256 `71e0928a…ea3e7`, 5.6 MB). Not yet verified across different machines/paths — the
  F-Droid build server comparison is the real test.
- Release CI (`release.yml`) attaches `AndroidRemoteVideoPlayer-<ver>-fdroid-release.apk`, signed with the
  same key as the GitHub APK (cert SHA-256 `91e8379e…07e054`), so `Binaries:` + `AllowedAPKSigningKeys:`
  let F-Droid ship the developer-signed APK when its build matches, and users can switch between GitHub
  and F-Droid without reinstalling.

Local equivalent of the F-Droid build:

```bash
sed -i -e 's/, ":desktop"//' settings.gradle.kts   # as in the recipe's prebuild (do not commit)
./gradlew :app:assembleFdroidRelease
apksigner verify --print-certs <signed apk>          # compare with the GitHub fdroid APK via apksigcopier/diffoscope
```

## Store metadata (fastlane)

`fastlane/metadata/android/{en-US,ru-RU}/`: title, short/full description, `changelogs/<versionCode>.txt`
(10, 11, 12 — add one per release), `en-US/images/icon.png` (512×512) and `phoneScreenshots/` (placeholders
from emulator test runs: splash, empty server list, add-server form — replace with library/player shots).

## Submitting (later, by the maintainer)

1. Tag a release that contains the `fdroid` flavor; update `versionName/versionCode/commit` in the draft.
2. Fork fdroiddata, add the file as `metadata/com.assassins377.remoteplayer.yml`, run
   `fdroid lint` and `fdroid build -v -l com.assassins377.remoteplayer`.
3. Open a merge request (or a Request For Packaging issue).
