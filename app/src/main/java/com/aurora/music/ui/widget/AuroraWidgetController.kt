package com.aurora.music.ui.widget

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Pushes a fresh render to the home-screen widget + Quick Settings tile when playback changes. */
object AuroraWidgetController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val styles = ConcurrentHashMap<GlanceId, WidgetStyle>()

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { AuroraWidget().updateAll(app) } }
        runCatching {
            TileService.requestListeningState(app, ComponentName(app, AuroraTileService::class.java))
        }
    }

    // each write takes the newest pick, so taps that land out of order still settle on the last one
    fun restyle(context: Context, id: GlanceId, style: WidgetStyle) {
        styles[id] = style
        val app = context.applicationContext
        scope.launch {
            runCatching {
                updateAppWidgetState(app, id) { prefs -> styles[id]?.writeTo(prefs) }
                AuroraWidget().update(app, id)
            }
        }
    }
}
