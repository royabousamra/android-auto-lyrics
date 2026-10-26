package com.roy.carlyrics.lyrics

/** Cleans up the title / artist strings music apps publish so lyrics lookups match more often. */
object TrackNames {

    private val BRACKETED_NOISE = Regex(
        """\s*[(\[](?:[^)\]]*?(?:remaster|remastered|live|version|edit|mono|stereo|mix|deluxe|bonus|explicit|clean|radio|single|from|feat\.?|ft\.?|with)\b[^)\]]*)[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val DASH_SUFFIX = Regex(
        """\s+[-–—]\s+.*\b(?:remaster|remastered|live|version|edit|mono|stereo|mix|radio|single)\b.*$""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT_INLINE = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(?:,|;|/|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*""", RegexOption.IGNORE_CASE)

    fun cleanTitle(title: String): String {
        var t = title
        t = BRACKETED_NOISE.replace(t, "")
        t = DASH_SUFFIX.replace(t, "")
        t = FEAT_INLINE.replace(t, "")
        return t.trim().ifEmpty { title.trim() }
    }

    /** "Daft Punk, Pharrell Williams" -> "Daft Punk". "&" is kept because it is often part of a band name. */
    fun primaryArtist(artist: String): String =
        artist.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    /** YouTube-style "Artist - Title" titles with no separate artist field. */
    fun splitArtistFromTitle(title: String): Pair<String, String>? {
        val parts = title.split(" - ", " – ", " — ", limit = 2)
        if (parts.size != 2 || parts.any { it.isBlank() }) return null
        return parts[0].trim() to parts[1].trim()
    }
}
