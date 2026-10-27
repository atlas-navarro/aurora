package com.aurora.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.aurora.music.data.LongForm
import com.aurora.music.data.PlaybackPrefs
import com.aurora.music.data.ResumePoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// episodes and audiobooks pick up where they were left, whichever way they come back into play
class ResumeTracker(private val points: ResumePoints, private val scope: CoroutineScope, prefs: Flow<PlaybackPrefs>) {
    private var player: Player? = null
    private var settings = PlaybackPrefs()
    private var ticker: Job? = null

    init {
        scope.launch { prefs.collect { settings = it } }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            val item = mediaItem ?: return
            if (p.currentPosition > START_MS) return
            if (!LongForm.resumes(id(item), durationSec(item, p), settings)) return
            val at = points.positionSec(id(item)) ?: return
            p.seekTo(at * 1000L)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val p = player ?: return
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) oldPosition.mediaItem?.let { record(it, oldPosition.positionMs, null, finished = true) }
            else if (oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId) oldPosition.mediaItem?.let { record(it, oldPosition.positionMs, null) }
            else if (reason == Player.DISCONTINUITY_REASON_SEEK) newPosition.mediaItem?.let { record(it, newPosition.positionMs, p) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ticker?.cancel()
            val p = player ?: return
            if (!isPlaying) { save(p); return }
            ticker = scope.launch {
                while (isActive) {
                    delay(SAVE_EVERY_MS)
                    save(p)
                }
            }
        }
    }

    fun observe(active: Player?) {
        if (active === player) return
        player?.let { save(it); it.removeListener(listener) }
        ticker?.cancel()
        player = active
        active?.addListener(listener)
    }

    fun close() {
        player?.let(::save)
        ticker?.cancel()
        points.flushNow()
    }

    private fun save(p: Player) {
        p.currentMediaItem?.let { record(it, p.currentPosition, p) }
    }

    private fun record(item: MediaItem, positionMs: Long, current: Player?, finished: Boolean = false) {
        if (finished) { points.record(id(item), 0, 0); return }
        val duration = durationSec(item, current)
        if (!LongForm.resumes(id(item), duration, settings)) return
        points.record(id(item), (positionMs / 1000).toInt(), duration)
    }

    private fun id(item: MediaItem) = item.mediaMetadata.extras?.getString("aurora.songId")?.takeIf { it.isNotBlank() } ?: item.mediaId

    private fun durationSec(item: MediaItem, current: Player?): Int {
        val known = item.mediaMetadata.extras?.getInt("aurora.durationSec") ?: 0
        val playing = current?.takeIf { it.currentMediaItem?.mediaId == item.mediaId }?.duration?.takeIf { it > 0 }?.div(1000)?.toInt() ?: 0
        return maxOf(known, playing)
    }

    private companion object {
        const val START_MS = 3_000L
        const val SAVE_EVERY_MS = 15_000L
    }
}
