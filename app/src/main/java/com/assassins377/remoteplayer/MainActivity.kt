package com.assassins377.remoteplayer

import android.app.PictureInPictureParams
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.assassins377.remoteplayer.nav.AppNavGraph
import com.assassins377.remoteplayer.settings.Locales
import com.assassins377.remoteplayer.settings.SettingsStore
import com.assassins377.remoteplayer.ui.player.PipState
import com.assassins377.remoteplayer.ui.theme.RemotePlayerTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        SettingsStore.init(newBase)
        super.attachBaseContext(Locales.wrap(newBase, SettingsStore.current.language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RemotePlayerTheme { AppNavGraph() } }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.inPip.value = isInPictureInPictureMode
    }

    /** PiP when the user presses Home during playback (if enabled in settings). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP automatically (setAutoEnterEnabled); older versions do it here.
        if (Build.VERSION.SDK_INT < 31 && PipState.playing && SettingsStore.current.pipOnMinimize) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(PipState.aspect.first, PipState.aspect.second)).build())
            }
        }
    }
}
