plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.smbj)
            api(libs.smbj.rpc.get().toString()) { exclude(group = "com.hierynomus", module = "smbj") }
            api(libs.okhttp)
            api(libs.room.runtime.kmp)
        }
        jvmMain.dependencies { implementation(libs.sqlite.bundled) }
        jvmTest.dependencies { implementation(libs.junit); implementation(libs.mockwebserver) }
    }
}

android {
    namespace = "com.assassins377.remoteplayer.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}


dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}
