package tech.egrie.soundtrail.integrations

import java.net.URLEncoder

/** Builds YouTube Music search URLs. Search and playback stay in YouTube Music. */
object YouTubeSearch {
    fun searchUrl(title: String, artist: String): String {
        val query = listOf(artist.trim(), title.trim())
            .filter(String::isNotBlank)
            .joinToString(" ")
            .take(120)
        return "https://music.youtube.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
    }
}
