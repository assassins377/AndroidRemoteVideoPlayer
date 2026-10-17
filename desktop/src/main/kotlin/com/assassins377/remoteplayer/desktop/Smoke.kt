package com.assassins377.remoteplayer.desktop

import java.awt.Robot
import java.awt.Rectangle
import java.awt.Window
import com.assassins377.remoteplayer.desktop.Screen
import java.io.File
import javax.imageio.ImageIO

/** `--smoke`: saves a screenshot of the window to $REMOTEPLAYER_SCREENSHOT (default ./desktop-screenshot.png). */
object Smoke {
    /** REMOTEPLAYER_SMOKE_OPEN = "library" | "series:<serverId>:<path>" | "<serverId>:<path>" (folder or video) — initial screen for smoke screenshots. */
    fun open(nav: Nav) {
        val o = System.getenv("REMOTEPLAYER_SMOKE_OPEN") ?: return
        if (o == "library") { nav.go(Screen.Library); return }
        if (o.startsWith("series:")) { val r = o.removePrefix("series:"); Env.servers.get(r.substringBefore(':'))?.let { nav.go(Screen.Series(it, r.substringAfter(':'))) }; return }
        val srv = Env.servers.get(o.substringBefore(':')) ?: return
        val path = o.substringAfter(':')
        val f = com.assassins377.remoteplayer.data.RemoteFile(path.substringAfterLast('/'), path, !path.contains('.'))
        nav.go(if (f.isDirectory) Screen.Browser(srv, path) else Screen.Player(srv, f))
    }

    fun capture(w: Window) {
        val out = File(System.getenv("REMOTEPLAYER_SCREENSHOT") ?: "desktop-screenshot.png")
        runCatching {
            val p = w.locationOnScreen
            val img = Robot().createScreenCapture(Rectangle(p.x, p.y, w.width, w.height))
            ImageIO.write(img, "png", out); println("SMOKE OK ${out.absolutePath}")
        }.onFailure { println("SMOKE screenshot failed: $it") }
    }
}
