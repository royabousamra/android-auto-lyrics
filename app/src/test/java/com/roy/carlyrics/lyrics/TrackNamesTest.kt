package com.roy.carlyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackNamesTest {

    @Test
    fun cleansCommonTitleNoise() {
        assertEquals("Come Together", TrackNames.cleanTitle("Come Together - Remastered 2009"))
        assertEquals("Get Lucky", TrackNames.cleanTitle("Get Lucky (feat. Pharrell Williams)"))
        assertEquals("Song", TrackNames.cleanTitle("Song [Live at Wembley]"))
        assertEquals("Hey Ya!", TrackNames.cleanTitle("Hey Ya!"))
        assertEquals("Stay", TrackNames.cleanTitle("Stay ft. Someone"))
    }

    @Test
    fun picksPrimaryArtist() {
        assertEquals("Daft Punk", TrackNames.primaryArtist("Daft Punk, Pharrell Williams"))
        assertEquals("Simon & Garfunkel", TrackNames.primaryArtist("Simon & Garfunkel"))
        assertEquals("Calvin Harris", TrackNames.primaryArtist("Calvin Harris feat. Rihanna"))
        assertEquals("Queen", TrackNames.primaryArtist("Queen"))
    }

    @Test
    fun splitsArtistFromYoutubeTitle() {
        assertEquals("Queen" to "Bohemian Rhapsody", TrackNames.splitArtistFromTitle("Queen - Bohemian Rhapsody"))
        assertNull(TrackNames.splitArtistFromTitle("Just a title"))
    }
}
