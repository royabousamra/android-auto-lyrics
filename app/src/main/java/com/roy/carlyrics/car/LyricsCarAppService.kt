package com.roy.carlyrics.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.roy.carlyrics.media.NowPlayingTracker

/** Android Auto entry point. */
class LyricsCarAppService : CarAppService() {

    // Personal sideloaded app: accept any host (Android Auto, the Desktop Head Unit, ...).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            NowPlayingTracker.start(carContext)
            return LyricsScreen(carContext)
        }
    }
}
