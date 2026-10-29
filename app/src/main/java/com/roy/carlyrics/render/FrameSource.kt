package com.roy.carlyrics.render

import com.roy.carlyrics.art.CoverArtRepository
import com.roy.carlyrics.lyrics.LyricsController
import com.roy.carlyrics.media.NowPlayingTracker

/** Snapshot of everything the renderer needs, read fresh on every frame. */
object FrameSource {
    fun current(): KaraokeRenderer.Frame {
        val np = NowPlayingTracker.nowPlaying.value
        val playbackMs = np?.playback?.positionAt() ?: 0L
        return KaraokeRenderer.Frame(
            nowPlaying = np,
            lyrics = LyricsController.state.value,
            positionMs = playbackMs - LyricsController.offsetMs.value,
            playbackMs = playbackMs,
            cover = CoverArtRepository.cover.value,
        )
    }
}
