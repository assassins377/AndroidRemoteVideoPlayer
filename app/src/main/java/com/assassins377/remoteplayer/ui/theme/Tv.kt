package com.assassins377.remoteplayer.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent

object Tv {
    fun isTv(ctx: Context): Boolean =
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature("android.software.leanback")

    /** True while a full-screen surface (player) is shown: overscan padding is not applied. */
    val fullScreen = kotlinx.coroutines.flow.MutableStateFlow(false)

    enum class PlayerAction { SEEK_BACK, SEEK_FORWARD, TOGGLE_PLAY, PLAY, PAUSE, SHOW_CONTROLS, NONE }

    /** D-pad / remote keys in the player while the controller is hidden. Pure for tests. */
    fun playerAction(keyCode: Int, controllerVisible: Boolean): PlayerAction = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlayerAction.TOGGLE_PLAY
        KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerAction.PAUSE
        KeyEvent.KEYCODE_MEDIA_REWIND -> PlayerAction.SEEK_BACK
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlayerAction.SEEK_FORWARD
        else -> if (controllerVisible) PlayerAction.NONE else when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> PlayerAction.SEEK_BACK
            KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerAction.SEEK_FORWARD
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> PlayerAction.TOGGLE_PLAY
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> PlayerAction.SHOW_CONTROLS
            else -> PlayerAction.NONE
        }
    }
}
