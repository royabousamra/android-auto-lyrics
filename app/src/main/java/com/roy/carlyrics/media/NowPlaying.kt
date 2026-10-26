package com.roy.carlyrics.media

import android.graphics.Bitmap
import android.os.SystemClock

data class Track(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sourcePackage: String,
) {
    /** Identity used to decide when to fetch new lyrics. Duration is rounded because some apps jitter it. */
    val key: String get() = "${title.lowercase()}|${artist.lowercase()}|${durationMs / 2000}"
}

/**
 * Playback position as reported by the media session. Sessions only publish a position when
 * something changes, so the current position is extrapolated from the last update.
 */
data class PlaybackSnapshot(
    val positionMs: Long,
    val updateTimeElapsedMs: Long,
    val speed: Float,
    val isPlaying: Boolean,
) {
    fun positionAt(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying) return positionMs
        val delta = ((nowElapsedMs - updateTimeElapsedMs) * speed).toLong()
        return (positionMs + delta).coerceAtLeast(0L)
    }

    companion object {
        val STOPPED = PlaybackSnapshot(0L, 0L, 0f, false)
    }
}

data class NowPlaying(
    val track: Track,
    val playback: PlaybackSnapshot,
    val art: Bitmap?,
)
