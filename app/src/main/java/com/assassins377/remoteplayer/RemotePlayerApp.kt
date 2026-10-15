package com.assassins377.remoteplayer

import android.app.Application
import com.assassins377.remoteplayer.data.KeystoreSecretStore
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.settings.ResumeStore
import com.assassins377.remoteplayer.settings.SettingsStore

class RemotePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        ResumeStore.init(this)
        ServerStore.init(this, KeystoreSecretStore())
    }
}
