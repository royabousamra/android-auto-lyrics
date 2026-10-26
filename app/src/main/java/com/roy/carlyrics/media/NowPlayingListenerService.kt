package com.roy.carlyrics.media

import android.service.notification.NotificationListenerService

/**
 * We never read notification contents. This service exists because Android only grants access to
 * other apps' media sessions to apps with an enabled notification listener. While it is enabled
 * the system keeps it bound, which also keeps our process alive for Android Auto.
 */
class NowPlayingListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NowPlayingTracker.start(this)
    }
}
