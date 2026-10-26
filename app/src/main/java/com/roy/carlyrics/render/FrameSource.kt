package com.roy.carlyrics.render

import com.roy.carlyrics.lyrics.LyricsController
import com.roy.carlyrics.media.NowPlayingTracker

/** Snapshot of everything the renderer needs, read fresh on every frame. */
object FrameSource {
    fun current(): KaraokeRenderer.Frame {
        val np = NowPlayingTracker.nowPlaying.value
        val position = (np?.playback?.positionAt() ?: 0L) - LyricsController.offsetMs.value
        return KaraokeRenderer.Frame(np, LyricsController.state.value, position)
    }
}
