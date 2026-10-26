package com.roy.carlyrics.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.roy.carlyrics.lyrics.TrackNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches every active media session on the phone (Spotify, YouTube Music, Apple Music, Tidal,
 * Pocket Casts, ...) and publishes the one that is currently playing.
 *
 * Reading other apps' sessions requires our [NowPlayingListenerService] to be enabled under
 * Settings > Notifications > Device & app notifications (a.k.a. "Notification access").
 */
object NowPlayingTracker {

    private const val TAG = "NowPlayingTracker"

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val controllers = LinkedHashMap<MediaSession.Token, Tracked>()
    private var current: MediaSession.Token? = null

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback) {
        var lastPlayingAt = 0L
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessionsChanged(list.orEmpty())
    }

    fun hasNotificationAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, NowPlayingListenerService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Starts tracking. Safe to call repeatedly. Returns false if notification access is missing. */
    fun start(context: Context): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start(context) }
            return true
        }
        if (sessionManager != null) return true
        val app = context.applicationContext
        val manager = app.getSystemService(MediaSessionManager::class.java) ?: return false
        val component = ComponentName(app, NowPlayingListenerService::class.java)
        return try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            sessionManager = manager
            onSessionsChanged(manager.getActiveSessions(component))
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification access yet", e)
            false
        }
    }

    fun stop() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        controllers.values.forEach { it.controller.unregisterCallback(it.callback) }
        controllers.clear()
        current = null
        _nowPlaying.value = null
    }

    private fun onSessionsChanged(active: List<MediaController>) {
        val activeTokens = active.map { it.sessionToken }.toSet()

        // Drop sessions that went away.
        val gone = controllers.keys.filter { it !in activeTokens }
        for (token in gone) {
            controllers.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
        }

        // Track new ones.
        for (controller in active) {
            if (controller.sessionToken in controllers) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() = refresh()
            }
            controller.registerCallback(callback, main)
            controllers[controller.sessionToken] = Tracked(controller, callback)
        }
        refresh()
    }

    private fun refresh() {
        val now = SystemClock.elapsedRealtime()
        for (t in controllers.values) {
            if (t.controller.playbackState?.state == PlaybackState.STATE_PLAYING && t.lastPlayingAt == 0L) {
                t.lastPlayingAt = now
            } else if (t.controller.playbackState?.state != PlaybackState.STATE_PLAYING) {
                t.lastPlayingAt = 0L
            }
        }

        // Prefer the session that most recently started playing; otherwise keep the current one
        // (e.g. it is just paused); otherwise the first session that has metadata.
        val playing = controllers.entries
            .filter { it.value.lastPlayingAt > 0L }
            .maxByOrNull { it.value.lastPlayingAt }
        val chosen = playing?.key
            ?: current?.takeIf { it in controllers }
            ?: controllers.entries.firstOrNull { it.value.controller.metadata != null }?.key
        current = chosen

        _nowPlaying.value = chosen?.let { controllers[it]?.controller }?.let(::snapshot)
    }

    private fun snapshot(controller: MediaController): NowPlaying? {
        val md = controller.metadata ?: return null
        var title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        var artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        if (artist.isBlank()) {
            TrackNames.splitArtistFromTitle(title)?.let { (a, t) -> artist = a; title = t }
        }
        if (title.isBlank()) return null

        val track = Track(
            title = title,
            artist = artist,
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
            sourcePackage = controller.packageName,
        )

        val ps = controller.playbackState
        val playback = if (ps == null) {
            PlaybackSnapshot.STOPPED
        } else {
            val updateTime = ps.lastPositionUpdateTime.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
            val playing = ps.state == PlaybackState.STATE_PLAYING
            PlaybackSnapshot(
                positionMs = ps.position.coerceAtLeast(0L),
                updateTimeElapsedMs = updateTime,
                speed = if (ps.playbackSpeed > 0f) ps.playbackSpeed else 1f,
                isPlaying = playing,
            )
        }

        val art: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        return NowPlaying(track, playback, art)
    }
}
