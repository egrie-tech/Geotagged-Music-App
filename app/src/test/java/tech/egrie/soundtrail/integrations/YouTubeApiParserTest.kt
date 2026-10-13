package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", results.single().url)
        assertEquals(MusicProvider.YOUTUBE, results.single().provider)
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

    @Test fun `parses playlist items into importable entries skipping deleted private and bad ids`() {
        val items = YouTubeApiParser.playlistItems(
            """{"items":[
                {"snippet":{"title":"One More Time","videoOwnerChannelTitle":"Daft Punk","playlistId":"PL_a"},"contentDetails":{"videoId":"dQw4w9WgXcQ"}},
                {"snippet":{"title":"Around the World","channelTitle":"Daft Punk"},"contentDetails":{"videoId":"abcdefghijk"}},
                {"snippet":{"title":"Deleted video"},"contentDetails":{"videoId":"ZYXWVUTSRQP"}},
                {"snippet":{"title":"Private video"},"contentDetails":{"videoId":"ZZZZZZZZZZZ"}},
                {"snippet":{"title":"Bad id"},"contentDetails":{"videoId":"short"}}
            ]}"""
        )
        assertEquals(2, items.size)
        assertEquals("One More Time", items[0].title)
        assertEquals("Daft Punk", items[0].artist)
        assertEquals("dQw4w9WgXcQ", items[0].videoId)
        assertEquals("Around the World", items[1].title)
        assertEquals("Daft Punk", items[1].artist)
    }
}
