package tech.egrie.soundtrail.integrations

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder

const val SPOTIFY_REDIRECT_URI = "app.soundtrail://spotify-auth"

class SpotifyException(message: String) : Exception(message)

/** Spotify's public-client Authorization Code + PKCE flow and a small, read-only Web API client. */
class SpotifyService(context: Context) {
    private val settings = context.getSharedPreferences("soundtrail_settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val refreshMutex = Mutex()
    private val idPattern = Regex("[a-fA-F0-9]{32}")

    val clientId: String get() = settings.getString("spotify_client_id", "").orEmpty()
    val connected: Boolean get() = readToken() != null

    fun beginSignIn(inputId: String): Uri {
        val id = inputId.trim()
        if (!idPattern.matches(id)) {
            throw SpotifyException("Enter the 32-character Client ID from your Spotify Developer app.")
        }
        if (clientId != id) {
            disconnect()
            settings.edit().putString("spotify_client_id", id).apply()
        }
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        secrets.put("pending", JSONObject().apply {
            put("state", state)
            put("verifier", verifier)
            put("clientId", id)
            put("createdAt", System.currentTimeMillis())
        }.toString())
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("redirect_uri", SPOTIFY_REDIRECT_URI)
            .appendQueryParameter("scope", "user-read-private")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .build()
    }

    fun cancelSignIn() = secrets.remove("pending")

    suspend fun finishSignIn(uri: Uri) {
        if (uri.scheme != "app.soundtrail" || uri.host != "spotify-auth" ||
            !uri.path.isNullOrEmpty() || uri.port != -1 || uri.fragment != null
        ) throw SpotifyException("Unexpected Spotify sign-in response.")

        val saved = secrets.get("pending") ?: throw SpotifyException("Start Spotify sign-in again.")
        // The authorization response can be consumed only once, even if it was denied.
        secrets.remove("pending")
        val pending = JSONObject(saved)
        val age = System.currentTimeMillis() - pending.getLong("createdAt")
        if (age !in 0L..600_000L || uri.getQueryParameter("state") != pending.getString("state") ||
            clientId != pending.getString("clientId")
        ) throw SpotifyException("Spotify sign-in expired or could not be verified. Try again.")

        if (uri.getQueryParameter("error") != null) {
            throw SpotifyException("Spotify sign-in was cancelled.")
        }
        val code = uri.getQueryParameter("code")
            ?: throw SpotifyException("Spotify did not return an authorization code.")
        val response = httpRequest(
            "https://accounts.spotify.com/api/token", "POST",
            body = form(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to SPOTIFY_REDIRECT_URI,
                "client_id" to clientId,
                "code_verifier" to pending.getString("verifier")
            )
        )
        if (response.code !in 200..299) throw response.error()
        saveToken(parseToken(response.body, clientId))
    }

    fun disconnect() {
        secrets.remove("pending")
        secrets.remove("token")
    }

    suspend fun profileName(): String {
        val json = JSONObject(apiGet("me"))
        return json.optString("display_name").takeIf { it.isNotBlank() && it != "null" }
            ?: json.optString("id", "Spotify listener")
    }

    suspend fun searchTracks(query: String): List<TrackResult> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim().take(100), "UTF-8")
        val json = JSONObject(apiGet("search?type=track&limit=10&q=$encoded"))
        val items = json.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val track = items.optJSONObject(index) ?: return@mapNotNull null
            val id = track.optString("id")
            if (!idPatternForTrack.matches(id)) return@mapNotNull null
            val artists = track.optJSONArray("artists")
            val artist = if (artists == null) "Unknown artist" else (0 until artists.length())
                .mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                .joinToString(", ").ifBlank { "Unknown artist" }
            TrackResult(
                title = track.optString("name", "Untitled").take(120),
                artist = artist,
                url = "https://open.spotify.com/track/$id",
                provider = MusicProvider.SPOTIFY
            )
        }
    }

    private suspend fun apiGet(path: String): String {
        val first = httpRequest("https://api.spotify.com/v1/$path", authorization = "Bearer ${accessToken()}")
        if (first.code in 200..299) return first.body
        if (first.code == 401) {
            // A token can be revoked before its reported expiry. Refresh once, never loop.
            val retry = httpRequest(
                "https://api.spotify.com/v1/$path", authorization = "Bearer ${accessToken(forceRefresh = true)}"
            )
            if (retry.code in 200..299) return retry.body
            throw retry.error()
        }
        throw first.error()
    }

    private suspend fun accessToken(forceRefresh: Boolean = false): String = refreshMutex.withLock {
        val old = readToken() ?: throw SpotifyException("Connect Spotify to search for tracks.")
        if (!forceRefresh && old.expiresAt > System.currentTimeMillis() + 60_000) {
            return@withLock old.access
        }
        val response = httpRequest(
            "https://accounts.spotify.com/api/token", "POST",
            body = form("grant_type" to "refresh_token", "refresh_token" to old.refresh, "client_id" to old.clientId)
        )
        if (response.code !in 200..299) {
            if (response.code == 400 || response.code == 401) {
                disconnect()
                throw SpotifyException("Spotify session expired. Connect again.")
            }
            throw response.error()
        }
        val refreshed = parseToken(response.body, old.clientId, old.refresh)
        saveToken(refreshed)
        refreshed.access
    }

    private fun readToken(): SpotifyToken? {
        val raw = secrets.get("token") ?: return null
        return runCatching {
            val json = JSONObject(raw)
            SpotifyToken(
                json.getString("access"), json.getString("refresh"),
                json.getString("clientId"), json.getLong("expiresAt")
            )
        }.getOrElse {
            secrets.remove("token")
            null
        }
    }

    private fun saveToken(token: SpotifyToken) {
        secrets.put("token", JSONObject().apply {
            put("access", token.access)
            put("refresh", token.refresh)
            put("clientId", token.clientId)
            put("expiresAt", token.expiresAt)
        }.toString())
    }

    private fun parseToken(body: String, id: String, previousRefresh: String? = null): SpotifyToken {
        val json = JSONObject(body)
        return SpotifyToken(
            access = json.getString("access_token"),
            refresh = json.optString("refresh_token").ifBlank {
                previousRefresh ?: throw SpotifyException("Spotify did not return a refresh token.")
            },
            clientId = id,
            expiresAt = System.currentTimeMillis() + json.getLong("expires_in") * 1_000L
        )
    }

    private data class SpotifyToken(
        val access: String, val refresh: String, val clientId: String, val expiresAt: Long
    )

    private fun HttpResponse.error(): SpotifyException {
        val detail = detail()
        val fallback = when (code) {
            400 -> "Spotify rejected this request. Check your Client ID and try again."
            401 -> "Spotify authorization failed. Reconnect your account."
            403 -> "Spotify denied access. Check your Developer app's allowed users and Premium requirements."
            429 -> "Spotify is rate-limiting requests. Try again later."
            else -> "Spotify is unavailable (HTTP $code). Try again later."
        }
        return SpotifyException(detail.takeIf { it.isNotBlank() && code != 403 } ?: fallback)
    }

    companion object {
        private val idPatternForTrack = Regex("[A-Za-z0-9]{22}")
    }
}
