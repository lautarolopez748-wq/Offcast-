package com.offcast.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/** Widget de la pantalla de inicio: muestra episodios descargados y abre la app. */
class OffcastWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_offcast)

        // Click en "Abrir Offcast": abre la pantalla de inicio
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = "android.intent.action.MAIN"
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_open, openPending)

        // Click en el título: también abre la app
        views.setOnClickPendingIntent(R.id.widget_title, openPending)

        // Cargar episodios de la biblioteca (máximo 3)
        val library = Core.library
        val epIds = listOf(R.id.widget_ep1, R.id.widget_ep2, R.id.widget_ep3)
        for (i in epIds.indices) {
            if (i < library.size) {
                val s = library[i]
                views.setTextViewText(epIds[i], s.title)
                views.setViewVisibility(epIds[i], android.view.View.VISIBLE)

                val epIntent = Intent(context, MainActivity::class.java).apply {
                    action = "com.offcast.app.OPEN_EPISODE"
                    putExtra("episode_id", s.id)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val epPending = PendingIntent.getActivity(
                    context, appWidgetId * 10 + i, epIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                views.setOnClickPendingIntent(epIds[i], epPending)
            } else {
                views.setViewVisibility(epIds[i], android.view.View.GONE)
            }
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        /** Fuerza la actualización de todos los widgets activos. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, OffcastWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val inst = OffcastWidgetProvider()
                for (id in ids) {
                    inst.updateWidget(context, manager, id)
                }
            }
        }
    }
}
