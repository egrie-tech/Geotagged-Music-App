package tech.egrie.soundtrail.integrations

import java.net.URLEncoder

/** Builds YouTube and YouTube Music search URLs. All audio now plays directly in Soundtrail. */
object YouTubeSearch {
    fun searchUrl(title: String, artist: String): String {
        val query = listOf(artist.trim(), title.trim())
            .filter(String::isNotBlank)
            .joinToString(" ")
            .take(120)
        return "https://music.youtube.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
    }

    fun youtubeSearchUrl(title: String, artist: String): String {
        val query = listOf(artist.trim(), title.trim())
            .filter(String::isNotBlank)
            .joinToString(" ")
            .take(120)
        return "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}"
    }
}
