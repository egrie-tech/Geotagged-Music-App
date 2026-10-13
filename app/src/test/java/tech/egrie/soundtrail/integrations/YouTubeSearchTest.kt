package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeSearchTest {
    @Test fun `builds an encoded search url`() {
        assertEquals(
            "https://music.youtube.com/search?q=Bruce+Springsteen+Thunder+Road",
            YouTubeSearch.searchUrl("Thunder Road", "Bruce Springsteen")
        )
    }

    @Test fun `handles missing artist and reserved characters`() {
        assertEquals(
            "https://music.youtube.com/search?q=%C3%84tman+%26+song",
            YouTubeSearch.searchUrl("Ätman & song", "")
        )
        assertEquals(
            "https://music.youtube.com/search?q=X+Song%3F%3A%23",
            YouTubeSearch.searchUrl("Song?:#", "X")
        )
    }
}
