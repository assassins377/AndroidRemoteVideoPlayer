package com.assassins377.remoteplayer

import android.app.Application
import com.assassins377.remoteplayer.data.KeystoreSecretStore
import com.assassins377.remoteplayer.data.ServerStore

class RemotePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServerStore.init(this, KeystoreSecretStore())
    }
}
