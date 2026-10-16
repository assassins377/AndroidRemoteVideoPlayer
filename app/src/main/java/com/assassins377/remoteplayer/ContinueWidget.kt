package com.assassins377.remoteplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.settings.ResumeStore
import com.assassins377.remoteplayer.ui.home.ContinueItems
import kotlinx.coroutines.flow.MutableStateFlow

/** Home-screen widget: up to 3 "Continue watching" items; tap opens the player at the saved position. */
class ContinueWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        const val EXTRA_PLAY_URI = "play_uri"
        /** Set by MainActivity when launched from the widget; consumed by the nav graph. */
        val pendingPlay = MutableStateFlow<String?>(null)

        fun update(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx) ?: return
            val ids = runCatching { m.getAppWidgetIds(ComponentName(ctx, ContinueWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) render(ctx, m, ids)
        }

        fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val servers = ServerStore.servers.value
            val items = ContinueItems.build(ResumeStore.recent(3)) { id -> servers.firstOrNull { it.id == id }?.name }
            val views = RemoteViews(ctx.packageName, R.layout.widget_continue)
            val rows = listOf(R.id.widget_item1, R.id.widget_item2, R.id.widget_item3)
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            rows.forEachIndexed { i, id ->
                val it = items.getOrNull(i)
                if (it == null) {
                    views.setTextViewText(id, if (i == 0) ctx.getString(R.string.widget_empty) else "")
                } else {
                    val pct = it.progress?.let { p -> " · ${(p * 100).toInt()}%" }.orEmpty()
                    views.setTextViewText(id, "▶ ${it.title}$pct")
                    val intent = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_PLAY_URI, it.uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    views.setOnClickPendingIntent(id, PendingIntent.getActivity(ctx, 100 + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
