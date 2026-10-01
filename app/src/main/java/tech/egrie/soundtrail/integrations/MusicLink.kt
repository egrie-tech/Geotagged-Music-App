package tech.egrie.soundtrail.integrations

import java.net.URI
import java.net.URLDecoder

/** Only known song URLs are opened. Strip tracking parameters and refuse untrusted hosts. */
enum class MusicProvider(val label: String) {
    SPOTIFY("Spotify"), YOUTUBE_MUSIC("YouTube Music"), SOUNDCLOUD("SoundCloud")
}

data class MusicLink(val provider: MusicProvider, val url: String)

/** A search result from a connected provider, ready to be pinned. */
data class TrackResult(val title: String, val artist: String, val url: String, val provider: MusicProvider)

object MusicLinkParser {
    private val spotifyId = Regex("[A-Za-z0-9]{22}")
    private val youtubeId = Regex("[A-Za-z0-9_-]{11}")
    private val soundcloudSlug = Regex("[A-Za-z0-9_-]{1,100}")
    private val soundcloudSecret = Regex("s-[A-Za-z0-9]{1,64}")
    private val soundcloudShort = Regex("[A-Za-z0-9_-]{4,64}")
    private val sharedUrl = Regex("https?://[^\\s<>]+|spotify:track:[A-Za-z0-9]{22}", RegexOption.IGNORE_CASE)

    // Top-level SoundCloud site sections that can never be an artist permalink.
    private val soundcloudReserved = setOf(
        "you", "settings", "notifications", "messages", "discover", "search", "upload",
        "pages", "feed", "charts", "popular", "tags", "tracks", "people", "playlists",
        "albums", "groups", "stations", "library", "history", "jobs", "legal", "imprint",
        "privacy", "terms-of-use", "cookies", "about", "help", "press", "mobile", "apps",
        "developer", "developers", "api", "oauth", "login", "signin", "signup", "logout"
    )

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

        // SoundCloud permalinks are slug-based, so they are canonicalized, not re-derived.
        if (host == "soundcloud.com" || host == "www.soundcloud.com" || host == "m.soundcloud.com") {
            val segments = path.split('/').filter(String::isNotEmpty)
            if (segments.size !in 2..3 || segments[0].lowercase() in soundcloudReserved ||
                !soundcloudSlug.matches(segments[0]) || !soundcloudSlug.matches(segments[1]) ||
                (segments.size == 3 && !soundcloudSecret.matches(segments[2]))
            ) return null
            val secretTrail = if (segments.size == 3) "/${segments[2]}" else ""
            return MusicLink(
                MusicProvider.SOUNDCLOUD,
                "https://soundcloud.com/${segments[0]}/${segments[1]}$secretTrail"
            )
        }

        // The share shorteners the SoundCloud apps produce; they redirect to the permalink.
        if (host == "on.soundcloud.com" || host == "soundcloud.app.goo.gl") {
            val token = path.removePrefix("/")
            return if (path.startsWith("/") && soundcloudShort.matches(token)) {
                MusicLink(MusicProvider.SOUNDCLOUD, "https://on.soundcloud.com/$token")
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
