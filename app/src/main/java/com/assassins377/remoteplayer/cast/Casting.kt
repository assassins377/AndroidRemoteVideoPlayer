package com.assassins377.remoteplayer.cast

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Chromecast backend. The `full` flavor implements it with media3-cast / Google Cast (non-free Play services);
 * the `fdroid` flavor ships a no-op so the app has no proprietary dependencies there.
 */
interface ChromecastBackend {
    val supported: Boolean
    fun startDiscovery(ctx: Context, onRoutes: (List<CastTarget.Chromecast>) -> Unit)
    fun stopDiscovery(ctx: Context)
    /** Returns an error text, or null when casting started. */
    fun cast(ctx: Context, target: CastTarget.Chromecast, url: String, mime: String, title: String, positionMs: Long, onEnded: () -> Unit): String?
    /** True if a Chromecast session handled the toggle. */
    fun togglePause(): Boolean
    fun stop(ctx: Context)
}

sealed class CastTarget(val name: String) {
    /** [handle] is the flavor-specific route object. */
    class Chromecast(name: String, val handle: Any) : CastTarget(name)
    class Dlna(val renderer: DlnaRenderer) : CastTarget(renderer.name)
}

data class CastState(val devices: List<CastTarget> = emptyList(), val searching: Boolean = false, val activeName: String? = null, val error: String? = null)

/** Chromecast (flavor backend, Default Media Receiver) + DLNA push; media is always served through [StreamProxy]. */
object Casting {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()
    private val chromecast: ChromecastBackend get() = ChromecastImpl
    private var dlna: DlnaRenderer? = null

    /** Starts discovery of Chromecast routes (full flavor with Google Play services) and DLNA renderers. */
    fun startDiscovery(ctx: Context) {
        _state.value = _state.value.copy(searching = true, error = null)
        var chromecasts = emptyList<CastTarget>()
        var dlnas = emptyList<CastTarget>()
        fun publish() { _state.value = _state.value.copy(devices = chromecasts + dlnas) }
        if (chromecast.supported) chromecast.startDiscovery(ctx) { chromecasts = it; publish() }
        scope.launch(Dispatchers.IO) {
            dlnas = runCatching { Dlna.discover() }.getOrDefault(emptyList()).map { CastTarget.Dlna(it) }
            scope.launch { publish(); _state.value = _state.value.copy(searching = false) }
        }
    }

    fun stopDiscovery(ctx: Context) {
        chromecast.stopDiscovery(ctx)
        _state.value = _state.value.copy(searching = false)
    }

    /** Casts [playbackUri] from [positionMs]; [onStarted] runs (on Main) only once the target accepted playback. */
    fun cast(ctx: Context, target: CastTarget, playbackUri: String, fileName: String, title: String, positionMs: Long, onStarted: () -> Unit = {}) {
        val targetHost = (target as? CastTarget.Dlna)?.renderer?.location?.let { runCatching { java.net.URL(it).host }.getOrNull() }
        val host = StreamProxy.lanAddress(ctx, targetHost) ?: run { _state.value = _state.value.copy(error = "Wi-Fi?"); return }
        val proxy = StreamProxy.shared(ctx)
        val mime = StreamProxy.mimeFor(fileName)
        val url = proxy.url(host, proxy.register(playbackUri, fileName, mime))
        stop(ctx)
        when (target) {
            is CastTarget.Dlna -> scope.launch(Dispatchers.IO) {
                runCatching { Dlna.play(target.renderer, url, title, mime); if (positionMs > 1000) Dlna.seek(target.renderer, positionMs) }
                    .onSuccess { dlna = target.renderer; dlnaPaused = false; _state.value = _state.value.copy(activeName = target.name, error = null); scope.launch { onStarted() } }
                    .onFailure { e -> _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName) }
            }
            is CastTarget.Chromecast -> {
                val err = chromecast.cast(ctx, target, url, mime, title, positionMs) { _state.value = _state.value.copy(activeName = null) }
                if (err != null) { _state.value = _state.value.copy(error = err); return }
                _state.value = _state.value.copy(activeName = target.name, error = null)
                onStarted()
            }
        }
    }

    fun togglePause() {
        if (chromecast.togglePause()) return
        dlna?.let { r -> val resume = dlnaPaused; dlnaPaused = !resume; scope.launch(Dispatchers.IO) { if (resume) Dlna.resume(r) else Dlna.pause(r) } }
    }
    @Volatile private var dlnaPaused = false

    fun stop(ctx: Context) {
        dlna?.let { r -> scope.launch(Dispatchers.IO) { Dlna.stop(r) } }; dlna = null
        chromecast.stop(ctx)
        _state.value = _state.value.copy(activeName = null)
    }
}
