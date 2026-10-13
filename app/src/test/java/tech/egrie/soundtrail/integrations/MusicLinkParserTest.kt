package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicLinkParserTest {
    private val spotifyId = "4uLU6hMCjMI75M1A2tKUQC"

    @Test fun `spotify link and URI become the same clean track URL`() {
        val expected = MusicLink(MusicProvider.SPOTIFY, "https://open.spotify.com/track/$spotifyId")
        assertEquals(expected, MusicLinkParser.parse("https://open.spotify.com/track/$spotifyId?si=tracking"))
        assertEquals(expected, MusicLinkParser.parse("spotify:track:$spotifyId"))
        assertEquals(expected, MusicLinkParser.fromText("Listen to this: https://open.spotify.com/track/$spotifyId?si=x!"))
    }

    @Test fun `youtube and youtube music share links resolve to distinct canonical song URLs`() {
        val ytMusic = MusicLink(MusicProvider.YOUTUBE_MUSIC,
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        val yt = MusicLink(MusicProvider.YOUTUBE,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(ytMusic, MusicLinkParser.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=tracking"))
        // youtu.be and youtube.com are YouTube (not YouTube Music) to keep liked separation
        assertEquals(yt, MusicLinkParser.fromText("Check it out https://youtu.be/dQw4w9WgXcQ)"))
        assertEquals(yt, MusicLinkParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(ytMusic, MusicLinkParser.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test fun `youtube video id extraction accepts both youtube and youtube music links`() {
        assertEquals("dQw4w9WgXcQ", MusicLinkParser.youtubeVideoId(
            "https://youtu.be/dQw4w9WgXcQ?si=tracking"
        ))
        assertEquals("dQw4w9WgXcQ", MusicLinkParser.youtubeVideoId(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=tracking"
        ))
        assertEquals("dQw4w9WgXcQ", MusicLinkParser.youtubeVideoId(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        ))
        assertNull(MusicLinkParser.youtubeVideoId("https://music.youtube.com/playlist?list=PL123"))
        assertNull(MusicLinkParser.youtubeVideoId("https://example.invalid/watch?v=dQw4w9WgXcQ"))
    }

    @Test fun `soundcloud permalinks canonicalize and drop tracking parameters`() {
        val expected = MusicLink(MusicProvider.SOUNDCLOUD, "https://soundcloud.com/matas/hobnotropic")
        assertEquals(expected, MusicLinkParser.parse("https://soundcloud.com/matas/hobnotropic"))
        assertEquals(expected, MusicLinkParser.parse("https://www.soundcloud.com/matas/hobnotropic?si=tracking"))
        assertEquals(expected, MusicLinkParser.parse("https://m.soundcloud.com/matas/hobnotropic?utm_source=share"))
        assertEquals(expected, MusicLinkParser.fromText("Listen: https://soundcloud.com/matas/hobnotropic."))
    }

    @Test fun `soundcloud share links and secret tokens survive`() {
        assertEquals(
            MusicLink(MusicProvider.SOUNDCLOUD, "https://on.soundcloud.com/9xk2mQaB"),
            MusicLinkParser.parse("https://on.soundcloud.com/9xk2mQaB?si=tracking")
        )
        assertEquals(
            MusicLink(MusicProvider.SOUNDCLOUD, "https://on.soundcloud.com/AbC_123"),
            MusicLinkParser.parse("HTTPS://ON.SOUNDCLOUD.COM/AbC_123")
        )
        assertEquals(
            MusicLink(MusicProvider.SOUNDCLOUD, "https://soundcloud.com/artist/track/s-Secret9"),
            MusicLinkParser.parse("https://soundcloud.com/artist/track/s-Secret9")
        )
    }

    @Test fun `rejects soundcloud site pages playlists and lookalike hosts`() {
        assertNull(MusicLinkParser.parse("https://soundcloud.com/matas"))
        assertNull(MusicLinkParser.parse("https://soundcloud.com/matas/sets/summer-mix"))
        assertNull(MusicLinkParser.parse("https://soundcloud.com/discover/sets/charts-top-electronic"))
        assertNull(MusicLinkParser.parse("https://soundcloud.com/you/apps"))
        assertNull(MusicLinkParser.parse("https://soundcloud.com.evil.example/matas/hobnotropic"))
        assertNull(MusicLinkParser.parse("http://soundcloud.com/matas/hobnotropic"))
        assertNull(MusicLinkParser.parse("https://user@soundcloud.com/matas/hobnotropic"))
        assertNull(MusicLinkParser.parse("https://soundcloud.com:443/matas/hobnotropic"))
        assertNull(MusicLinkParser.parse("https://soundcloud.com/matas/hobnotropic#nowhere"))
        assertNull(MusicLinkParser.parse("https://on.soundcloud.com/abc"))
        assertNull(MusicLinkParser.parse("https://soundcloud.com//"))
    }

    @Test fun `rejects playlists forged hosts and non-https URLs`() {
        assertNull(MusicLinkParser.parse("https://music.youtube.com/playlist?list=PL123"))
        assertNull(MusicLinkParser.parse("https://music.youtube.com/search?q=artist+song"))
        assertNull(MusicLinkParser.parse("https://open.spotify.com.evil.example/track/$spotifyId"))
        assertNull(MusicLinkParser.parse("https://music.youtube.com.evil.example/watch?v=dQw4w9WgXcQ"))
        assertNull(MusicLinkParser.parse("http://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(MusicLinkParser.parse("https://user@open.spotify.com/track/$spotifyId"))
        assertNull(MusicLinkParser.parse("https://open.spotify.com:443/track/$spotifyId"))
        assertNull(MusicLinkParser.parse("https://open.spotify.com/album/$spotifyId"))
    }

    @Test fun `refuses malformed video ids and incomplete urls`() {
        assertNull(MusicLinkParser.fromText("https://music.youtube.com/watch?v=<bad>"))
        assertNull(MusicLinkParser.parse("https://music.youtube.com/watch?v=short"))
        assertNull(MusicLinkParser.fromText("Just a song title"))
    }
}
