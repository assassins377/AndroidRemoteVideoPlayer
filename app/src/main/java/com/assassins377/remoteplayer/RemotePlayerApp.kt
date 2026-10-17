package com.assassins377.remoteplayer

import android.app.Application
import com.assassins377.remoteplayer.data.KeystoreSecretStore
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.settings.ResumeStore
import com.assassins377.remoteplayer.settings.SettingsStore

class RemotePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStrings.context = this
        com.assassins377.remoteplayer.settings.FavoritesStore.init(this)
        SettingsStore.init(this)
        ResumeStore.init(this)
        com.assassins377.remoteplayer.player.SubtitleDelayStore.init(this)
        com.assassins377.remoteplayer.settings.WatchedStore.init(this)
        com.assassins377.remoteplayer.meta.TmdbRepository.init(this)
        ServerStore.init(this, KeystoreSecretStore())
        com.assassins377.remoteplayer.library.LibraryStore.init(this)
    }
}
