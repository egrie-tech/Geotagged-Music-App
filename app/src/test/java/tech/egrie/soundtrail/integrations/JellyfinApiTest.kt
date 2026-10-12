package tech.egrie.soundtrail.integrations

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinApiTest {
    private val itemId = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test fun `server addresses are normalized and implausible ones refused`() {
        assertEquals("http://192.168.1.20:8096", JellyfinApi.normalizeServerUrl("192.168.1.20:8096"))
        assertEquals("http://192.168.1.20:8096", JellyfinApi.normalizeServerUrl("  http://192.168.1.20:8096/  "))
        assertEquals("https://music.example.com", JellyfinApi.normalizeServerUrl("https://Music.Example.com"))
        // Jellyfin can live under a sub-path behind a reverse proxy.
        assertEquals(
            "https://music.example.com/jellyfin",
            JellyfinApi.normalizeServerUrl("https://music.example.com/jellyfin/")
        )

        assertNull(JellyfinApi.normalizeServerUrl(""))
        assertNull(JellyfinApi.normalizeServerUrl("   "))
        assertNull(JellyfinApi.normalizeServerUrl("ftp://192.168.1.20:8096"))
        assertNull(JellyfinApi.normalizeServerUrl("file:///etc/passwd"))
        assertNull(JellyfinApi.normalizeServerUrl("http://user:secret@192.168.1.20:8096"))
        assertNull(JellyfinApi.normalizeServerUrl("http://192.168.1.20:8096?api_key=leak"))
        assertNull(JellyfinApi.normalizeServerUrl("http://192.168.1.20:8096/web#frag"))
        assertNull(JellyfinApi.normalizeServerUrl("http://192.168.1.20:0"))
        assertNull(JellyfinApi.normalizeServerUrl("http://192.168.1.20:99999"))
        assertNull(JellyfinApi.normalizeServerUrl("http://192.168.1.20:8096/../admin"))
        assertNull(JellyfinApi.normalizeServerUrl("http:///jellyfin"))
        assertNull(JellyfinApi.normalizeServerUrl("http://192.168.1.20:8096/a b"))
    }

    @Test fun `the jellyfin authorization header uses the modern spelling`() {
        assertEquals(
            "MediaBrowser Client=\"Soundtrail\", Device=\"Android\", DeviceId=\"dev-1\", Version=\"1.0.0\"",
            JellyfinApi.authorizationHeader("dev-1")
        )
        assertEquals(
            "MediaBrowser Client=\"Soundtrail\", Device=\"Android\", DeviceId=\"dev-1\", " +
                "Version=\"1.0.0\", Token=\"tok\"",
            JellyfinApi.authorizationHeader("dev-1", "tok")
        )
        // A blank token must not produce an empty Token attribute.
        assertEquals(JellyfinApi.authorizationHeader("dev-1"), JellyfinApi.authorizationHeader("dev-1", "  "))
    }

    @Test fun `credentials travel as json with the signature jellyfin expects`() {
        val body = JSONObject(JellyfinApi.authenticationBody("ann\"e", "p w&x"))
        assertEquals("ann\"e", body.getString("Username"))
        assertEquals("p w&x", body.getString("Pw"))
    }

    @Test fun `public server info and the signed-in session are read defensively`() {
        assertEquals(
            JellyfinServerInfo("Living Room", "10.9.11", "a1b2c3"),
            JellyfinApi.publicInfo("""{"ServerName":"Living Room","Version":"10.9.11","Id":"a1b2c3"}""")
        )
        assertNull(JellyfinApi.publicInfo("<html><body>reverse proxy login</body></html>"))
        assertNull(JellyfinApi.publicInfo("""{"Version":"10.9.11","Id":"a1b2c3"}"""))
        assertNull(JellyfinApi.publicInfo("""{"ServerName":null,"Version":"10.9.11"}"""))

        val session = JellyfinApi.session(
            """{"AccessToken":"tok","ServerId":"srv","User":{"Id":"$itemId","Name":"Ann"}}"""
        )
        assertEquals("tok", session?.accessToken)
        assertEquals(itemId, session?.userId)
        assertEquals("Ann", session?.userName)
        assertEquals("srv", session?.serverId)
        assertNull(JellyfinApi.session("""{"User":{"Id":"$itemId"}}"""))
        assertNull(JellyfinApi.session("""{"AccessToken":"tok"}"""))
    }

    @Test fun `library items become pinnable tracks and unusable rows are skipped`() {
        val payload = """
            {"Items":[
              {"Id":"$itemId","Name":"Nightcall","Album":"OutRun","RunTimeTicks":2520000000,
               "Artists":["Kavinsky"]},
              {"Id":"ffffffffffffffffffffffffffffffff","Name":"Anonymous record","RunTimeTicks":1000},
              {"Id":"short","Name":"Not a real item id"},
              {"Id":"cccccccccccccccccccccccccccccccc","Artists":["No title at all"]},
              {"Id":"$itemId","Name":"Duplicate of the first"}
            ]}
        """.trimIndent()
        val tracks = JellyfinApi.items(payload)
        assertEquals(2, tracks.size)
        assertEquals("Nightcall", tracks[0].title)
        assertEquals("Kavinsky", tracks[0].artist)
        assertEquals("OutRun", tracks[0].album)
        assertEquals(252_000L, tracks[0].durationMs)
        assertEquals("Anonymous record", tracks[1].title)
        assertEquals("Unknown artist", tracks[1].artist)
        assertEquals(0L, tracks[1].durationMs)

        assertEquals(emptyList<JellyfinTrack>(), JellyfinApi.items("""{"TotalRecordCount":0}"""))
        assertEquals(emptyList<JellyfinTrack>(), JellyfinApi.items("<html>nope</html>"))
    }

    @Test fun `album artist stands in when an item lists no performers`() {
        val payload = """{"Items":[{"Id":"$itemId","Name":"Song","AlbumArtist":"Album Person"}]}"""
        assertEquals("Album Person", JellyfinApi.items(payload).single().artist)
    }

    @Test fun `search requests are scoped to the user and fully url encoded`() {
        val path = JellyfinApi.searchPath("user-1", "  Björk  ")
        assertTrue(path.startsWith("/Users/user-1/Items?recursive=true"))
        assertTrue(path.contains("includeItemTypes=Audio"))
        assertTrue(path.contains("searchTerm=Bj%C3%B6rk"))
        assertTrue(path.contains("limit=25"))
        assertFalse(path.contains("Björk"))

        // A query cannot smuggle extra parameters into the request.
        val escaped = JellyfinApi.searchPath("u", "a&limit=999")
        assertTrue(escaped.contains("searchTerm=a%26limit%3D999"))
        assertFalse(escaped.contains("&limit=999&"))
    }

    @Test fun `stream urls direct play first and never carry the token`() {
        val urls = JellyfinApi.streamPaths("http://192.168.1.20:8096", itemId, "user-1", "device-1")
        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("http://192.168.1.20:8096/Audio/$itemId/universal?static=true"))
        assertTrue(urls[0].contains("userId=user-1"))
        assertTrue(urls[0].contains("deviceId=device-1"))
        // The fallback asks the server to transcode to a progressive MP3 any decoder can open.
        assertTrue(urls[1].contains("static=false"))
        assertTrue(urls[1].contains("audioCodec=mp3"))
        assertTrue(urls[1].contains("transcodingContainer=mp3"))
        assertTrue(urls[1].contains("transcodingProtocol=http"))
        urls.forEach { assertFalse(it.contains("api_key")) }

        assertTrue(JellyfinApi.streamPaths("http://192.168.1.20:8096", "not-an-id", "u", "d").isEmpty())
        assertTrue(JellyfinApi.streamPaths("http://192.168.1.20:8096", "a/../b", "u", "d").isEmpty())
        assertTrue(JellyfinApi.streamPaths("", itemId, "u", "d").isEmpty())
    }

    @Test fun `jellyfin songs are stored as item references and nothing else`() {
        val link = JellyfinRef.link(itemId)
        assertEquals(MusicLink(MusicProvider.JELLYFIN, "jellyfin:item:$itemId"), link)
        assertEquals(MusicProvider.JELLYFIN, link?.provider)
        // The stored link holds no address, no user, and no token.
        assertFalse(link!!.url.contains("http"))
        assertFalse(link.url.contains("192.168"))
        assertEquals(itemId, JellyfinRef.itemId(link.url))

        // Stored pins and playlists read their links back through the shared parser.
        assertEquals(link, MusicLinkParser.parse("jellyfin:item:$itemId"))
        assertEquals(link, MusicLinkParser.parse("  JELLYFIN:ITEM:$itemId  "))
        assertEquals(link, MusicLinkParser.fromText("jellyfin:item:$itemId"))

        assertNull(MusicLinkParser.parse("jellyfin:item:"))
        assertNull(MusicLinkParser.parse("jellyfin:item:short"))
        assertNull(MusicLinkParser.parse("jellyfin:playlist:$itemId"))
        assertNull(MusicLinkParser.parse("https://jellyfin.example.com/web/index.html#!/details?id=$itemId"))
        assertNull(MusicLinkParser.parse("jellyfin:item:$itemId/../../Users"))
        assertNull(JellyfinRef.link("not an item id"))
        // Whitespace from a paste is tolerated, anything else about the link is not.
        assertEquals(itemId, JellyfinRef.itemId("jellyfin:item:$itemId "))
    }

    @Test fun `web links point at the item on the connected server`() {
        assertEquals(
            "http://192.168.1.20:8096/web/index.html#!/details?id=$itemId",
            JellyfinRef.webUrl("http://192.168.1.20:8096", itemId)
        )
        assertNull(JellyfinRef.webUrl("http://192.168.1.20:8096", "short"))
    }
}
