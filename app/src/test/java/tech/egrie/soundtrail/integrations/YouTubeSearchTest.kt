package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSearchTest {
    @Test fun `builds an encoded search url`() {
        assertEquals(
            "https://www.youtube.com/results?search_query=Bruce+Springsteen+Thunder+Road",
            YouTubeSearch.searchUrl("Thunder Road", "Bruce Springsteen")
        )
    }

    @Test fun `handles missing artist and reserved characters`() {
        assertEquals(
            "https://www.youtube.com/results?search_query=%C3%84tman+%26+song",
            YouTubeSearch.searchUrl("Ätman & song", "")
        )
        assertTrue(YouTubeSearch.searchUrl("Song?:#", "X").startsWith("https://www.youtube.com/results?search_query="))
    }
}
