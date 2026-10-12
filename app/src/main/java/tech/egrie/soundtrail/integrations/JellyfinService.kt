package tech.egrie.soundtrail.integrations

import android.content.Context
import java.util.UUID

/**
 * The client half of a Jellyfin connection: sign in against the user's own server, search its
 * music library, and resolve stream URLs for playback.
 *
 * Only a username and a password are ever sent, and only once, to the address the user typed.
 * The password is never stored; the server keeps an access token, which lives in
 * Android Keystore-encrypted storage on this device. Everything here is read-only — Soundtrail
 * never writes to, renames, or deletes anything in the user's library.
 */
class JellyfinService(context: Context) {
    private val settings = context.getSharedPreferences("soundtrail_settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    val serverUrl: String get() = settings.getString("jellyfin_server", "").orEmpty()
    val userName: String get() = settings.getString("jellyfin_user", "").orEmpty()
    val serverName: String get() = settings.getString("jellyfin_server_name", "").orEmpty()

    private val userId: String get() = settings.getString("jellyfin_user_id", "").orEmpty()
    private val token: String get() = secrets.get("jellyfin_token").orEmpty()

    val connected: Boolean
        get() = serverUrl.isNotBlank() && userId.isNotBlank() && token.isNotBlank()

    /** A stable per-install device id, so the server can recognise (and revoke) this app. */
    private val deviceId: String
        get() {
            settings.getString("jellyfin_device_id", "")?.takeIf(String::isNotBlank)?.let { return it }
            val generated = UUID.randomUUID().toString()
            settings.edit().putString("jellyfin_device_id", generated).apply()
            return generated
        }

    /** Signs in and stores the resulting session; throws [JellyfinException] with a usable message. */
    suspend fun connect(rawServer: String, rawUser: String, rawPassword: String): String {
        val server = JellyfinApi.normalizeServerUrl(rawServer)
            ?: throw JellyfinException("Enter your Jellyfin address, like http://192.168.1.20:8096.")
        val user = rawUser.trim()
        if (user.isEmpty()) throw JellyfinException("Enter your Jellyfin username.")
        if (rawPassword.isEmpty()) throw JellyfinException("Enter your Jellyfin password.")

        // Ask the public endpoint first: it proves the address is a Jellyfin server and names it.
        val info = runCatching { publicInfo(server) }.getOrNull()
            ?: throw JellyfinException(
                "No Jellyfin server answered at $server. Check the address, the port, " +
                    "and that this device can reach your server."
            )

        val response = httpRequest(
            "$server/Users/AuthenticateByName", "POST",
            body = JellyfinApi.authenticationBody(user, rawPassword),
            authorization = JellyfinApi.authorizationHeader(deviceId),
            contentType = "application/json; charset=UTF-8"
        )
        if (response.code !in 200..299) throw response.error()
        val session = JellyfinApi.session(response.body)
            ?: throw JellyfinException("Jellyfin accepted the sign-in but returned no usable session.")

        // Only now replace stored settings: a failed attempt must not drop a working session.
        disconnect()
        settings.edit()
            .putString("jellyfin_server", server)
            .putString("jellyfin_user", user)
            .putString("jellyfin_server_name", info.name)
            .putString("jellyfin_user_id", session.userId)
            .apply()
        secrets.put("jellyfin_token", session.accessToken)
        return if (session.userName.isNotBlank()) session.userName else user
    }

    /** Clears the local session and returns the token for a best-effort server-side sign-out. */
    fun disconnect(): String? {
        val previous = secrets.get("jellyfin_token")
        secrets.remove("jellyfin_token")
        settings.edit().remove("jellyfin_user_id").apply()
        return previous
    }

    /** Ends the session on the server too. Best effort: local tokens are already gone. */
    suspend fun revokeAccessToken(server: String, previousToken: String) {
        runCatching {
            httpRequest(
                "$server/Sessions/Logout", "POST",
                authorization = JellyfinApi.authorizationHeader(deviceId, previousToken)
            )
        }
    }

    suspend fun searchTracks(query: String): List<TrackResult> {
        if (query.isBlank()) return emptyList()
        if (!connected) throw JellyfinException("Connect your Jellyfin server to search its library.")
        val response = apiGet(JellyfinApi.searchPath(userId, query))
        if (response.code !in 200..299) throw response.error()
        return JellyfinApi.items(response.body).mapNotNull { track ->
            // The pickable link is the item reference; the server address is added at play time.
            JellyfinRef.link(track.id)?.let {
                TrackResult(title = track.title, artist = track.artist, url = it.url, provider = it.provider)
            }
        }
    }

    /** Stream URLs plus the header the player must send, or null when nothing is connected. */
    fun playback(rawItemId: String): JellyfinPlayback? {
        if (!connected) return null
        val urls = JellyfinApi.streamPaths(serverUrl, rawItemId, userId, deviceId)
        if (urls.isEmpty()) return null
        return JellyfinPlayback(urls, JellyfinApi.authorizationHeader(deviceId, token))
    }

    private suspend fun publicInfo(server: String): JellyfinServerInfo? {
        val response = httpRequest("$server/System/Info/Public")
        if (response.code !in 200..299) return null
        return JellyfinApi.publicInfo(response.body)
    }

    private suspend fun apiGet(path: String): HttpResponse {
        val response = httpRequest(
            serverUrl + path, authorization = JellyfinApi.authorizationHeader(deviceId, token)
        )
        if (response.code == 401) {
            // A revoked or expired token is useless; drop it so the UI stops claiming a session.
            disconnect()
            throw JellyfinException("Your Jellyfin session ended. Connect again on the Connect tab.")
        }
        return response
    }

    private fun HttpResponse.error(): JellyfinException {
        // Http.kt's shared detail() already reads the provider's own explanation, if any.
        val detail = detail()
        val fallback = when (code) {
            400 -> "Jellyfin rejected this request. Check the address and your account name."
            401, 403 -> "Jellyfin refused those credentials. Check your username and password."
            404 -> "That address answered, but not like a Jellyfin server. Check the URL and port."
            else -> "Your Jellyfin server answered with HTTP $code. Try again shortly."
        }
        return JellyfinException(detail.takeIf { it.isNotBlank() && code != 403 } ?: fallback)
    }
}
