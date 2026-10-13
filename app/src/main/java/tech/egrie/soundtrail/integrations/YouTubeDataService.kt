package tech.egrie.soundtrail.integrations

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

private const val YOUTUBE_READONLY_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
private const val YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3"
private const val GOOGLE_ACCOUNT_TYPE = "com.google"

/**
 * Browser OAuth redirect for the Desktop-client sign-in. Google's documented custom-scheme
 * form is `scheme:/path` (single slash); the scheme must contain a period. No Google Play
 * services, standard microG account, or MicroG RE account is needed for this path.
 */
const val YOUTUBE_OAUTH_REDIRECT_URI = "app.soundtrail:/youtube-auth"
private const val GOOGLE_OAUTH_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
private const val GOOGLE_OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val GOOGLE_OAUTH_REVOKE_URL = "https://oauth2.googleapis.com/revoke"

open class YouTubeDataException(message: String) : Exception(message)

/**
 * Google approval for the read-only scope is missing for this account. The caller should run the
 * interactive Google Sign-In flow, which shows the consent screen, and then connect again.
 */
class YouTubeConsentRequiredException : YouTubeDataException(
    "Approve the YouTube read-only permission for this Google account, then connect again."
)

/** The Data API rejected the current access token. The OAuth path refreshes once before surfacing this. */
internal class YouTubeAuthExpiredException(message: String) : YouTubeDataException(message)

/**
 * Read-only YouTube Data API access with two mutually exclusive sign-in methods. The
 * Google-account method uses the accounts microG (or Google Play services) registers under the
 * `com.google` type and requests its OAuth token from GmsCore when needed, keeping only the
 * account name and type. The browser-OAuth method uses the user's own Desktop client with PKCE
 * and keeps Keystore-encrypted access and refresh tokens, needing no Google services at all —
 * that is the path that works alongside MicroG RE, which only serves patched apps.
 */
internal class YouTubeDataService(context: Context) {
    private val appContext = context.applicationContext
    private val settings = appContext.getSharedPreferences("soundtrail_settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(appContext)
    private val oauthMutex = Mutex()

    @Volatile private var cachedLibrary: CachedLibrary? = null

    private data class CachedPlaylist(val title: String, val body: String)
    private data class CachedLibrary(val cachedAt: Long, val playlists: List<CachedPlaylist>)

    val accountName: String? get() = secrets.get("youtube_data_account")?.takeIf(String::isNotBlank)

    /** Either sign-in method counts; the two are mutually exclusive and the newest wins. */
    val connected: Boolean get() = gmsConnected || oauthConnected

    private val gmsConnected: Boolean
        get() = accountName != null && secrets.get("youtube_data_account_name") != null

    val oauthConnected: Boolean get() = readOAuthToken() != null

    /** The user's own Desktop OAuth client ID, mirrored in the Connect tab's Client ID field. */
    val oauthClientId: String get() = settings.getString("youtube_oauth_client_id", "").orEmpty()

    /** Connects a microG/Google account chosen by the user or found on the device. */
    suspend fun connect(account: Account, label: String?): String {
        // Avoid showing a previous Google account if the user switched accounts.
        cachedLibrary = null
        // This Google-account connection replaces any browser-OAuth connection.
        secrets.remove("youtube_oauth_pending")
        secrets.remove("youtube_oauth_token")
        secrets.remove("youtube_data_account")
        secrets.remove("youtube_data_account_name")
        val token = accessToken(account)
        // Verify that the user's consent and YouTube Data API configuration work before saving.
        apiGet(
            "playlists",
            listOf("part" to "snippet", "mine" to "true", "maxResults" to "1"),
            token
        )
        val name = label?.takeIf(String::isNotBlank)
            ?: account.name.takeIf(String::isNotBlank)
            ?: "YouTube account"
        secrets.put("youtube_data_account_name", account.name)
        secrets.put("youtube_data_account_type", account.type)
        secrets.put("youtube_data_account", name)
        return name
    }

    /**
     * Clears both sign-in methods and asks Google services to revoke the Google-account grant.
     * Returns the previous browser-OAuth access token, if any, so the caller can revoke it too.
     */
    fun disconnect(): String? {
        cachedLibrary = null
        secrets.remove("youtube_oauth_pending")
        val oauthAccess = readOAuthToken()?.access
        secrets.remove("youtube_oauth_token")
        secrets.remove("youtube_data_account")
        secrets.remove("youtube_data_account_name")
        secrets.remove("youtube_data_account_type")
        runCatching { GoogleSignIn.getClient(appContext, signInOptions()).revokeAccess() }
        return oauthAccess
    }

    /** Asks Google to revoke a browser-OAuth token. Failures are ignored; local data is already gone. */
    suspend fun revokeOAuthToken(token: String) {
        runCatching {
            httpRequest("$GOOGLE_OAUTH_REVOKE_URL?token=${URLEncoder.encode(token, "UTF-8")}", "POST")
        }
    }

    // ----- Browser OAuth: Desktop client, PKCE, no Google services required -----

    /**
     * Starts the browser sign-in with the user's own Desktop OAuth client ID. MicroG RE only
     * serves Morphe/ReVanced patched apps, so this is the path that works for MicroG RE users,
     * de-Googled devices, and anyone without a device Google account — it needs only a browser.
     */
    fun beginOAuthSignIn(inputId: String): Uri {
        val id = YouTubeOAuth.checkClientId(inputId)
        if (oauthClientId != id) {
            secrets.remove("youtube_oauth_token")
            settings.edit().putString("youtube_oauth_client_id", id).apply()
        }
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        secrets.put("youtube_oauth_pending", JSONObject().apply {
            put("state", state)
            put("verifier", verifier)
            put("clientId", id)
            put("createdAt", System.currentTimeMillis())
        }.toString())
        return Uri.parse(YouTubeOAuth.authorizationUrl(id, state, Pkce.challenge(verifier)))
    }

    fun cancelOAuthSignIn() = secrets.remove("youtube_oauth_pending")

    /**
     * Finishes the browser sign-in: validates the redirect, exchanges the code with the PKCE
     * verifier, confirms the read-only scope, and verifies the grant against the Data API
     * before saving anything. Returns the channel label shown on the Connect tab.
     */
    suspend fun finishOAuthSignIn(uri: Uri): String {
        val saved = secrets.get("youtube_oauth_pending")
            ?: throw YouTubeDataException("Start YouTube sign-in again.")
        // The authorization response can be consumed only once, even if it was denied.
        secrets.remove("youtube_oauth_pending")
        val pending = runCatching { JSONObject(saved) }.getOrNull()
            ?: throw YouTubeDataException("Start YouTube sign-in again.")
        val code = YouTubeOAuth.authorizationCode(uri.toString(), pending.optString("state"))
        val age = System.currentTimeMillis() - pending.optLong("createdAt", -1L)
        if (age !in 0L..600_000L || oauthClientId != pending.optString("clientId")) {
            throw YouTubeDataException("YouTube sign-in expired or could not be verified. Try again.")
        }
        val response = httpRequest(
            GOOGLE_OAUTH_TOKEN_URL, "POST",
            body = form(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to YOUTUBE_OAUTH_REDIRECT_URI,
                "client_id" to oauthClientId,
                "code_verifier" to pending.optString("verifier")
            )
        )
        if (response.code !in 200..299) throw response.tokenError()
        val parsed = YouTubeOAuth.parseToken(response.body)
        YouTubeOAuth.checkScope(parsed.scope)
        val refresh = parsed.refresh ?: throw YouTubeDataException(
            "Google did not return a refresh token. Remove Soundtrail's access at " +
                "myaccount.google.com/permissions and connect again."
        )
        val token = YouTubeOAuthToken(
            access = parsed.access,
            refresh = refresh,
            clientId = oauthClientId,
            expiresAt = System.currentTimeMillis() + parsed.expiresInSec.coerceAtLeast(60L) * 1_000L
        )
        // Verify that the grant and the YouTube Data API configuration work before saving.
        apiGet(
            "playlists",
            listOf("part" to "snippet", "mine" to "true", "maxResults" to "1"),
            token.access
        )
        saveOAuthToken(token)
        // This browser connection replaces any Google-account connection.
        secrets.remove("youtube_data_account_name")
        secrets.remove("youtube_data_account_type")
        cachedLibrary = null
        val label = channelLabel(token.access)
        secrets.put("youtube_data_account", label)
        return label
    }

    /** The channel title shown as the account label; plain "YouTube account" when unavailable. */
    private suspend fun channelLabel(token: String): String = runCatching {
        val json = JSONObject(
            apiGet("channels", listOf("part" to "snippet", "mine" to "true", "maxResults" to "1"), token)
        )
        json.optJSONArray("items")?.optJSONObject(0)
            ?.optJSONObject("snippet")?.optString("title")
            ?.takeIf(String::isNotBlank)?.take(80)
    }.getOrNull() ?: "YouTube account"

    private suspend fun oauthAccessToken(forceRefresh: Boolean = false): String = oauthMutex.withLock {
        val old = readOAuthToken()
            ?: throw YouTubeDataException("Connect your YouTube account to search your playlists.")
        if (!forceRefresh && old.expiresAt > System.currentTimeMillis() + 60_000) {
            return@withLock old.access
        }
        val response = httpRequest(
            GOOGLE_OAUTH_TOKEN_URL, "POST",
            body = form(
                "grant_type" to "refresh_token",
                "refresh_token" to old.refresh,
                "client_id" to old.clientId
            )
        )
        if (response.code !in 200..299) {
            if (response.code == 400 || response.code == 401) {
                secrets.remove("youtube_oauth_token")
                secrets.remove("youtube_data_account")
                throw YouTubeDataException("YouTube session expired. Connect again.")
            }
            throw response.tokenError()
        }
        val parsed = YouTubeOAuth.parseToken(response.body)
        val refreshed = old.copy(
            access = parsed.access,
            refresh = parsed.refresh ?: old.refresh,
            expiresAt = System.currentTimeMillis() + parsed.expiresInSec.coerceAtLeast(60L) * 1_000L
        )
        saveOAuthToken(refreshed)
        refreshed.access
    }

    private fun readOAuthToken(): YouTubeOAuthToken? {
        val raw = secrets.get("youtube_oauth_token") ?: return null
        return runCatching {
            val json = JSONObject(raw)
            YouTubeOAuthToken(
                json.getString("access"), json.getString("refresh"),
                json.getString("clientId"), json.getLong("expiresAt")
            )
        }.getOrElse {
            secrets.remove("youtube_oauth_token")
            null
        }
    }

    private fun saveOAuthToken(token: YouTubeOAuthToken) {
        secrets.put("youtube_oauth_token", JSONObject().apply {
            put("access", token.access)
            put("refresh", token.refresh)
            put("clientId", token.clientId)
            put("expiresAt", token.expiresAt)
        }.toString())
    }

    private fun HttpResponse.tokenError(): YouTubeDataException {
        val detail = detail()
        val message = when {
            detail.contains("redirect_uri", ignoreCase = true) ->
                "Google rejected the sign-in redirect. Use a Desktop (not Android or Web) OAuth client ID."
            detail.contains("invalid_client", ignoreCase = true) ->
                "Google rejected this Client ID. Use the Desktop OAuth client ID from your Cloud project."
            code in 400..499 -> detail.ifBlank {
                "YouTube sign-in was rejected (HTTP $code). Check the Client ID and try again."
            }
            else -> detail.ifBlank { "YouTube sign-in is unavailable (HTTP $code). Try again later." }
        }
        return YouTubeDataException(message)
    }

    /**
     * Searches videos in the first page of the user's own playlists. The public YouTube Data API
     * does not expose the complete YouTube Music library (saved songs/albums/artists), so this is
     * intentionally a playlist-video search, not a claim to mirror the Music app's library.
     */
    suspend fun searchTracks(query: String): List<TrackResult> {
        if (query.isBlank()) return emptyList()
        if (!connected) throw YouTubeDataException("Connect your YouTube account to search your playlists.")

        // Only one method is ever connected; the browser-OAuth token refreshes itself.
        val useOAuth = oauthConnected
        try {
            return searchWithToken(query, if (useOAuth) oauthAccessToken() else accessToken())
        } catch (e: YouTubeAuthExpiredException) {
            // A token can be revoked before its reported expiry. Refresh once, never loop.
            if (!useOAuth) throw e
            return searchWithToken(query, oauthAccessToken(forceRefresh = true))
        }
    }

    private suspend fun searchWithToken(query: String, token: String): List<TrackResult> {
        val now = System.currentTimeMillis()
        val snapshot = cachedLibrary?.takeIf {
            now >= it.cachedAt && now - it.cachedAt < LIBRARY_CACHE_MS
        } ?: run {
            val playlists = YouTubeApiParser.playlists(
                apiGet("playlists", listOf("part" to "snippet", "mine" to "true", "maxResults" to "50"), token)
            ).take(MAX_PLAYLISTS_TO_SEARCH)
            val semaphore = Semaphore(5)
            val items = coroutineScope {
                playlists.map { playlist ->
                    async {
                        semaphore.withPermit {
                            val body = apiGet(
                                "playlistItems",
                                listOf(
                                    "part" to "snippet,contentDetails",
                                    "playlistId" to playlist.id,
                                    "maxResults" to "50"
                                ),
                                token
                            )
                            CachedPlaylist(playlist.title, body)
                        }
                    }
                }.awaitAll()
            }
            CachedLibrary(System.currentTimeMillis(), items).also { cachedLibrary = it }
        }

        return snapshot.playlists.flatMap { playlist ->
            YouTubeApiParser.matchingTracks(playlist.body, query, playlist.title)
        }.distinctBy { it.url }.take(MAX_RESULTS)
    }

    private suspend fun accessToken(account: Account? = null): String = withContext(Dispatchers.IO) {
        val selectedAccount = account ?: storedAccount()
            ?: throw YouTubeDataException("Your Google account is no longer connected. Connect again.")
        try {
            GoogleAuthUtil.getToken(appContext, selectedAccount, "oauth2:$YOUTUBE_READONLY_SCOPE")
                .takeIf(String::isNotBlank)
                ?: throw YouTubeDataException("Google services returned an empty YouTube token.")
        } catch (e: UserRecoverableAuthException) {
            throw YouTubeConsentRequiredException()
        } catch (e: GoogleAuthException) {
            throw YouTubeDataException(
                "Google services could not authorize YouTube read-only access. Check the microG/Google account setup."
            )
        } catch (e: IOException) {
            throw YouTubeDataException("Could not reach Google services. Check your connection and try again.")
        }
    }

    private fun storedAccount(): Account? {
        val name = secrets.get("youtube_data_account_name")?.takeIf(String::isNotBlank) ?: return null
        val type = secrets.get("youtube_data_account_type")?.takeIf(String::isNotBlank) ?: GOOGLE_ACCOUNT_TYPE
        return Account(name, type)
    }

    private suspend fun apiGet(
        resource: String,
        parameters: List<Pair<String, String>>,
        token: String
    ): String {
        val address = "$YOUTUBE_API_BASE/$resource?${form(*parameters.toTypedArray())}"
        val response = httpRequest(address, authorization = "Bearer $token")
        if (response.code !in 200..299) {
            if (response.code == 401) {
                throw YouTubeAuthExpiredException(
                    "YouTube authorization expired. Disconnect and connect the account again."
                )
            }
            val detail = response.detail()
            val message = when (response.code) {
                403 -> detail.ifBlank {
                    "YouTube denied access. Enable YouTube Data API v3 in your Google Cloud project and approve read-only access."
                }
                429 -> "YouTube is rate-limiting requests. Try again later."
                else -> detail.ifBlank { "YouTube Data API is unavailable (HTTP ${response.code})." }
            }
            throw YouTubeDataException(message)
        }
        return response.body
    }

    companion object {
        private const val MAX_PLAYLISTS_TO_SEARCH = 50
        private const val MAX_RESULTS = 20
        private const val LIBRARY_CACHE_MS = 120_000L

        /**
         * Google-type accounts registered with the device's account manager (microG's account
         * store when microG is the Google services provider). Requires the GET_ACCOUNTS permission.
         */
        fun deviceGoogleAccounts(context: Context): List<Account> {
            if (context.checkSelfPermission(android.Manifest.permission.GET_ACCOUNTS) !=
                PackageManager.PERMISSION_GRANTED
            ) return emptyList()
            return AccountManager.get(context).getAccountsByType(GOOGLE_ACCOUNT_TYPE).toList()
        }

        fun signInOptions(): GoogleSignInOptions = GoogleSignInOptions.Builder(
            GoogleSignInOptions.DEFAULT_SIGN_IN
        )
            .requestEmail()
            .requestScopes(Scope(YOUTUBE_READONLY_SCOPE))
            .build()
    }
}

internal data class YouTubePlaylistRef(val id: String, val title: String)

/** Small pure parsers kept separate so the API response rules can be unit tested. */
internal object YouTubeApiParser {
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    fun playlists(body: String): List<YouTubePlaylistRef> {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val title = item.optJSONObject("snippet")?.optString("title").orEmpty()
            if (id.isBlank() || title.isBlank()) null else YouTubePlaylistRef(id, title.take(120))
        }
    }

    fun matchingTracks(body: String, query: String, playlistTitle: String): List<TrackResult> {
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val snippet = item.optJSONObject("snippet") ?: return@mapNotNull null
            val title = snippet.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (title.equals("Deleted video", ignoreCase = true) ||
                title.equals("Private video", ignoreCase = true)
            ) return@mapNotNull null

            val resource = snippet.optJSONObject("resourceId")
            val id = item.optJSONObject("contentDetails")?.optString("videoId")
                ?.takeIf(String::isNotBlank)
                ?: resource?.optString("videoId")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            if (!videoIdPattern.matches(id)) return@mapNotNull null

            val artist = snippet.optString("videoOwnerChannelTitle").ifBlank {
                snippet.optString("channelTitle")
            }.ifBlank { "Unknown artist" }.take(120)
            val searchable = "$title $artist $playlistTitle"
            if (terms.any { !searchable.contains(it, ignoreCase = true) }) return@mapNotNull null

            TrackResult(
                title = title.take(120),
                artist = artist,
                url = "https://music.youtube.com/watch?v=$id",
                provider = MusicProvider.YOUTUBE_MUSIC
            )
        }.distinctBy { it.url }
    }
}

private data class YouTubeOAuthToken(
    val access: String, val refresh: String, val clientId: String, val expiresAt: Long
)

/** Pure browser-OAuth pieces (URL building, redirect checks, token parsing) kept testable. */
internal object YouTubeOAuth {
    private val clientIdPattern = Regex("^[0-9]{5,}-[A-Za-z0-9_-]{5,}\\.apps\\.googleusercontent\\.com$")

    /** A Google Cloud OAuth client ID always ends in `.apps.googleusercontent.com`. */
    fun checkClientId(input: String): String {
        val id = input.trim()
        if (!clientIdPattern.matches(id)) {
            throw YouTubeDataException(
                "Enter the Client ID from your Desktop OAuth client " +
                    "(123…-abc….apps.googleusercontent.com)."
            )
        }
        return id
    }

    /** Installed-app authorization URL: PKCE, offline access, and explicit consent for a refresh token. */
    fun authorizationUrl(clientId: String, state: String, challenge: String): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to YOUTUBE_OAUTH_REDIRECT_URI,
            "scope" to YOUTUBE_READONLY_SCOPE,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "access_type" to "offline",
            "prompt" to "consent"
        ).joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$GOOGLE_OAUTH_AUTH_URL?$params"
    }

    /**
     * Validates the browser redirect and returns the authorization code. Accepts Google's
     * documented `scheme:/path` form and the equivalent `scheme://host` form.
     */
    fun authorizationCode(redirect: String, expectedState: String): String {
        val uri = runCatching { java.net.URI(redirect) }.getOrNull()
            ?: throw YouTubeDataException("Unexpected YouTube sign-in response.")
        val targetOk = uri.scheme == "app.soundtrail" &&
            uri.port == -1 &&
            uri.fragment == null &&
            uri.userInfo == null &&
            (uri.rawAuthority == null || uri.rawAuthority == "youtube-auth") &&
            ((uri.host == "youtube-auth" && (uri.path.isNullOrEmpty() || uri.path == "/")) ||
                (uri.host == null && uri.path == "/youtube-auth"))
        if (!targetOk) throw YouTubeDataException("Unexpected YouTube sign-in response.")
        val params = runCatching {
            uri.rawQuery.orEmpty().split("&").mapNotNull { part ->
                val separator = part.indexOf("=")
                if (separator <= 0) null else
                    URLDecoder.decode(part.substring(0, separator), "UTF-8") to
                        URLDecoder.decode(part.substring(separator + 1), "UTF-8")
            }.toMap()
        }.getOrNull() ?: throw YouTubeDataException("Unexpected YouTube sign-in response.")
        params["error"]?.let { error ->
            throw YouTubeDataException(
                if (error == "access_denied") "YouTube sign-in was cancelled."
                else "YouTube sign-in failed ($error)."
            )
        }
        if (expectedState.isBlank() || params["state"] != expectedState) {
            throw YouTubeDataException("YouTube sign-in expired or could not be verified. Try again.")
        }
        return params["code"]?.takeIf(String::isNotBlank)
            ?: throw YouTubeDataException("YouTube did not return an authorization code.")
    }

    internal data class ParsedToken(
        val access: String, val refresh: String?, val expiresInSec: Long, val scope: String?
    )

    /** Parses a token-endpoint response; throws a user-facing error when it carries no token. */
    fun parseToken(body: String): ParsedToken {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw YouTubeDataException("YouTube sign-in returned an unreadable response.")
        val access = json.optString("access_token")
        if (access.isBlank()) {
            val detail = json.optString("error_description").ifBlank { json.optString("error") }
            throw YouTubeDataException(
                detail.ifBlank { "YouTube sign-in returned an unreadable response." }
            )
        }
        return ParsedToken(
            access = access,
            refresh = json.optString("refresh_token").ifBlank { null },
            expiresInSec = json.optLong("expires_in", 0L),
            scope = json.optString("scope").ifBlank { null }
        )
    }

    /** Fails when Google reports granted scopes that omit the read-only permission. */
    fun checkScope(scope: String?) {
        if (scope != null && scope.split(Regex("\\s+")).none { it == YOUTUBE_READONLY_SCOPE }) {
            throw YouTubeDataException("Approve the YouTube read-only permission to use playlist search.")
        }
    }
}
