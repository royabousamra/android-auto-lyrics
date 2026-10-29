package com.roy.carlyrics.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.roy.carlyrics.lyrics.TrackNames
import com.roy.carlyrics.media.NowPlayingTracker
import com.roy.carlyrics.media.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CoverArt(
    val trackKey: String,
    /** The cover itself, or null if none could be found. */
    val image: Bitmap?,
    /** Blurred, darkened colour wash made from the cover, for the far background. */
    val backdrop: Bitmap?,
    val theme: ArtTheme,
    val source: Source,
) {
    enum class Source { PLAYER_IMAGE, PLAYER_LINK, ONLINE, NONE }
}

/**
 * Finds the cover for the current song and derives a colour theme from it.
 *
 * Order of preference:
 *  1. the image the music app embeds in its media session
 *  2. the image link the music app publishes (content:// or https://)
 *  3. an iTunes Search lookup, only if the player gave us nothing
 */
object CoverArtRepository {

    private const val TAG = "CoverArtRepository"
    private const val MAX_EDGE = 800

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _cover = MutableStateFlow<CoverArt?>(null)
    val cover: StateFlow<CoverArt?> = _cover.asStateFlow()

    /** Online lookups, including misses, so we ask at most once per song per app run. */
    private val onlineCache = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>) = size > 20
    }

    private data class Request(val track: Track, val image: Bitmap?, val link: String?)

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            NowPlayingTracker.nowPlaying
                .map { np -> np?.let { Request(it.track, it.art, it.artUri) } }
                .distinctUntilChanged()
                .collectLatest { req ->
                    if (req == null) {
                        _cover.value = null
                    } else {
                        _cover.value = resolve(app, req)
                    }
                }
        }
    }

    private suspend fun resolve(context: Context, req: Request): CoverArt {
        var source = CoverArt.Source.PLAYER_IMAGE
        var image: Bitmap? = req.image?.takeIf { !it.isRecycled }

        if (image == null && req.link != null) {
            image = withContext(Dispatchers.IO) { loadLink(context, req.link) }
            source = CoverArt.Source.PLAYER_LINK
        }
        if (image == null) {
            // Players often send the title first and the artwork a moment later; give them a
            // chance (this coroutine is cancelled if new metadata arrives) before going online.
            delay(800)
            image = withContext(Dispatchers.IO) { lookupOnline(req.track) }
            source = CoverArt.Source.ONLINE
        }
        if (image == null) {
            return CoverArt(req.track.key, null, null, ArtTheme.DEFAULT, CoverArt.Source.NONE)
        }

        return try {
            val soft = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
            CoverArt(req.track.key, image, Backdrop.from(soft), ArtTheme.from(soft), source)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not process cover", t)
            CoverArt(req.track.key, image, null, ArtTheme.DEFAULT, source)
        }
    }

    private fun loadLink(context: Context, link: String): Bitmap? = try {
        val uri = Uri.parse(link)
        when (uri.scheme?.lowercase()) {
            "content", "android.resource", "file" ->
                context.contentResolver.openInputStream(uri)?.use { decode(it.readBytes()) }
            "http", "https" -> download(link)
            else -> null
        }
    } catch (t: Throwable) {
        // Some players publish links only their own process may open.
        Log.i(TAG, "Player art link not readable: $link (${t.javaClass.simpleName})")
        null
    }

    /** Looks the song up on the iTunes Search API (free, no key) and takes the 600 px artwork. */
    private fun lookupOnline(track: Track): Bitmap? {
        synchronized(onlineCache) {
            if (onlineCache.containsKey(track.key)) return onlineCache[track.key]
        }
        val title = TrackNames.cleanTitle(track.title)
        val artist = TrackNames.primaryArtist(track.artist)
        val result = try {
            val term = URLEncoder.encode("$artist $title", "UTF-8")
            val body = get("https://itunes.apple.com/search?term=$term&media=music&entity=song&limit=10")
            val results = JSONObject(body).optJSONArray("results")
            val candidates = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }

            fun same(a: String, b: String) = a.isNotBlank() && b.isNotBlank() &&
                (a.contains(b, ignoreCase = true) || b.contains(a, ignoreCase = true))

            // Only accept a confident match; a wrong cover is worse than none.
            val match = candidates.firstOrNull {
                same(it.optString("artistName"), artist) && same(it.optString("trackName"), title)
            } ?: candidates.firstOrNull { same(it.optString("artistName"), artist) }

            match?.optString("artworkUrl100")
                ?.takeIf { it.isNotBlank() }
                ?.replace("100x100bb", "600x600bb")
                ?.let(::download)
        } catch (t: Throwable) {
            Log.i(TAG, "Online cover lookup failed", t)
            return null // don't cache network failures
        }
        synchronized(onlineCache) { onlineCache[track.key] = result }
        return result
    }

    private fun download(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) null else conn.inputStream.use { decode(it.readBytes()) }
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Decodes without loading huge images at full size. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
