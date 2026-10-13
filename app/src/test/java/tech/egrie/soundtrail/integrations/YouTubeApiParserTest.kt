package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeApiParserTest {
    @Test fun `reads own playlist ids and titles`() {
        val playlists = YouTubeApiParser.playlists(
            """{"items":[
                {"id":"PL_public_1","snippet":{"title":"Road trip"}},
                {"id":"PL_private_2","snippet":{"title":"Late night"}},
                {"id":"","snippet":{"title":"Ignored"}},
                {"id":"PL_missing_title","snippet":{}}
            ]}"""
        )
        assertEquals(
            listOf(
                YouTubePlaylistRef("PL_public_1", "Road trip"),
                YouTubePlaylistRef("PL_private_2", "Late night")
            ),
            playlists
        )
    }

    @Test fun `matches playlist videos by all query terms and creates music links`() {
        val results = YouTubeApiParser.matchingTracks(
            """{"items":[
                {"snippet":{"title":"One More Time","videoOwnerChannelTitle":"Daft Punk"},"contentDetails":{"videoId":"dQw4w9WgXcQ"}},
                {"snippet":{"title":"Around the World","channelTitle":"Daft Punk"},"contentDetails":{"videoId":"abcdefghijk"}},
                {"snippet":{"title":"Deleted video","channelTitle":"Daft Punk"},"contentDetails":{"videoId":"ZYXWVUTSRQP"}}
            ]}""",
            "daft punk one more",
            "Electronic favorites"
        )

        assertEquals(1, results.size)
        assertEquals("One More Time", results.single().title)
        assertEquals("Daft Punk", results.single().artist)
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", results.single().url)
        assertEquals(MusicProvider.YOUTUBE_MUSIC, results.single().provider)
    }

    @Test fun `skips deleted private and malformed playlist items`() {
        val results = YouTubeApiParser.matchingTracks(
            """{"items":[
                {"snippet":{"title":"Deleted video"},"contentDetails":{"videoId":"dQw4w9WgXcQ"}},
                {"snippet":{"title":"Private video"},"contentDetails":{"videoId":"abcdefghijk"}},
                {"snippet":{"title":"No usable video id"},"contentDetails":{"videoId":"bad"}},
                {"snippet":{"title":"Valid title","channelTitle":"Channel"},"contentDetails":{"videoId":"abcdefghijk"}}
            ]}""",
            "Valid title",
            "Test list"
        )
        assertEquals(1, results.size)
        assertEquals("Valid title", results.single().title)
        assertTrue(results.single().url.endsWith("abcdefghijk"))
    }
}
