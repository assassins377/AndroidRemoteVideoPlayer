package com.assassins377.remoteplayer.cast

import android.content.Context

/** F-Droid flavor: no Google Cast (proprietary Play services); DLNA casting still works. */
object ChromecastImpl : ChromecastBackend {
    override val supported = false
    override fun startDiscovery(ctx: Context, onRoutes: (List<CastTarget.Chromecast>) -> Unit) {}
    override fun stopDiscovery(ctx: Context) {}
    override fun cast(ctx: Context, target: CastTarget.Chromecast, url: String, mime: String, title: String, positionMs: Long, onEnded: () -> Unit): String? = "Chromecast is not available in this build"
    override fun togglePause() = false
    override fun stop(ctx: Context) {}
}
