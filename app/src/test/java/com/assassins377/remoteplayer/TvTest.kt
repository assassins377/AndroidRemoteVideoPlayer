package com.assassins377.remoteplayer

import android.view.KeyEvent
import com.assassins377.remoteplayer.ui.theme.Tv
import com.assassins377.remoteplayer.ui.theme.Tv.PlayerAction.*
import org.junit.Assert.assertEquals
import org.junit.Test

class TvTest {
    @Test fun keys() {
        assertEquals(SEEK_BACK, Tv.playerAction(KeyEvent.KEYCODE_DPAD_LEFT, false))
        assertEquals(NONE, Tv.playerAction(KeyEvent.KEYCODE_DPAD_LEFT, true)) // controller navigates itself
        assertEquals(SEEK_FORWARD, Tv.playerAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, true))
        assertEquals(TOGGLE_PLAY, Tv.playerAction(KeyEvent.KEYCODE_DPAD_CENTER, false))
        assertEquals(SHOW_CONTROLS, Tv.playerAction(KeyEvent.KEYCODE_DPAD_DOWN, false))
        assertEquals(NONE, Tv.playerAction(KeyEvent.KEYCODE_BACK, false))
    }
}
