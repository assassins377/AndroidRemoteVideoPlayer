package com.assassins377.remoteplayer.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Default Media Receiver; no custom receiver app needed. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Google Cast via media3-cast (full flavor only). */
@OptIn(UnstableApi::class)
object ChromecastImpl : ChromecastBackend {
    override val supported = true
    private var castPlayer: CastPlayer? = null
    private var pending: MediaItem? = null
    private var pendingPos = 0L
    private var routerCb: MediaRouter.Callback? = null

    private fun castContext(ctx: Context): CastContext? = runCatching {
        @Suppress("DEPRECATION") CastContext.getSharedInstance(ctx.applicationContext)
    }.getOrNull()

    private fun selector() = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)).build()

    override fun startDiscovery(ctx: Context, onRoutes: (List<CastTarget.Chromecast>) -> Unit) {
        val router = runCatching { if (castContext(ctx) != null) MediaRouter.getInstance(ctx) else null }.getOrNull() ?: return
        val sel = selector()
        fun refresh() = onRoutes(router.routes.filter { !it.isDefault && it.matchesSelector(sel) }.map { CastTarget.Chromecast(it.name, it) })
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        }
        routerCb?.let { router.removeCallback(it) }
        router.addCallback(sel, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN); routerCb = cb
        refresh()
    }

    override fun stopDiscovery(ctx: Context) {
        routerCb?.let { cb -> runCatching { MediaRouter.getInstance(ctx).removeCallback(cb) } }; routerCb = null
    }

    override fun cast(ctx: Context, target: CastTarget.Chromecast, url: String, mime: String, title: String, positionMs: Long, onEnded: () -> Unit): String? {
        val cc = castContext(ctx) ?: return "Google Play services?"
        pending = MediaItem.Builder().setUri(url).setMimeType(mime).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
        pendingPos = positionMs
        val cp = castPlayer ?: CastPlayer(cc).also { castPlayer = it }
        cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() { loadPending() }
            override fun onCastSessionUnavailable() { onEnded() }
        })
        if (cp.isCastSessionAvailable) loadPending() else MediaRouter.getInstance(ctx).selectRoute(target.handle as MediaRouter.RouteInfo)
        return null
    }

    private fun loadPending() {
        val cp = castPlayer ?: return; val item = pending ?: return
        cp.setMediaItem(item, pendingPos); cp.prepare(); cp.playWhenReady = true; pending = null
    }

    override fun togglePause(): Boolean {
        val cp = castPlayer?.takeIf { it.isCastSessionAvailable } ?: return false
        cp.playWhenReady = !cp.playWhenReady; return true
    }

    override fun stop(ctx: Context) {
        castPlayer?.let { cp -> runCatching { cp.stop(); cp.clearMediaItems() } }
        runCatching { castContext(ctx)?.sessionManager?.endCurrentSession(true) }
    }
}
