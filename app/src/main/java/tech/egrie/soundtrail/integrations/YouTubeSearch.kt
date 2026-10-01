package tech.egrie.soundtrail.integrations

import java.net.URLEncoder

/** Builds plain YouTube *search* URLs. Soundtrail never downloads or streams YouTube content. */
object YouTubeSearch {
    fun searchUrl(title: String, artist: String): String {
        val query = listOf(artist.trim(), title.trim())
            .filter(String::isNotBlank)
            .joinToString(" ")
            .take(120)
        return "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}"
    }
}
