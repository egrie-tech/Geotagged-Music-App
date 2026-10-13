package tech.egrie.soundtrail.integrations

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

private const val YOUTUBE_READONLY_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
private const val YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3"
private const val GOOGLE_ACCOUNT_TYPE = "com.google"

open class YouTubeDataException(message: String) : Exception(message)

/**
 * Google approval for the read-only scope is missing for this account. The caller should run the
 * interactive Google Sign-In flow, which shows the consent screen, and then connect again.
 */
class YouTubeConsentRequiredException : YouTubeDataException(
    "Approve the YouTube read-only permission for this Google account, then connect again."
)

/**
 * Read-only YouTube Data API access through microG (or Google Play services) account storage.
 * The account is chosen from the accounts microG registers under the `com.google` type; the
 * OAuth token is requested from GmsCore when needed. This class never reads microG's private
 * database and stores no access or refresh token; it keeps only the account name and type.
 */
internal class YouTubeDataService(context: Context) {
    private val appContext = context.applicationContext
    private val secrets = SecretStore(appContext)

    @Volatile private var cachedLibrary: CachedLibrary? = null

    private data class CachedPlaylist(val title: String, val body: String)
    private data class CachedLibrary(val cachedAt: Long, val playlists: List<CachedPlaylist>)

    val accountName: String? get() = secrets.get("youtube_data_account")?.takeIf(String::isNotBlank)
    val connected: Boolean get() = accountName != null && secrets.get("youtube_data_account_name") != null

    /** Connects a microG/Google account chosen by the user or found on the device. */
    suspend fun connect(account: Account, label: String?): String {
        // Avoid showing a previous Google account if the user switched accounts.
        cachedLibrary = null
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

    /** Clears Soundtrail's account label and revokes this app's Google grant on a best-effort basis. */
    fun disconnect() {
        cachedLibrary = null
        secrets.remove("youtube_data_account")
        secrets.remove("youtube_data_account_name")
        secrets.remove("youtube_data_account_type")
        runCatching { GoogleSignIn.getClient(appContext, signInOptions()).revokeAccess() }
    }

    /**
     * Searches videos in the first page of the user's own playlists. The public YouTube Data API
     * does not expose the complete YouTube Music library (saved songs/albums/artists), so this is
     * intentionally a playlist-video search, not a claim to mirror the Music app's library.
     */
    suspend fun searchTracks(query: String): List<TrackResult> {
        if (query.isBlank()) return emptyList()
        if (!connected) throw YouTubeDataException("Connect a Google account to search your YouTube playlists.")

        val token = accessToken()
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
            val detail = response.detail()
            val message = when (response.code) {
                401 -> "YouTube authorization expired. Disconnect and connect the account again."
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
