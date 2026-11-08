package com.music.bitchord

import com.music.bitchord.data.discord.DiscordArtwork
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscordArtworkTest {
    private val local = Song("content://song", "Ghost", "GUNSHIP", null, albumName = "Dark All Day")
    private fun candidate(album: String?, artist: String = "GUNSHIP", title: String = "Ghost") =
        Song("id", title, artist, "https://example.com/cover.jpg", albumName = album)

    @Test
    fun `matches the named release regardless of candidate order`() {
        assertEquals(candidate("Dark All Day").thumbnailUrl,
            DiscordArtwork.select(local, listOf(candidate("Other"), candidate("Dark All Day"))))
    }

    @Test
    fun `rejects conflicting artist release and version`() {
        assertNull(DiscordArtwork.select(local, listOf(candidate("Dark All Day", artist = "Someone Else"))))
        assertNull(DiscordArtwork.select(local, listOf(candidate("Other"))))
        assertNull(DiscordArtwork.select(local, listOf(candidate("Dark All Day", title = "Ghost (Live)"))))
    }

    @Test
    fun `rejects ambiguous releases when album is unknown`() {
        assertNull(DiscordArtwork.select(local.copy(albumName = null),
            listOf(candidate("First"), candidate("Second"))))
    }
}
