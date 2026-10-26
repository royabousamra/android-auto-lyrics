package com.roy.carlyrics.lyrics

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.roy.carlyrics.media.NowPlayingTracker
import com.roy.carlyrics.media.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

sealed interface LyricsState {
    data object NoMusic : LyricsState
    data class Loading(val track: Track) : LyricsState
    data class Synced(val track: Track, val lyrics: SyncedLyrics) : LyricsState
    data class Plain(val track: Track, val lines: List<String>) : LyricsState
    data class Instrumental(val track: Track) : LyricsState
    data class NotFound(val track: Track) : LyricsState
    data class Failed(val track: Track, val message: String) : LyricsState
}

/**
 * Follows the now-playing track, fetches lyrics for it (disk cache first, then LRCLIB) and
 * exposes the result as [state]. Also stores a per-song timing offset the user can nudge
 * from the car screen.
 */
object LyricsController {

    private const val TAG = "LyricsController"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = LrcLibClient()

    private val _state = MutableStateFlow<LyricsState>(LyricsState.NoMusic)
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private val _offsetMs = MutableStateFlow(0L)
    /** Added to the playback position. Positive = lyrics later, negative = lyrics earlier. */
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    private lateinit var cacheDir: File
    private lateinit var prefs: SharedPreferences
    private var currentTrack: Track? = null
    private var loadJob: Job? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        cacheDir = File(app.filesDir, "lyrics").apply { mkdirs() }
        prefs = app.getSharedPreferences("offsets", Context.MODE_PRIVATE)

        scope.launch {
            NowPlayingTracker.nowPlaying
                .map { it?.track }
                .distinctUntilChangedBy { it?.key }
                .collect { track -> load(track, force = false) }
        }
    }

    fun retry() = load(currentTrack, force = true)

    fun nudgeOffset(deltaMs: Long) {
        val track = currentTrack ?: return
        val v = (_offsetMs.value + deltaMs).coerceIn(-10_000L, 10_000L)
        _offsetMs.value = v
        prefs.edit().putLong(track.key, v).apply()
    }

    private fun load(track: Track?, force: Boolean) {
        loadJob?.cancel()
        currentTrack = track
        if (track == null) {
            _state.value = LyricsState.NoMusic
            _offsetMs.value = 0L
            return
        }
        _offsetMs.value = prefs.getLong(track.key, 0L)
        _state.value = LyricsState.Loading(track)

        loadJob = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    (if (force) null else readCache(track))
                        ?: client.find(track.title, track.artist, track.album, track.durationMs)
                            ?.also { writeCache(track, it) }
                }
            }
            _state.value = result.fold(
                onSuccess = { toState(track, it) },
                onFailure = {
                    Log.w(TAG, "Lyrics lookup failed", it)
                    LyricsState.Failed(track, it.message ?: "Network error")
                },
            )
        }
    }

    private fun toState(track: Track, r: LyricsResult?): LyricsState = when {
        r == null -> LyricsState.NotFound(track)
        r.hasSynced -> {
            val parsed = LrcParser.parse(r.synced!!, track.durationMs)
            if (parsed.lines.isEmpty()) LyricsState.NotFound(track) else LyricsState.Synced(track, parsed)
        }
        r.instrumental -> LyricsState.Instrumental(track)
        r.hasPlain -> LyricsState.Plain(track, r.plain!!.lines())
        else -> LyricsState.NotFound(track)
    }

    // --- Disk cache: one small JSON file per track, so lyrics still work with no signal. ---

    private fun cacheFile(track: Track): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(track.key.toByteArray())
        return File(cacheDir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun readCache(track: Track): LyricsResult? = runCatching {
        val f = cacheFile(track)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        LyricsResult(
            synced = o.optString("synced").takeIf { it.isNotBlank() },
            plain = o.optString("plain").takeIf { it.isNotBlank() },
            instrumental = o.optBoolean("instrumental"),
        )
    }.getOrNull()

    private fun writeCache(track: Track, r: LyricsResult) {
        runCatching {
            val o = JSONObject()
                .put("synced", r.synced ?: "")
                .put("plain", r.plain ?: "")
                .put("instrumental", r.instrumental)
            cacheFile(track).writeText(o.toString())
        }
    }
}
