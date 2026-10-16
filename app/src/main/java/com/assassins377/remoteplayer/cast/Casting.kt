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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Default Media Receiver; no custom receiver app needed. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

sealed class CastTarget(val name: String) {
    class Chromecast(val route: MediaRouter.RouteInfo) : CastTarget(route.name)
    class Dlna(val renderer: DlnaRenderer) : CastTarget(renderer.name)
}

data class CastState(val devices: List<CastTarget> = emptyList(), val searching: Boolean = false, val activeName: String? = null, val error: String? = null)

/** Chromecast (media3-cast, Default Media Receiver) + DLNA push; media is always served through [StreamProxy]. */
@OptIn(UnstableApi::class)
object Casting {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()
    private var castPlayer: CastPlayer? = null
    private var dlna: DlnaRenderer? = null
    private var pending: MediaItem? = null
    private var pendingPos = 0L
    private var routerCb: MediaRouter.Callback? = null

    private fun castContext(ctx: Context): CastContext? = runCatching {
        @Suppress("DEPRECATION") CastContext.getSharedInstance(ctx.applicationContext)
    }.getOrNull()

    private fun selector() = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)).build()

    /** Starts discovery of Chromecast routes (Google Play services needed) and DLNA renderers. */
    fun startDiscovery(ctx: Context) {
        _state.value = _state.value.copy(searching = true, error = null)
        val router = runCatching { if (castContext(ctx) != null) MediaRouter.getInstance(ctx) else null }.getOrNull()
        var chromecasts = emptyList<CastTarget>()
        var dlnas = emptyList<CastTarget>()
        fun publish() { _state.value = _state.value.copy(devices = chromecasts + dlnas) }
        if (router != null) {
            val sel = selector()
            fun refresh() { chromecasts = router.routes.filter { !it.isDefault && it.matchesSelector(sel) }.map { CastTarget.Chromecast(it) }; publish() }
            val cb = object : MediaRouter.Callback() {
                override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            }
            routerCb?.let { router.removeCallback(it) }
            router.addCallback(sel, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN); routerCb = cb
            refresh()
        }
        scope.launch(Dispatchers.IO) {
            dlnas = runCatching { Dlna.discover() }.getOrDefault(emptyList()).map { CastTarget.Dlna(it) }
            scope.launch { publish(); _state.value = _state.value.copy(searching = false) }
        }
    }

    fun stopDiscovery(ctx: Context) {
        routerCb?.let { cb -> runCatching { MediaRouter.getInstance(ctx).removeCallback(cb) } }; routerCb = null
        _state.value = _state.value.copy(searching = false)
    }

    /** Casts [playbackUri] from [positionMs]. */
    fun cast(ctx: Context, target: CastTarget, playbackUri: String, fileName: String, title: String, positionMs: Long) {
        val host = StreamProxy.lanAddress() ?: run { _state.value = _state.value.copy(error = "Wi-Fi?"); return }
        val proxy = StreamProxy.shared(ctx)
        val mime = StreamProxy.mimeFor(fileName)
        val url = proxy.url(host, proxy.register(playbackUri, fileName, mime))
        stop(ctx)
        when (target) {
            is CastTarget.Dlna -> scope.launch(Dispatchers.IO) {
                runCatching { Dlna.play(target.renderer, url, title, mime) }
                    .onSuccess { dlna = target.renderer; _state.value = _state.value.copy(activeName = target.name, error = null) }
                    .onFailure { e -> _state.value = _state.value.copy(error = e.message) }
            }
            is CastTarget.Chromecast -> {
                val cc = castContext(ctx) ?: return
                pending = MediaItem.Builder().setUri(url).setMimeType(mime)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
                pendingPos = positionMs
                val cp = castPlayer ?: CastPlayer(cc).also { castPlayer = it }
                cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() { loadPending() }
                    override fun onCastSessionUnavailable() { _state.value = _state.value.copy(activeName = null) }
                })
                if (cp.isCastSessionAvailable) loadPending() else MediaRouter.getInstance(ctx).selectRoute(target.route)
                _state.value = _state.value.copy(activeName = target.name, error = null)
            }
        }
    }

    private fun loadPending() {
        val cp = castPlayer ?: return; val item = pending ?: return
        cp.setMediaItem(item, pendingPos); cp.prepare(); cp.playWhenReady = true; pending = null
    }

    fun togglePause() {
        castPlayer?.takeIf { it.isCastSessionAvailable }?.let { it.playWhenReady = !it.playWhenReady; return }
        dlna?.let { r -> scope.launch(Dispatchers.IO) { Dlna.pause(r) } }
    }

    fun stop(ctx: Context) {
        dlna?.let { r -> scope.launch(Dispatchers.IO) { Dlna.stop(r) } }; dlna = null
        castPlayer?.let { cp -> runCatching { cp.stop(); cp.clearMediaItems() } }
        runCatching { castContext(ctx)?.sessionManager?.endCurrentSession(true) }
        _state.value = _state.value.copy(activeName = null)
    }
}
