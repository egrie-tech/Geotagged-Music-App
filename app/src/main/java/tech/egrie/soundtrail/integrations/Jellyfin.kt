package tech.egrie.soundtrail.integrations

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** The client identity Soundtrail presents to a Jellyfin server (required by its API). */
const val JELLYFIN_CLIENT = "Soundtrail"
const val JELLYFIN_DEVICE = "Android"
const val JELLYFIN_VERSION = "1.0.0"

class JellyfinException(message: String) : Exception(message)

/** What `GET /System/Info/Public` answers before anyone has signed in. */
data class JellyfinServerInfo(val name: String, val version: String, val id: String)

/** A signed-in session. The token and user id are what every later call needs. */
data class JellyfinSession(
    val accessToken: String,
    val userId: String,
    val userName: String,
    val serverId: String?
)

/** One song from the server's music library, ready to pin. */
data class JellyfinTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

/** The stream URLs and header a player needs for one item. */
data class JellyfinPlayback(val urls: List<String>, val authorization: String)

/**
 * Jellyfin songs are stored as item references (`jellyfin:item:<id>`), never as server URLs.
 * The address, the user, and the access token stay in the connection settings, so a pin, a
 * playlist, or a shared backup can never leak the server address or a token — and the same
 * song keeps playing after the server moves to another host or port.
 */
internal object JellyfinRef {
    private val idPattern = Regex("[A-Za-z0-9-]{16,64}")

    fun isItemId(value: String): Boolean = idPattern.matches(value)

    /** The link shape Soundtrail stores for a server item; null when the id is not plausible. */
    fun link(rawItemId: String): MusicLink? = rawItemId.trim()
        .takeIf(::isItemId)
        ?.let { MusicLink(MusicProvider.JELLYFIN, "jellyfin:item:$it") }

    /** Reads a stored link back into an item id; anything else is refused. */
    fun itemId(value: String): String? {
        val trimmed = value.trim()
        val prefix = "jellyfin:item:"
        if (!trimmed.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
        return trimmed.substring(prefix.length).takeIf(::isItemId)
    }

    /** A plain link to the item in Jellyfin's own web client, for sharing or opening. */
    fun webUrl(serverUrl: String, rawItemId: String): String? = rawItemId.trim()
        .takeIf(::isItemId)
        ?.let { "$serverUrl/web/index.html#!/details?id=$it" }
}

/**
 * The Jellyfin REST shapes Soundtrail uses, kept free of Android and networking so the
 * request building and response parsing can be unit tested. Everything here is read-only:
 * library search, playback info, and sign-out.
 */
internal object JellyfinApi {
    /** Addresses are user-typed, so accept "host:port" too and refuse anything unusable. */
    fun normalizeServerUrl(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return null
        val withScheme = if ("://" in value) value else "http://$value"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (host.isBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (host.any { it.isWhitespace() } || uri.path.orEmpty().any { it.isWhitespace() }) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        // A base path behind a reverse proxy is fine; escaping it never is.
        val path = uri.path.orEmpty().trimEnd('/')
        if (path.split('/').any { it == "." || it == ".." }) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://${host.lowercase()}$port$path"
    }

    /**
     * Jellyfin wants its own header format. Newer servers reject the legacy
     * X-Emby-Authorization spelling, so always send the Authorization form.
     */
    fun authorizationHeader(deviceId: String, token: String? = null): String = buildString {
        append("MediaBrowser ")
        append("Client=\"$JELLYFIN_CLIENT\", ")
        append("Device=\"$JELLYFIN_DEVICE\", ")
        append("DeviceId=\"$deviceId\", ")
        append("Version=\"$JELLYFIN_VERSION\"")
        if (!token.isNullOrBlank()) append(", Token=\"$token\"")
    }

    fun authenticationBody(username: String, password: String): String =
        JSONObject().put("Username", username).put("Pw", password).toString()

    fun publicInfo(body: String): JellyfinServerInfo? = runCatching {
        val json = JSONObject(body)
        val name = json.text("ServerName") ?: return null
        JellyfinServerInfo(
            name = name,
            version = json.text("Version").orEmpty(),
            id = json.text("Id").orEmpty()
        )
    }.getOrNull()

    fun session(body: String): JellyfinSession? = runCatching {
        val json = JSONObject(body)
        val token = json.text("AccessToken") ?: return null
        val user = json.optJSONObject("User") ?: return null
        JellyfinSession(
            accessToken = token,
            userId = user.text("Id") ?: return null,
            userName = user.text("Name").orEmpty(),
            serverId = json.text("ServerId")
        )
    }.getOrNull()

    /** Library search over the signed-in user's own items, audible items only. */
    fun searchPath(userId: String, query: String, limit: Int = 25): String {
        val encoded = URLEncoder.encode(query.trim().take(100), "UTF-8")
        return "/Users/${URLEncoder.encode(userId, "UTF-8")}/Items?recursive=true" +
            "&includeItemTypes=Audio&mediaTypes=Audio&searchTerm=$encoded&limit=$limit" +
            "&fields=Artists,AlbumArtist,Album,RunTimeTicks&enableTotalRecordCount=false"
    }

    fun items(body: String): List<JellyfinTrack> = runCatching {
        val items = JSONObject(body).optJSONArray("Items") ?: return emptyList()
        (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.text("Id") ?: return@mapNotNull null
            if (!JellyfinRef.isItemId(id)) return@mapNotNull null
            val title = item.text("Name") ?: return@mapNotNull null
            JellyfinTrack(
                id = id,
                title = title.take(120),
                artist = item.artistText(),
                album = item.text("Album").orEmpty(),
                durationMs = item.optLong("RunTimeTicks", 0L) / 10_000L
            )
        }.distinctBy(JellyfinTrack::id)
    }.getOrDefault(emptyList())

    /** Official, authenticated file-download endpoint for an item the user can already read. */
    fun downloadPath(rawItemId: String): String? = rawItemId.trim()
        .takeIf(JellyfinRef::isItemId)
        ?.let { "/Items/$it/Download" }

    /**
     * Direct play first — the server hands over the stored file untouched — then a transcode
     * fallback to a progressive MP3 stream for formats this device's decoder cannot open.
     */
    fun streamPaths(serverUrl: String, rawItemId: String, userId: String, deviceId: String): List<String> {
        val itemId = rawItemId.trim()
        if (serverUrl.isBlank() || !JellyfinRef.isItemId(itemId)) return emptyList()
        val common = "userId=${encode(userId)}&deviceId=${encode(deviceId)}"
        return listOf(
            "$serverUrl/Audio/$itemId/universal?static=true&$common",
            "$serverUrl/Audio/$itemId/universal?static=false&$common" +
                "&container=mp3&audioCodec=mp3&transcodingContainer=mp3" +
                "&transcodingProtocol=http&maxStreamingBitrate=256000"
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Android and JVM org.json builds disagree on how JSON null reads back through optString;
     * this treats blank and literal "null" as absent on both.
     */
    private fun JSONObject.text(name: String): String? = optString(name)
        .takeIf { it.isNotBlank() && it != "null" }

    /** Jellyfin puts the performers in `Artists`, with `AlbumArtist` as a fallback. */
    private fun JSONObject.artistText(): String {
        val artists = optJSONArray("Artists")
        val joined = if (artists == null) {
            ""
        } else {
            (0 until artists.length())
                .mapNotNull { artists.optString(it).takeIf { name -> name.isNotBlank() && name != "null" } }
                .joinToString(", ")
        }
        return joined.ifBlank { text("AlbumArtist").orEmpty() }.ifBlank { "Unknown artist" }
    }
}
