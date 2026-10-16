package com.assassins377.remoteplayer

import android.app.Application
import kotlinx.coroutines.launch
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
        com.assassins377.remoteplayer.download.DownloadStore.init(this)
        com.assassins377.remoteplayer.player.Thumbnails.init(this)
        com.assassins377.remoteplayer.player.SubtitleDelayStore.init(this)
        com.assassins377.remoteplayer.settings.WatchedStore.init(this)
        com.assassins377.remoteplayer.meta.TmdbRepository.init(this)
        ServerStore.init(this, KeystoreSecretStore())
        // Jellyfin played-state sync for watched marks set in the app.
        com.assassins377.remoteplayer.settings.WatchedStore.onUserChange = { key, watched ->
            val id = key.substringBefore('/'); val path = key.substringAfter('/')
            ServerStore.get(id)?.takeIf { it.type == com.assassins377.remoteplayer.data.ServerType.JELLYFIN }?.let { srv ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { com.assassins377.remoteplayer.data.JellyfinRepository().setPlayed(srv, path, watched) }
            }
        }
        com.assassins377.remoteplayer.library.LibraryStore.init(this)
    }
}
