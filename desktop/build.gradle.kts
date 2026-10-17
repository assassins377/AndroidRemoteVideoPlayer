import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.vlcj)
    implementation(libs.jmdns)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}

// Reuse the Android string resources (RU default + EN) for shared-code messages.
val copyStrings by tasks.registering(Copy::class) {
    from("../app/src/main/res/values/strings.xml") { rename { "strings-ru.xml" } }
    from("../app/src/main/res/values-en/strings.xml") { rename { "strings-en.xml" } }
    into(layout.buildDirectory.dir("generated/strings/android"))
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/strings")) }
tasks.named("processResources") { dependsOn(copyStrings) }

val appVersion = providers.fileContents(rootProject.layout.projectDirectory.file("app/build.gradle.kts")).asText
    .map { Regex("versionName = \"([^\"]+)\"").find(it)?.groupValues?.get(1) ?: "1.0.0" }

compose.desktop {
    application {
        mainClass = "com.assassins377.remoteplayer.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Msi)
            packageName = "remote-video-player"
            packageVersion = appVersion.get()
            description = "Remote Video Player — SMB/WebDAV/HTTP/Jellyfin video player"
            vendor = "assassins377"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.naming", "java.sql", "jdk.unsupported", "java.net.http", "jdk.crypto.ec")
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
                debMaintainer = "assassins377@users.noreply.github.com"
                shortcut = true
            }
            windows {
                iconFile.set(project.file("packaging/icon.ico"))
                menuGroup = "Remote Video Player"
                // Stable upgrade code: newer MSIs replace older installs.
                upgradeUuid = "6f1c2a43-8a5e-4d0b-9c7e-2b5d3f6a9e11"
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }
        }
    }
}
