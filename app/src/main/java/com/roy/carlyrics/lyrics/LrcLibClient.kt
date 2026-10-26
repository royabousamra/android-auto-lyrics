package com.roy.carlyrics.lyrics

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class LyricsResult(
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean,
) {
    val hasSynced get() = !synced.isNullOrBlank()
    val hasPlain get() = !plain.isNullOrBlank()
}

/**
 * Minimal client for https://lrclib.net, a free, key-less synced lyrics database.
 * All calls are blocking; run them off the main thread.
 */
class LrcLibClient(
    private val baseUrl: String = "https://lrclib.net",
    private val userAgent: String = "CarLyrics/0.1 (personal Android Auto lyrics app)",
) {

    fun find(title: String, artist: String, album: String, durationMs: Long): LyricsResult? {
        val durationSec = durationMs / 1000
        val cleanTitle = TrackNames.cleanTitle(title)
        val mainArtist = TrackNames.primaryArtist(artist)
        var plainFallback: LyricsResult? = null

        fun consider(r: LyricsResult?): LyricsResult? {
            if (r == null) return null
            if (r.hasSynced || r.instrumental) return r
            if (r.hasPlain && plainFallback == null) plainFallback = r
            return null
        }

        // 1. Exact signature match (fast path, uses LRCLIB's own matching on duration).
        if (durationSec > 0) {
            consider(get(title, artist, album, durationSec))?.let { return it }
            if (cleanTitle != title || mainArtist != artist) {
                consider(get(cleanTitle, mainArtist, album, durationSec))?.let { return it }
            }
        }
        // 2. Field search, pick the closest duration.
        consider(search(mapOf("track_name" to cleanTitle, "artist_name" to mainArtist), durationSec))?.let { return it }
        // 3. Free-text search as a last resort.
        consider(search(mapOf("q" to "$mainArtist $cleanTitle"), durationSec))?.let { return it }

        return plainFallback
    }

    private fun get(title: String, artist: String, album: String, durationSec: Long): LyricsResult? {
        val params = buildMap {
            put("track_name", title)
            put("artist_name", artist)
            if (album.isNotBlank()) put("album_name", album)
            put("duration", durationSec.toString())
        }
        val body = request("/api/get", params) ?: return null
        return parse(JSONObject(body))
    }

    private fun search(params: Map<String, String>, durationSec: Long): LyricsResult? {
        val body = request("/api/search", params) ?: return null
        val arr = JSONArray(body)
        val candidates = (0 until arr.length()).map { arr.getJSONObject(it) }
        if (candidates.isEmpty()) return null

        fun durationDiff(o: JSONObject): Double =
            if (durationSec <= 0) 0.0 else abs(o.optDouble("duration", 0.0) - durationSec)

        val best = candidates
            .filter { durationSec <= 0 || durationDiff(it) <= 8.0 }
            .sortedWith(
                compareByDescending<JSONObject> { !it.isNull("syncedLyrics") }
                    .thenBy { durationDiff(it) }
            )
            .firstOrNull() ?: return null
        return parse(best)
    }

    private fun parse(o: JSONObject) = LyricsResult(
        synced = o.optStringOrNull("syncedLyrics"),
        plain = o.optStringOrNull("plainLyrics"),
        instrumental = o.optBoolean("instrumental", false),
    )

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    /** Returns the body on 200, null on 404, throws on other failures. */
    private fun request(path: String, params: Map<String, String>): String? {
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = URL("$baseUrl$path?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            return when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("LRCLIB $path returned HTTP $code")
            }
        } catch (e: IOException) {
            Log.w("LrcLibClient", "Request failed: $path", e)
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
