package com.assassins377.remoteplayer

import android.app.Application
import android.appwidget.AppWidgetManager
import androidx.test.core.app.ApplicationProvider
import com.assassins377.remoteplayer.data.SmbHost
import com.assassins377.remoteplayer.ui.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class MiscTest {
    @Test fun smbHostPort() {
        assertEquals("nas" to 445, SmbHost.parse("nas"))
        assertEquals("192.168.1.5" to 4455, SmbHost.parse("192.168.1.5:4455"))
        assertEquals("fe80::1" to 139, SmbHost.parse("[fe80::1]:139"))
        assertEquals("fe80::1" to 445, SmbHost.parse("[fe80::1]"))
        assertEquals("nas" to 445, SmbHost.parse("smb://nas:99999/"))
        assertEquals("fe80::1" to 445, SmbHost.parse("[fe80::1]:99999"))
        assertEquals("fe80::1" to 445, SmbHost.parse("[fe80::1]:0"))
    }

    @Test fun backgroundContinueWhileBuffering() {
        assertTrue(com.assassins377.remoteplayer.player.PlayerHolder.shouldContinue(true, androidx.media3.common.Player.STATE_BUFFERING))
        assertFalse(com.assassins377.remoteplayer.player.PlayerHolder.shouldContinue(false, androidx.media3.common.Player.STATE_READY))
        assertFalse(com.assassins377.remoteplayer.player.PlayerHolder.shouldContinue(true, androidx.media3.common.Player.STATE_ENDED))
    }

    @Test fun widgetRendersEmpty() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val m = AppWidgetManager.getInstance(app)
        val id = Shadows.shadowOf(m).createWidget(ContinueWidget::class.java, R.layout.widget_continue)
        ContinueWidget.render(app, m, intArrayOf(id))
        val v = Shadows.shadowOf(m).getViewFor(id)
        assertEquals("Nothing to continue", v.findViewById<android.widget.TextView>(R.id.widget_item1).text.toString())
    }
}
