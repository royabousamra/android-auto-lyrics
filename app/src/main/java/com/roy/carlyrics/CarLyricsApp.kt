package com.roy.carlyrics

import android.app.Application
import com.roy.carlyrics.lyrics.LyricsController
import com.roy.carlyrics.media.NowPlayingTracker

class CarLyricsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LyricsController.init(this)
        // Works only once notification access is granted; MainActivity retries after that.
        NowPlayingTracker.start(this)
    }
}
