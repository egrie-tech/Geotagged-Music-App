package tech.egrie.soundtrail.integrations

import java.net.URI
import java.net.URLDecoder

/** Only known song URLs are opened. Strip tracking parameters and refuse untrusted hosts. */
enum class MusicProvider(val label: String) {
    SPOTIFY("Spotify"), YOUTUBE_MUSIC("YouTube Music")
}

data class MusicLink(val provider: MusicProvider, val url: String)

object MusicLinkParser {
    private val spotifyId = Regex("[A-Za-z0-9]{22}")
    private val youtubeId = Regex("[A-Za-z0-9_-]{11}")
    private val sharedUrl = Regex("https?://[^\\s<>]+|spotify:track:[A-Za-z0-9]{22}", RegexOption.IGNORE_CASE)

    fun fromText(text: String): MusicLink? {
        parse(text.trim())?.let { return it }
        return sharedUrl.findAll(text).firstNotNullOfOrNull { match ->
            parse(match.value.trimEnd('.', ',', ')', ']', '>', '!', ';'))
        }
    }

    fun parse(input: String): MusicLink? {
        val value = input.trim()
        if (value.startsWith("spotify:track:", ignoreCase = true)) {
            val id = value.substringAfterLast(':')
            return if (spotifyId.matches(id)) {
                MusicLink(MusicProvider.SPOTIFY, "https://open.spotify.com/track/$id")
            } else null
        }

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
            uri.port != -1 || uri.fragment != null
        ) return null

        val host = uri.host?.lowercase() ?: return null
        val path = uri.path?.trimEnd('/') ?: return null
        if (host == "open.spotify.com") {
            val id = path.removePrefix("/track/")
            return if (path.startsWith("/track/") && spotifyId.matches(id)) {
                MusicLink(MusicProvider.SPOTIFY, "https://open.spotify.com/track/$id")
            } else null
        }

        val videoId = when {
            host == "music.youtube.com" || host == "www.youtube.com" ||
                host == "youtube.com" || host == "m.youtube.com" -> {
                if (path != "/watch") return null
                queryParameter(uri.rawQuery, "v")
            }
            host == "youtu.be" -> path.removePrefix("/").takeIf { path.startsWith("/") }
            else -> null
        }
        return videoId?.takeIf(youtubeId::matches)?.let {
            MusicLink(MusicProvider.YOUTUBE_MUSIC, "https://music.youtube.com/watch?v=$it")
        }
    }

    private fun queryParameter(rawQuery: String?, name: String): String? = rawQuery
        ?.split('&')
        ?.firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            if (key == name && '=' in part) {
                runCatching { URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrNull()
            } else null
        }
}
