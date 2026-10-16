package com.assassins377.remoteplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.assassins377.remoteplayer.MainActivity

/** Process-wide handle to the player owned by PlayerViewModel, so the service can expose it via MediaSession. */
object PlayerHolder {
    @Volatile var player: Player? = null
    internal var onRelease: (() -> Unit)? = null

    /** Called by the ViewModel before releasing the player. */
    fun detach() { onRelease?.invoke(); onRelease = null; player = null }
}

/**
 * MediaSession + media notification (play/pause/seek, lock screen, Bluetooth buttons). Media3 posts the
 * notification and promotes the service to foreground while playing, which keeps audio alive in background.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val p = PlayerHolder.player ?: run { stopSelf(); return }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, p).setSessionActivity(open).build()
        PlayerHolder.onRelease = { releaseSession(); stopSelf() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents: stop unless actively playing in background. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) { releaseSession(); stopSelf() }
    }

    private fun releaseSession() { session?.release(); session = null }

    override fun onDestroy() { releaseSession(); super.onDestroy() }
}
