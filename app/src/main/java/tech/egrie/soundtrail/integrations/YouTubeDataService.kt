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

/** One playlist item as pulled from the YouTube Data API, ready to turn into a Soundtrail entry. */
internal data class YouTubeImportedItem(
    val videoId: String,
    val title: String,
    val artist: String
)

/** One playlist as pulled from YouTube: stable YouTube playlist id, display title, all items. */
internal data class YouTubeImportedPlaylist(
    val playlistId: String,
    val title: String,
    val items: List<YouTubeImportedItem>
)

/**
 * Read-only YouTube Data API access through microG (or Google Play services) account storage.
 * The account is chosen from the accounts microG registers under the `com.google` type; the
 * OAuth token is requested from GmsCore when needed. This class never reads microG's private
 * database and stores no access or refresh token; it keeps only the account name and type.
 *
 * The official Data API exposes the user's YouTube playlists (including their "Liked videos"
 * and "Uploads" playlists reported on the channel resource), but NOT YouTube Music library-only
 * collections (saved songs, albums, artists, or auto-generated mixes like Supermix / Daylist /
 * Your likes). Those live behind YouTube Music's private API and cannot be reached with the
 * public Data API scope. Imported playlists therefore reflect what the Data API can see;
 * Music-only mixes are intentionally out of scope.
 */
internal class YouTubeDataService(context: Context) {
    private val appContext = context.applicationContext
    private val secrets = SecretStore(appContext)

    @Volatile private var cachedLibrary: CachedLibrary? = null

    private data class CachedPlaylist(val playlistId: String, val title: String, val body: String)
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
     * Pulls every accessible YouTube playlist (plus Liked videos and Uploads) and returns
     * them as [YouTubeImportedPlaylist]s with deterministic item lists. Each playlist uses
     * its stable YouTube playlist id; ViewModel layer maps that to a Soundtrail playlist id
     * so repeated imports merge (existing items keep their state, new videos are appended).
     *
     * The YouTube Data API pages through at most [MAX_IMPORT_PLAYLISTS] playlists and
     * [MAX_IMPORT_ITEMS_PER_PLAYLIST] items per list to bound bandwidth and token use.
     */
    suspend fun importPlaylists(): List<YouTubeImportedPlaylist> {
        if (!connected) throw YouTubeDataException("Connect a Google account to import your YouTube playlists.")
        val token = accessToken()
        val snapshot = fetchAllPlaylists(token)
        cachedLibrary = CachedLibrary(System.currentTimeMillis(), snapshot)
        return snapshot.map { cached ->
            val videos = YouTubeApiParser.playlistItems(cached.body)
            YouTubeImportedPlaylist(
                playlistId = cached.playlistId,
                title = cached.title,
                items = videos
            )
        }
    }

    /**
     * Searches videos in the user's own playlists. The public YouTube Data API does not expose
     * the complete YouTube Music library (saved songs/albums/artists/mixes), so this searches
     * accessible playlist items only.
     */
    suspend fun searchTracks(query: String): List<TrackResult> {
        if (query.isBlank()) return emptyList()
        if (!connected) throw YouTubeDataException("Connect a Google account to search your YouTube playlists.")

        val token = accessToken()
        val now = System.currentTimeMillis()
        val snapshot = cachedLibrary?.takeIf {
            now >= it.cachedAt && now - it.cachedAt < LIBRARY_CACHE_MS
        } ?: fetchAllPlaylists(token).also { cachedLibrary = CachedLibrary(System.currentTimeMillis(), it) }

        return snapshot.flatMap { playlist ->
            YouTubeApiParser.matchingTracks(playlist.body, query, playlist.title)
        }.distinctBy { it.url }.take(MAX_RESULTS)
    }

    /** Walks every page of playlists (mine=true), fetches items for each, and also pulls Likes + Uploads. */
    private suspend fun fetchAllPlaylists(token: String): List<CachedPlaylist> = coroutineScope {
        val owned = paginatePlaylists(token)
        // Likes & Uploads playlist ids live on the user's own channel.
        val channelBody = apiGet(
            "channels",
            listOf("part" to "contentDetails", "mine" to "true", "maxResults" to "1"),
            token
        )
        val related = channelPlaylistIds(channelBody)

        val allRefs = owned + related.map { (id, title) -> YouTubePlaylistRef(id, title) }
        val semaphore = Semaphore(4)
        val fetched = allRefs.map { playlist ->
            async {
                semaphore.withPermit {
                    val body = fetchAllPlaylistItems(token, playlist.id)
                    CachedPlaylist(playlist.id, playlist.title, body)
                }
            }
        }.awaitAll()
        // Dedupe by playlist id (special lists could theoretically overlap with owned playlists).
        fetched.associateBy { it.playlistId }.values.toList()
    }

    private suspend fun paginatePlaylists(token: String): List<YouTubePlaylistRef> {
        val collected = mutableListOf<YouTubePlaylistRef>()
        var pageToken: String? = null
        var pages = 0
        while (pages < MAX_PLAYLIST_PAGES) {
            val params = arrayOf(
                "part" to "snippet",
                "mine" to "true",
                "maxResults" to PAGE_SIZE,
                *(if (pageToken != null) arrayOf("pageToken" to pageToken!!) else emptyArray())
            )
            val body = apiGet("playlists", params.toList(), token)
            collected += YouTubeApiParser.playlists(body)
            pageToken = JSONObject(body).optString("nextPageToken").takeIf(String::isNotBlank)
            pages++
            if (pageToken == null) break
            if (collected.size >= MAX_IMPORT_PLAYLISTS) break
        }
        return collected.take(MAX_IMPORT_PLAYLISTS)
    }

    private suspend fun fetchAllPlaylistItems(token: String, playlistId: String): String {
        // Pages are concatenated into a synthetic JSON object shaped the same as a single page,
        // so the existing playlistItems/matchingTracks parsers keep working.
        val items = StringBuilder()
        var pageToken: String? = null
        var total = 0
        var pages = 0
        while (pages < MAX_ITEM_PAGES) {
            val params = arrayOf(
                "part" to "snippet,contentDetails",
                "playlistId" to playlistId,
                "maxResults" to PAGE_SIZE,
                *(if (pageToken != null) arrayOf("pageToken" to pageToken!!) else emptyArray())
            )
            val body = apiGet("playlistItems", params.toList(), token)
            val pageItems = JSONObject(body).optJSONArray("items")
            if (pageItems != null) {
                for (i in 0 until pageItems.length()) {
                    if (total >= MAX_IMPORT_ITEMS_PER_PLAYLIST) break
                    if (items.isNotEmpty()) items.append(',')
                    items.append(pageItems.getJSONObject(i).toString())
                    total++
                }
            }
            if (total >= MAX_IMPORT_ITEMS_PER_PLAYLIST) break
            pageToken = JSONObject(body).optString("nextPageToken").takeIf(String::isNotBlank)
            pages++
            if (pageToken == null) break
        }
        return """{"items":[$items]}"""
    }

    private fun channelPlaylistIds(body: String): List<Pair<String, String>> {
        val item = JSONObject(body).optJSONArray("items")?.optJSONObject(0) ?: return emptyList()
        val details = item.optJSONObject("contentDetails")
            ?.optJSONObject("relatedPlaylists") ?: return emptyList()
        return buildList {
            details.optString("likes").takeIf(String::isNotBlank)?.let { add(it to "Liked videos") }
            details.optString("uploads").takeIf(String::isNotBlank)?.let { add(it to "Uploads") }
            details.optString("favorites").takeIf(String::isNotBlank)?.let { add(it to "Favorites") }
        }
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
        private const val PAGE_SIZE = "50"
        private const val MAX_PLAYLIST_PAGES = 4            // 4 * 50 = 200 playlists
        private const val MAX_ITEM_PAGES = 40               // 40 * 50 = 2000 items/playlist
        private const val MAX_IMPORT_PLAYLISTS = 200
        private const val MAX_IMPORT_ITEMS_PER_PLAYLIST = 2000
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

    /** Parses a playlist-items page into structured video entries (stable id/title/artist). */
    fun playlistItems(body: String): List<YouTubeImportedItem> {
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
            YouTubeImportedItem(videoId = id, title = title.take(120), artist = artist)
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
