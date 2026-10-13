package tech.egrie.soundtrail

import android.accounts.Account
import android.app.Application
import android.net.Uri
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.egrie.soundtrail.automation.AutomationRule
import tech.egrie.soundtrail.automation.AutomationStore
import tech.egrie.soundtrail.automation.LocationWatchService
import tech.egrie.soundtrail.data.BackupException
import tech.egrie.soundtrail.data.BackupFormat
import tech.egrie.soundtrail.data.FileStore
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.PinStore
import tech.egrie.soundtrail.data.Playlist
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.data.PlaylistStore
import tech.egrie.soundtrail.data.StoredAudioFile
import tech.egrie.soundtrail.integrations.AudioDbService
import tech.egrie.soundtrail.integrations.DeviceApps
import tech.egrie.soundtrail.integrations.JellyfinRef
import tech.egrie.soundtrail.integrations.JellyfinService
import tech.egrie.soundtrail.integrations.MusicLink
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.MusicProvider
import tech.egrie.soundtrail.integrations.SongInfo
import tech.egrie.soundtrail.integrations.SoundCloudService
import tech.egrie.soundtrail.integrations.SpotifyService
import tech.egrie.soundtrail.integrations.TrackResult
import tech.egrie.soundtrail.integrations.YouTubeConsentRequiredException
import tech.egrie.soundtrail.integrations.YouTubeDataService
import tech.egrie.soundtrail.integrations.YouTubeImportedPlaylist
import tech.egrie.soundtrail.integrations.YouTubeSearch
import tech.egrie.soundtrail.location.DeviceLocation
import tech.egrie.soundtrail.playback.JellyfinPlayer
import tech.egrie.soundtrail.sync.CloudSyncException
import tech.egrie.soundtrail.sync.CloudSyncService
import tech.egrie.soundtrail.sync.YouTubeAudioStage
import java.util.UUID

/** Which connected catalog the Search tab queries. */
internal enum class SearchProvider(val label: String) {
    SPOTIFY("Spotify"), SOUNDCLOUD("SoundCloud"), JELLYFIN("Jellyfin"), YOUTUBE_DATA("YouTube")
}

internal data class AppState(
    val pins: List<MusicPin> = emptyList(),
    val location: GeoPoint? = null,
    val locating: Boolean = false,
    val spotifyConnected: Boolean = false,
    val spotifyClientId: String = "",
    val spotifyName: String? = null,
    val soundcloudConnected: Boolean = false,
    val soundcloudClientId: String = "",
    val soundcloudName: String? = null,
    val jellyfinConnected: Boolean = false,
    val jellyfinServer: String = "",
    val jellyfinName: String = "",
    val jellyfinServerName: String = "",
    val youtubeDataConnected: Boolean = false,
    val youtubeDataAccount: String? = null,
    val youtubeOAuthClientId: String = "",
    val connecting: Boolean = false,
    val connectingSoundcloud: Boolean = false,
    val connectingJellyfin: Boolean = false,
    val connectingYouTubeData: Boolean = false,
    val connectingYouTubeOAuth: Boolean = false,
    val searchProvider: SearchProvider = SearchProvider.SPOTIFY,
    val searching: Boolean = false,
    val results: List<TrackResult> = emptyList(),
    val searchError: String? = null,
    val lookupSearching: Boolean = false,
    val lookupResults: List<SongInfo> = emptyList(),
    val lookupError: String? = null,
    val playlists: List<tech.egrie.soundtrail.data.Playlist> = emptyList(),
    val automations: List<AutomationRule> = emptyList(),
    val autoSearchYoutube: Boolean = true,
    val backupBusy: Boolean = false,
    val syncServer: String = "",
    val syncEffectiveServer: String = CloudSyncService.DEFAULT_SYNC_SERVER,
    val syncUsingCustomServer: Boolean = false,
    val syncEmail: String = "",
    val syncConnected: Boolean = false,
    val syncHasRecoveryKey: Boolean = false,
    val syncBusy: Boolean = false,
    val syncAutoEnabled: Boolean = false,
    val syncLastSyncAt: Long = 0L,
    val syncPendingChanges: Int = 0,
    val syncFailed: Boolean = false,
    val syncError: String? = null,
    val syncRecoveryKey: String? = null,
    val jellyfinDownloads: Map<String, JellyfinDownloadProgress> = emptyMap(),
    val youtubeDownloads: Map<String, YouTubeDownloadProgress> = emptyMap(),
    val youtubeImporting: Boolean = false,
    val youtubeAutoImportEnabled: Boolean = true,
    val youtubeLastImportedAt: Long = 0L,
    val offlineAudioFiles: List<OfflineAudioItem> = emptyList(),
    val offlineAudioBusy: Boolean = false,
    val audioStorageBytes: Long = 0L,
    val watchStatus: tech.egrie.soundtrail.automation.WatchStatus = tech.egrie.soundtrail.automation.WatchStatus(),
    val spotifyAppInstalled: Boolean = false,
    val soundcloudAppInstalled: Boolean = false,
    val youtubeAppInstalled: Boolean = false,
    val servicesLabel: String = "Checking services…",
    val gmsSignInAvailable: Boolean = false,
    val microGReInstalled: Boolean = false
)

internal data class JellyfinDownloadProgress(val receivedBytes: Long, val totalBytes: Long)

internal data class YouTubeDownloadProgress(
    val stage: YouTubeAudioStage,
    val receivedBytes: Long,
    val totalBytes: Long
)

internal data class OfflineAudioItem(
    val fileName: String,
    val title: String,
    val detail: String,
    val sizeBytes: Long
)

internal data class PinInput(
    val title: String,
    val artist: String,
    val place: String,
    val note: String,
    val url: String,
    val point: GeoPoint?
)

internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val pins = PinStore(application)
    private val playlists = PlaylistStore(application)
    private val automations = AutomationStore(application)
    private val files = FileStore(application)
    private val spotify = SpotifyService(application)
    private val soundcloud = SoundCloudService(application)
    private val jellyfin = JellyfinService(application)
    private val youtubeData = YouTubeDataService(application)
    private val audioDb = AudioDbService()
    private val locationReader = DeviceLocation(application)
    private val apps = DeviceApps(application)
    private val cloudSync = CloudSyncService(application, pins, playlists, automations, files)
    private val youtubePrefs = application.getSharedPreferences("soundtrail_youtube", android.content.Context.MODE_PRIVATE)

    /**
     * The app's one player, shared with [tech.egrie.soundtrail.playback.PlaybackService] so
     * the in-app bar and the playback notification always drive the same stream.
     */
    val player = JellyfinPlayer.shared(application)

    private val _state = MutableStateFlow(
        AppState(
            pins = pins.all(),
            playlists = playlists.all(),
            automations = automations.all(),
            offlineAudioFiles = buildOfflineAudioItems(playlists.all()),
            audioStorageBytes = files.totalBytes(),
            syncPendingChanges = cloudSync.pendingChangeCount(),
            spotifyConnected = spotify.connected,
            spotifyClientId = spotify.clientId,
            soundcloudConnected = soundcloud.connected,
            soundcloudClientId = soundcloud.clientId,
            jellyfinConnected = jellyfin.connected,
            jellyfinServer = jellyfin.serverUrl,
            jellyfinName = jellyfin.userName,
            jellyfinServerName = jellyfin.serverName,
            youtubeDataConnected = youtubeData.connected,
            youtubeDataAccount = youtubeData.accountName,
            youtubeOAuthClientId = youtubeData.oauthClientId,
            gmsSignInAvailable = apps.systemGmsInstalled,
            microGReInstalled = apps.microGReInstalled,
            syncServer = cloudSync.serverUrl,
            syncEffectiveServer = cloudSync.effectiveServerUrl,
            syncUsingCustomServer = cloudSync.isUsingCustomServer,
            syncEmail = cloudSync.accountEmail,
            syncConnected = cloudSync.connected,
            syncHasRecoveryKey = cloudSync.hasRecoveryKey,
            syncAutoEnabled = cloudSync.autoSyncEnabled,
            syncLastSyncAt = cloudSync.lastSyncAt
        )
    )
    val state = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        refreshDeviceStatus()
        if (spotify.connected) loadProfile()
        if (soundcloud.connected) loadSoundcloudProfile()
        if (cloudSync.autoSyncEnabled && cloudSync.connected && cloudSync.hasRecoveryKey) {
            runCloudSync(showMessages = false)
        }
        // If the user left auto-import on and YouTube is already connected (after an upgrade or
        // process restart), do a quiet background refresh. This is a pull, not a mutation of any
        // remote account: it just adds newly discovered videos to local playlists.
        if (youtubeData.connected && youtubePrefs.getBoolean("auto_import", true)) {
            viewModelScope.launch { runCatching { runYouTubeImport(showMessages = false) } }
        }
        viewModelScope.launch {
            LocationWatchService.status.collect { live -> _state.update { it.copy(watchStatus = live) } }
        }
    }

    fun notify(message: String) { _messages.tryEmit(message) }

    fun refreshDeviceStatus() {
        _state.update {
            it.copy(
                spotifyAppInstalled = apps.spotifyInstalled,
                soundcloudAppInstalled = apps.soundcloudInstalled,
                youtubeAppInstalled = apps.youtubeMusicPackage != null,
                servicesLabel = apps.servicesLabel,
                gmsSignInAvailable = apps.systemGmsInstalled,
                microGReInstalled = apps.microGReInstalled,
                spotifyConnected = spotify.connected,
                soundcloudConnected = soundcloud.connected,
                jellyfinConnected = jellyfin.connected,
                youtubeDataConnected = youtubeData.connected,
                youtubeDataAccount = youtubeData.accountName,
                youtubeOAuthClientId = youtubeData.oauthClientId,
                youtubeAutoImportEnabled = youtubePrefs.getBoolean("auto_import", true),
                youtubeLastImportedAt = youtubePrefs.getLong("last_imported_at", 0L),
                syncServer = cloudSync.serverUrl,
                syncEffectiveServer = cloudSync.effectiveServerUrl,
                syncUsingCustomServer = cloudSync.isUsingCustomServer,
                syncEmail = cloudSync.accountEmail,
                syncConnected = cloudSync.connected,
                syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                syncAutoEnabled = cloudSync.autoSyncEnabled,
                syncLastSyncAt = cloudSync.lastSyncAt,
                syncPendingChanges = cloudSync.pendingChangeCount()
            )
        }
    }

    fun locate() {
        if (_state.value.locating) return
        _state.update { it.copy(locating = true) }
        viewModelScope.launch {
            try {
                val point = locationReader.locate()
                _state.update { it.copy(location = point) }
                notify("Location ready. Coordinates are used only for your saved pins and opt-in automations.")
            } catch (e: Exception) {
                notify(e.message ?: "Couldn't find your location.")
            } finally {
                _state.update { it.copy(locating = false) }
            }
        }
    }

    fun beginSpotify(clientId: String): Uri {
        val uri = spotify.beginSignIn(clientId)
        _state.update { it.copy(spotifyClientId = spotify.clientId, spotifyConnected = spotify.connected) }
        return uri
    }

    fun cancelSpotify() = spotify.cancelSignIn()

    fun finishSpotify(uri: Uri) {
        if (_state.value.connecting) return
        _state.update { it.copy(connecting = true) }
        viewModelScope.launch {
            try {
                spotify.finishSignIn(uri)
                _state.update { it.copy(spotifyConnected = true, spotifyClientId = spotify.clientId) }
                notify("Spotify connected. Find a track to pin!")
                loadProfile()
            } catch (e: Exception) {
                notify(e.message ?: "Could not connect Spotify.")
            } finally {
                _state.update { it.copy(connecting = false, spotifyConnected = spotify.connected) }
            }
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            // The connection still works when profile lookup is unavailable/offline.
            val name = runCatching { spotify.profileName() }.getOrNull()
            _state.update { it.copy(spotifyName = name, spotifyConnected = spotify.connected) }
        }
    }

    fun disconnectSpotify() {
        spotify.disconnect()
        _state.update {
            it.copy(spotifyConnected = false, spotifyName = null, results = emptyList(), searchError = null)
        }
        notify("Spotify disconnected on this device.")
    }

    fun beginSoundcloud(clientId: String, clientSecret: String): Uri {
        val uri = soundcloud.beginSignIn(clientId, clientSecret)
        _state.update {
            it.copy(soundcloudClientId = soundcloud.clientId, soundcloudConnected = soundcloud.connected)
        }
        return uri
    }

    fun cancelSoundcloud() = soundcloud.cancelSignIn()

    fun finishSoundcloud(uri: Uri) {
        if (_state.value.connectingSoundcloud) return
        _state.update { it.copy(connectingSoundcloud = true) }
        viewModelScope.launch {
            try {
                soundcloud.finishSignIn(uri)
                _state.update { it.copy(soundcloudConnected = true, soundcloudClientId = soundcloud.clientId) }
                notify("SoundCloud connected. Find a track to pin!")
                loadSoundcloudProfile()
            } catch (e: Exception) {
                notify(e.message ?: "Could not connect SoundCloud.")
            } finally {
                _state.update { it.copy(connectingSoundcloud = false, soundcloudConnected = soundcloud.connected) }
            }
        }
    }

    private fun loadSoundcloudProfile() {
        viewModelScope.launch {
            // The connection still works when profile lookup is unavailable/offline.
            val name = runCatching { soundcloud.profileName() }.getOrNull()
            _state.update { it.copy(soundcloudName = name, soundcloudConnected = soundcloud.connected) }
        }
    }

    fun disconnectSoundcloud() {
        // Clear local state first; the server-side sign-out is best-effort and only
        // invalidates the exact token captured here, so it cannot race a reconnect.
        val previous = soundcloud.disconnect()
        _state.update {
            it.copy(soundcloudConnected = false, soundcloudName = null, results = emptyList(), searchError = null)
        }
        notify("SoundCloud disconnected on this device.")
        if (previous != null) {
            viewModelScope.launch { soundcloud.revokeAccessToken(previous) }
        }
    }

    // ----- Jellyfin: the user's own server -----

    /**
     * Signs in to the user's Jellyfin server. The address, the username, and the password go
     * only to that server; the password is not stored, and the returned token is encrypted
     * with Android Keystore like the other providers' tokens.
     */
    fun connectJellyfin(server: String, user: String, password: String) {
        if (_state.value.connectingJellyfin) return
        _state.update { it.copy(connectingJellyfin = true) }
        viewModelScope.launch {
            try {
                val name = jellyfin.connect(server, user, password)
                _state.update {
                    it.copy(
                        jellyfinConnected = true,
                        jellyfinServer = jellyfin.serverUrl,
                        jellyfinName = jellyfin.userName.ifBlank { name },
                        jellyfinServerName = jellyfin.serverName
                    )
                }
                notify("Connected to ${jellyfin.serverName.ifBlank { "your Jellyfin server" }} as $name.")
            } catch (e: Exception) {
                notify(e.message ?: "Could not reach your Jellyfin server.")
            } finally {
                _state.update {
                    it.copy(
                        connectingJellyfin = false,
                        jellyfinConnected = jellyfin.connected,
                        jellyfinServer = jellyfin.serverUrl,
                        jellyfinName = jellyfin.userName,
                        jellyfinServerName = jellyfin.serverName
                    )
                }
            }
        }
    }

    fun disconnectJellyfin() {
        // Clear locally first; the sign-out call is best-effort and cannot race a reconnect.
        val previous = jellyfin.disconnect()
        val server = _state.value.jellyfinServer
        player.stop()
        _state.update {
            it.copy(
                jellyfinConnected = false,
                jellyfinName = "",
                results = if (it.searchProvider == SearchProvider.JELLYFIN) emptyList() else it.results,
                searchError = null
            )
        }
        notify("Jellyfin disconnected on this device.")
        if (previous != null && server.isNotBlank()) {
            viewModelScope.launch { jellyfin.revokeAccessToken(server, previous) }
        }
    }

    /**
     * Plays a pinned or playlist Jellyfin song in the app itself. The stored link is only an
     * item reference, so the stream URL is resolved here from the current connection.
     */
    fun playJellyfin(link: MusicLink, title: String, artist: String) {
        val itemId = JellyfinRef.itemId(link.url)
        if (itemId == null) {
            notify("That Jellyfin song link is not usable.")
            return
        }
        val playback = jellyfin.playback(itemId)
        if (playback == null) {
            notify("Connect your Jellyfin server on the Connect tab to stream this song.")
            return
        }
        player.play(
            title = title.ifBlank { "Jellyfin song" },
            artist = artist,
            itemId = itemId,
            playback = playback
        )
    }

    /** Unified in-app playback for any provider: local file, Jellyfin, Spotify preview, SoundCloud, YouTube. */
    fun playPin(pin: tech.egrie.soundtrail.data.MusicPin) {
        val link = MusicLink(pin.provider, pin.url)
        when (pin.provider) {
            MusicProvider.JELLYFIN -> playJellyfin(link, pin.title, pin.artist)
            else -> playLink(link, pin.title, pin.artist, pin.id)
        }
    }

    fun playPlaylistItemUnified(item: PlaylistItem) {
        // Local offline copy always wins — plays directly in-app, no network.
        item.localFile?.let { name ->
            files.fileFor(name)?.let { file ->
                player.playLocalFile(
                    title = item.title,
                    artist = item.artist,
                    itemId = item.id,
                    file = file,
                    provider = item.provider ?: MusicProvider.YOUTUBE_MUSIC
                )
                return
            }
        }
        val url = item.url
        if (url != null) {
            val link = MusicLinkParser.parse(url)
            if (link != null) {
                if (link.provider == MusicProvider.JELLYFIN) {
                    playJellyfin(link, item.title, item.artist)
                    return
                }
                playLink(link, item.title, item.artist, item.id)
                return
            }
        }
        // No link — try to find a local search fallback? For now, notify.
        notify("This song has no playable link. Add a supported link or attach audio you own.")
    }

    // Keeps the last YouTube in-app playback temp file so we can clean it up on next play.
    private var lastYouTubePlaybackFile: java.io.File? = null

    private fun playLink(link: MusicLink, title: String, artist: String, itemId: String) {
        viewModelScope.launch {
            try {
                when (link.provider) {
                    MusicProvider.JELLYFIN -> {
                        // Already handled above, but keep for safety
                        playJellyfin(link, title, artist)
                    }
                    MusicProvider.SPOTIFY -> {
                        player.setBuffering(title, artist, itemId, MusicProvider.SPOTIFY)
                        val preview = spotify.resolvePlayableUrl(link)
                        if (preview != null) {
                            player.playRemoteUrl(title, artist, itemId, preview, MusicProvider.SPOTIFY)
                        } else {
                            player.stop()
                            notify("No Spotify preview available for this track. Spotify full playback needs their app, but Soundtrail plays 30-second previews when Spotify provides them. Try searching again to get a preview.")
                        }
                    }
                    MusicProvider.SOUNDCLOUD -> {
                        player.setBuffering(title, artist, itemId, MusicProvider.SOUNDCLOUD)
                        val stream = try {
                            soundcloud.resolvePlayableUrl(link)
                        } catch (_: Exception) { null }
                        if (stream != null) {
                            player.playRemoteUrl(title, artist, itemId, stream, MusicProvider.SOUNDCLOUD)
                        } else {
                            player.stop()
                            notify("Couldn't get a SoundCloud stream for this track. It may be private or require re-connecting SoundCloud.")
                        }
                    }
                    MusicProvider.YOUTUBE_MUSIC -> {
                        val videoId = MusicLinkParser.youtubeVideoId(link.url)
                        if (videoId == null) {
                            notify("That YouTube link is not a playable video.")
                            return@launch
                        }
                        if (!cloudSync.connected) {
                            notify("Connect your sync server to play YouTube audio in-app, or download it for offline playback first.")
                            return@launch
                        }
                        player.setBuffering(title, artist, itemId, MusicProvider.YOUTUBE_MUSIC)
                        // Clean up previous in-app YouTube playback temp file.
                        lastYouTubePlaybackFile?.let { prev ->
                            withContext(Dispatchers.IO) { files.discardDownloadTemp(prev) }
                            lastYouTubePlaybackFile = null
                        }
                        // Download to a temp file that is NOT counted as offline storage (.part is excluded).
                        val temp = withContext(Dispatchers.IO) { files.newDownloadTemp() }
                        try {
                            cloudSync.downloadYouTubeAudio(videoId, temp, userConfirmedRights = true) { _, _, _ -> }
                            if (!temp.isFile || temp.length() == 0L) {
                                notify("Couldn't prepare YouTube audio for playback.")
                                withContext(Dispatchers.IO) { files.discardDownloadTemp(temp) }
                                player.stop()
                                return@launch
                            }
                            lastYouTubePlaybackFile = temp
                            player.playLocalFile(title, artist, itemId, temp, MusicProvider.YOUTUBE_MUSIC)
                        } catch (e: Exception) {
                            withContext(Dispatchers.IO) { files.discardDownloadTemp(temp) }
                            player.stop()
                            notify(e.message ?: "YouTube in-app playback failed.")
                        }
                    }
                }
            } catch (e: Exception) {
                notify(e.message ?: "Couldn't play this song in-app.")
            }
        }
    }

    /** Attempts to play a TrackResult directly (from search), using its playableUrl if present. */
    fun playTrackResult(track: TrackResult) {
        track.playableUrl?.let { url ->
            player.playRemoteUrl(track.title, track.artist, track.url, url, track.provider)
            return
        }
        // No direct playable URL in result — resolve via provider APIs.
        val link = MusicLinkParser.parse(track.url)
        if (link != null) {
            playLink(link, track.title, track.artist, track.url)
        } else {
            notify("No playable audio for this track.")
        }
    }

    fun togglePlayback() = player.toggle()

    fun stopPlayback() {
        player.stop()
        // Optionally keep the YouTube playback cache for quick replay; clean up only on next YouTube play
        // or when explicitly cleared. We don't delete here to allow pause/resume to keep working.
    }

    fun clearYouTubePlaybackCache() {
        lastYouTubePlaybackFile?.let { file ->
            viewModelScope.launch(Dispatchers.IO) { files.discardDownloadTemp(file) }
            lastYouTubePlaybackFile = null
        }
    }

    /** A link to the item in Jellyfin's own web client, for sharing with people on that server. */
    fun jellyfinWebUrl(link: MusicLink): String? {
        val itemId = JellyfinRef.itemId(link.url) ?: return null
        if (!jellyfin.connected) return null
        return JellyfinRef.webUrl(jellyfin.serverUrl, itemId)
    }

    /** The server's own web interface, for opening in a browser. */
    fun jellyfinServerUrl(): String = jellyfin.serverUrl

    /** Connects a Google account that the user finished signing in to in the Google sign-in screen. */
    fun connectYouTubeData(account: GoogleSignInAccount) {
        val googleAccount = account.account
        if (googleAccount == null) {
            notify("Google Sign-In did not return an account. Try again.")
            return
        }
        runYouTubeConnect(onConsentNeeded = null) {
            youtubeData.connect(googleAccount, account.email ?: account.displayName)
        }
    }

    /**
     * Connects the microG (or device) Google account without a picker. If Google still needs the
     * read-only consent, [onConsentNeeded] runs so the caller can open the interactive sign-in.
     */
    fun connectMicroGAccount(account: Account, onConsentNeeded: () -> Unit) {
        runYouTubeConnect(onConsentNeeded) {
            youtubeData.connect(account, null)
        }
    }

    private fun runYouTubeConnect(
        onConsentNeeded: (() -> Unit)?,
        connect: suspend () -> String
    ) {
        if (_state.value.connectingYouTubeData) return
        _state.update { it.copy(connectingYouTubeData = true, youtubeDataConnected = false) }
        viewModelScope.launch {
            try {
                val accountName = connect()
                _state.update {
                    it.copy(
                        youtubeDataConnected = true,
                        youtubeDataAccount = accountName,
                        youtubeAutoImportEnabled = youtubePrefs.getBoolean("auto_import", true),
                        youtubeLastImportedAt = youtubePrefs.getLong("last_imported_at", 0L)
                    )
                }
                notify("YouTube read-only access connected. Playlists will import automatically if that option is on.")
                maybeAutoImportYouTube()
            } catch (e: YouTubeConsentRequiredException) {
                _state.update { it.copy(youtubeDataConnected = false, youtubeDataAccount = null) }
                if (onConsentNeeded != null) {
                    onConsentNeeded()
                } else {
                    notify(e.message ?: "Approve the YouTube read-only permission, then connect again.")
                }
            } catch (e: Exception) {
                _state.update { it.copy(youtubeDataConnected = false, youtubeDataAccount = null) }
                notify(e.message ?: "Could not connect your YouTube account.")
            } finally {
                _state.update {
                    it.copy(
                        connectingYouTubeData = false,
                        youtubeDataConnected = youtubeData.connected,
                        youtubeDataAccount = youtubeData.accountName
                    )
                }
            }
        }
    }

    /** Starts the browser OAuth flow with the user's own Desktop client ID (no GMS needed). */
    fun beginYouTubeOAuth(clientId: String): Uri {
        val uri = youtubeData.beginOAuthSignIn(clientId)
        _state.update { it.copy(youtubeOAuthClientId = youtubeData.oauthClientId) }
        return uri
    }

    fun cancelYouTubeOAuth() = youtubeData.cancelOAuthSignIn()

    /** Finishes the browser OAuth flow after Google redirects back to the app. */
    fun finishYouTubeOAuth(uri: Uri) {
        if (_state.value.connectingYouTubeOAuth) return
        _state.update { it.copy(connectingYouTubeOAuth = true) }
        viewModelScope.launch {
            try {
                val label = youtubeData.finishOAuthSignIn(uri)
                _state.update {
                    it.copy(
                        youtubeDataConnected = true,
                        youtubeDataAccount = label,
                        youtubeOAuthClientId = youtubeData.oauthClientId,
                        youtubeAutoImportEnabled = youtubePrefs.getBoolean("auto_import", true),
                        youtubeLastImportedAt = youtubePrefs.getLong("last_imported_at", 0L)
                    )
                }
                notify("YouTube read-only access connected. Playlists will import automatically if that option is on.")
                maybeAutoImportYouTube()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        youtubeDataConnected = youtubeData.connected,
                        youtubeDataAccount = youtubeData.accountName,
                        youtubeOAuthClientId = youtubeData.oauthClientId
                    )
                }
                notify(e.message ?: "Could not connect your YouTube account.")
            } finally {
                _state.update {
                    it.copy(
                        connectingYouTubeOAuth = false,
                        youtubeDataConnected = youtubeData.connected,
                        youtubeDataAccount = youtubeData.accountName,
                        youtubeOAuthClientId = youtubeData.oauthClientId
                    )
                }
            }
        }
    }

    fun disconnectYouTubeData() {
        // Clear local state first; the server-side revocation is best-effort and only
        // invalidates the exact token captured here, so it cannot race a reconnect.
        val previousOAuth = youtubeData.disconnect()
        _state.update {
            it.copy(
                youtubeDataConnected = false,
                youtubeDataAccount = null,
                youtubeImporting = false,
                results = if (it.searchProvider == SearchProvider.YOUTUBE_DATA) emptyList() else it.results,
                searchError = null
            )
        }
        notify("YouTube access disconnected on this device; Google was asked to revoke the grant.")
        if (previousOAuth != null) {
            viewModelScope.launch { youtubeData.revokeOAuthToken(previousOAuth) }
        }
    }

    fun setYouTubeAutoImport(enabled: Boolean) {
        youtubePrefs.edit().putBoolean("auto_import", enabled).apply()
        _state.update { it.copy(youtubeAutoImportEnabled = enabled) }
    }

    /**
     * Pulls playlists and their videos from the connected YouTube account and merges them into
     * Soundtrail playlists. Deterministic ids (yt-pl:<playlistId> and yt:<playlistId>:<videoId>)
     * keep re-imports idempotent: previously imported playlists keep existing items (including
     * offline files) and only newly discovered videos are appended. YouTube Music library-only
     * collections (saved songs/albums/mixes) are not reachable via the public Data API and are
     * therefore not imported; the notice text makes that clear.
     */
    fun importYouTubePlaylists() = runYouTubeImport(showMessages = true)

    private fun runYouTubeImport(showMessages: Boolean) {
        if (_state.value.youtubeImporting) return
        if (!youtubeData.connected) {
            if (showMessages) notify("Connect your Google account before importing YouTube playlists.")
            return
        }
        _state.update { it.copy(youtubeImporting = true) }
        viewModelScope.launch {
            try {
                val imported: List<YouTubeImportedPlaylist> = withContext(Dispatchers.IO) { youtubeData.importPlaylists() }
                val now = System.currentTimeMillis()
                val incoming = imported.map { yt ->
                    Playlist(
                        id = "yt-pl:${yt.playlistId}",
                        name = yt.title,
                        items = yt.items.map { video ->
                            PlaylistItem(
                                id = "yt:${yt.playlistId}:${video.videoId}",
                                title = video.title,
                                artist = video.artist,
                                url = "https://music.youtube.com/watch?v=${video.videoId}",
                                provider = MusicProvider.YOUTUBE_MUSIC,
                                createdAt = now
                            )
                        },
                        createdAt = now
                    )
                }
                val (addedLists, addedItems) = withContext(Dispatchers.IO) { playlists.merge(incoming) }
                youtubePrefs.edit().putLong("last_imported_at", now).apply()
                refreshOfflineAudioState()
                recordCloudSyncChanges()
                _state.update {
                    it.copy(
                        playlists = playlists.all(),
                        youtubeLastImportedAt = now,
                        youtubeImporting = false
                    )
                }
                if (showMessages) {
                    val note = buildString {
                        append("Imported ${incoming.size} YouTube playlist")
                        if (incoming.size != 1) append('s')
                        append(" — ${addedItems.coerceAtLeast(0)} new song")
                        if (addedItems != 1) append('s')
                        append(" added.")
                    }
                    notify(note)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(youtubeImporting = false) }
                if (showMessages) notify(e.message ?: "Could not import YouTube playlists.")
            }
        }
    }

    /** Called after a successful YouTube connect to optionally start an automatic import. */
    private fun maybeAutoImportYouTube() {
        if (youtubePrefs.getBoolean("auto_import", true)) runYouTubeImport(showMessages = true)
    }

    fun selectSearchProvider(provider: SearchProvider) {
        if (_state.value.searchProvider == provider) return
        _state.update { it.copy(searchProvider = provider, results = emptyList(), searchError = null) }
    }

    fun search(query: String) {
        if (_state.value.searching) return
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList(), searchError = null) }
            return
        }
        val provider = _state.value.searchProvider
        _state.update { it.copy(searching = true, results = emptyList(), searchError = null) }
        viewModelScope.launch {
            try {
                val tracks = when (provider) {
                    SearchProvider.SPOTIFY -> spotify.searchTracks(query)
                    SearchProvider.SOUNDCLOUD -> soundcloud.searchTracks(query)
                    SearchProvider.JELLYFIN -> jellyfin.searchTracks(query)
                    SearchProvider.YOUTUBE_DATA -> youtubeData.searchTracks(query)
                }
                _state.update { it.copy(results = tracks) }
            } catch (e: Exception) {
                _state.update { it.copy(searchError = e.message ?: "Search is unavailable.") }
            } finally {
                _state.update {
                    it.copy(
                        searching = false,
                        spotifyConnected = spotify.connected,
                        soundcloudConnected = soundcloud.connected,
                        jellyfinConnected = jellyfin.connected,
                        youtubeDataConnected = youtubeData.connected,
                        youtubeDataAccount = youtubeData.accountName
                    )
                }
            }
        }
    }

    fun lookupSong(artist: String, title: String) {
        if (_state.value.lookupSearching) return
        if (artist.isBlank() || title.isBlank()) {
            _state.update { it.copy(lookupError = "Enter both artist and song title.") }
            return
        }
        _state.update { it.copy(lookupSearching = true, lookupResults = emptyList(), lookupError = null) }
        viewModelScope.launch {
            try {
                val songs = audioDb.searchTracks(artist, title)
                _state.update { it.copy(lookupResults = songs) }
            } catch (e: Exception) {
                _state.update { it.copy(lookupError = e.message ?: "Song lookup is unavailable.") }
            } finally {
                _state.update { it.copy(lookupSearching = false) }
            }
        }
    }

    private fun rejectMutationWhileSyncing(): Boolean {
        val current = _state.value
        when {
            current.syncBusy -> notify("Wait for cloud sync to finish before editing synced items.")
            current.offlineAudioBusy -> notify("Wait for offline storage management to finish first.")
            current.backupBusy -> notify("Wait for the current backup operation to finish.")
            else -> return false
        }
        return true
    }

    // ----- Playlists -----

    fun createPlaylist(rawName: String) {
        if (rejectMutationWhileSyncing()) return
        val name = rawName.trim().take(80)
        val current = _state.value.playlists
        when {
            name.isEmpty() -> notify("Give the playlist a name first.")
            current.any { it.name.equals(name, ignoreCase = true) } ->
                notify("A playlist called \"$name\" already exists.")
            else -> {
                val playlist = Playlist(
                    id = UUID.randomUUID().toString(), name = name,
                    items = emptyList(), createdAt = System.currentTimeMillis()
                )
                val updated = (current + playlist).sortedBy { it.createdAt }
                playlists.replaceAll(updated)
                _state.update { it.copy(playlists = updated) }
                recordCloudSyncChanges()
                notify("Playlist \"$name\" created.")
            }
        }
    }

    fun deletePlaylist(id: String) {
        if (rejectMutationWhileSyncing()) return
        val currentPlaylists = _state.value.playlists
        // Imported audio belongs to the song; it leaves with the playlist.
        currentPlaylists.firstOrNull { it.id == id }?.items
            ?.forEach { item -> item.localFile?.let(files::delete) }
        val updatedPlaylists = currentPlaylists.filterNot { it.id == id }
        playlists.replaceAll(updatedPlaylists)

        // Rules pointing at a deleted playlist would never fire again.
        val currentRules = _state.value.automations
        val updatedRules = currentRules.filterNot { it.playlistId == id }
        automations.replaceAll(updatedRules)
        _state.update { it.copy(playlists = updatedPlaylists, automations = updatedRules) }
        refreshOfflineAudioState()
        recordCloudSyncChanges()
        notify("Playlist removed.")
    }

    /**
     * Adds a song to a playlist. All music now plays directly in-app, so no external search URL is returned.
     * YouTube offline audio remains a separate, rights-confirmed action through the self-hosted API.
     */
    fun addToPlaylist(playlistId: String, rawTitle: String, rawArtist: String, rawUrl: String) {
        if (rejectMutationWhileSyncing()) return
        val title = rawTitle.trim().take(120)
        val artist = rawArtist.trim().take(120)
        val link = rawUrl.trim().takeIf(String::isNotBlank)?.let(MusicLinkParser::fromText)
        val current = _state.value.playlists
        val playlist = current.firstOrNull { it.id == playlistId }
        when {
            title.isEmpty() -> { notify("Add a song title first."); return }
            playlist == null -> { notify("That playlist no longer exists."); return }
            rawUrl.trim().isNotBlank() && link == null -> {
                notify("That link is not a supported song URL. Leave it empty or add a valid Spotify, SoundCloud, YouTube Music, or Jellyfin link.")
                return
            }
        }
        val item = PlaylistItem(
            id = UUID.randomUUID().toString(), title = title, artist = artist,
            url = link?.url, provider = link?.provider, createdAt = System.currentTimeMillis()
        )
        val updated = current.map {
            if (it.id == playlistId) it.copy(items = it.items + item) else it
        }
        playlists.replaceAll(updated)
        _state.update { it.copy(playlists = updated) }
        recordCloudSyncChanges()
        notify("Added \"$title\" to ${playlist.name}. It will play directly in Soundtrail.")
    }

    fun removePlaylistItem(playlistId: String, itemId: String) {
        if (rejectMutationWhileSyncing()) return
        val current = _state.value.playlists
        val playlist = current.firstOrNull { it.id == playlistId }
        playlist?.items?.firstOrNull { it.id == itemId }?.localFile?.let(files::delete)
        if (playlist != null) {
            val updated = current.map {
                if (it.id == playlistId) {
                    it.copy(items = it.items.filterNot { item -> item.id == itemId })
                } else it
            }
            playlists.replaceAll(updated)
            _state.update { it.copy(playlists = updated) }
        }
        refreshOfflineAudioState()
        recordCloudSyncChanges()
        notify("Song removed.")
    }

    /** Downloads only the selected Jellyfin server item the signed-in user is permitted to access. */
    fun downloadJellyfinOffline(playlistId: String, itemId: String) {
        if (rejectMutationWhileSyncing()) return
        val key = "$playlistId:$itemId"
        if (_state.value.jellyfinDownloads.containsKey(key)) return
        if (!jellyfin.connected) {
            notify("Connect your Jellyfin server before downloading offline.")
            return
        }
        val item = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
        if (item == null) {
            notify("That playlist song no longer exists.")
            return
        }
        if (item.localFile?.let(files::exists) == true) {
            notify("That song is already available offline on this device.")
            return
        }
        val serverItemId = item.url?.let(JellyfinRef::itemId)
        if (serverItemId == null) {
            notify("Offline download is available for tracks added from your Jellyfin library.")
            return
        }
        _state.update {
            it.copy(jellyfinDownloads = it.jellyfinDownloads + (key to JellyfinDownloadProgress(0L, 0L)))
        }
        viewModelScope.launch {
            var temp: java.io.File? = null
            var downloadedName: String? = null
            var attached = false
            try {
                val staging = withContext(Dispatchers.IO) { files.newDownloadTemp() }
                temp = staging
                val info = jellyfin.downloadAudio(serverItemId, staging) { received, total ->
                    _state.update {
                        it.copy(jellyfinDownloads = it.jellyfinDownloads + (key to JellyfinDownloadProgress(received, total)))
                    }
                }
                downloadedName = withContext(Dispatchers.IO) {
                    files.finishDownload(staging, info.contentType, info.filename)
                } ?: throw IllegalStateException("Couldn't save that audio file to this device.")
                val latest = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
                if (latest == null) throw IllegalStateException("That playlist song was removed during the download.")
                attached = playlists.replaceItem(playlistId, itemId, latest.copy(localFile = downloadedName))
                if (!attached) throw IllegalStateException("Couldn't attach the downloaded file to its song.")
                refreshOfflineAudioState()
                val mebibytes = info.bytes / (1024L * 1024L)
                notify("${item.title} downloaded for offline playback from your Jellyfin server (${mebibytes} MiB).")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(jellyfinConnected = jellyfin.connected) }
                notify(e.message ?: "Jellyfin offline download failed.")
            } finally {
                temp?.let(files::discardDownloadTemp)
                if (!attached) downloadedName?.let(files::delete)
                _state.update { it.copy(jellyfinDownloads = it.jellyfinDownloads - key) }
            }
        }
    }

    /** Requests audio-only extraction for a YouTube item after the UI's explicit rights confirmation. */
    fun downloadYouTubeOffline(playlistId: String, itemId: String) {
        if (rejectMutationWhileSyncing()) return
        val key = "$playlistId:$itemId"
        if (_state.value.youtubeDownloads.containsKey(key)) return
        if (!cloudSync.connected) {
            notify("Sign in to your sync server to use its YouTube audio extractor.")
            return
        }
        val item = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
        if (item == null) {
            notify("That playlist song no longer exists.")
            return
        }
        if (item.localFile?.let(files::exists) == true) {
            notify("That song is already available offline on this device.")
            return
        }
        val videoId = item.url?.let(MusicLinkParser::youtubeVideoId)
        if (videoId == null) {
            notify("Audio extraction is available for supported YouTube video links only.")
            return
        }
        _state.update {
            it.copy(youtubeDownloads = it.youtubeDownloads + (key to YouTubeDownloadProgress(YouTubeAudioStage.PREPARING, 0L, 0L)))
        }
        viewModelScope.launch {
            var temp: java.io.File? = null
            var downloadedName: String? = null
            var attached = false
            try {
                val staging = withContext(Dispatchers.IO) { files.newDownloadTemp() }
                temp = staging
                val info = cloudSync.downloadYouTubeAudio(videoId, staging, userConfirmedRights = true) { stage, received, total ->
                    _state.update {
                        it.copy(youtubeDownloads = it.youtubeDownloads +
                            (key to YouTubeDownloadProgress(stage, received, total)))
                    }
                }
                downloadedName = withContext(Dispatchers.IO) {
                    files.finishDownload(staging, info.contentType, info.filename)
                } ?: throw IllegalStateException("Couldn't save that audio file to this device.")
                val latest = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
                if (latest == null) throw IllegalStateException("That playlist song was removed during the extraction.")
                attached = playlists.replaceItem(playlistId, itemId, latest.copy(localFile = downloadedName))
                if (!attached) throw IllegalStateException("Couldn't attach the extracted audio to its song.")
                refreshOfflineAudioState()
                val mebibytes = info.bytes / (1024L * 1024L)
                notify("${item.title} extracted as audio for offline playback (${mebibytes} MiB).")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notify(e.message ?: "YouTube audio extraction failed.")
            } finally {
                temp?.let(files::discardDownloadTemp)
                if (!attached) downloadedName?.let(files::delete)
                _state.update { it.copy(youtubeDownloads = it.youtubeDownloads - key) }
            }
        }
    }

    /** Attaches a user-picked audio file (their own download/import) to a playlist song. */
    fun attachLocalAudio(uri: Uri, playlistId: String, itemId: String) {
        if (rejectOfflineAudioManagement()) return
        _state.update { it.copy(offlineAudioBusy = true) }
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { files.importAudio(uri) }
                if (name == null) {
                    notify("Couldn't read that audio file.")
                    return@launch
                }
                val item = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
                if (item == null) {
                    files.delete(name)
                    notify("That song no longer exists.")
                    return@launch
                }
                val replaced = playlists.replaceItem(playlistId, itemId, item.copy(localFile = name))
                if (replaced) item.localFile?.takeIf { it != name }?.let(files::delete) else files.delete(name)
                refreshOfflineAudioState()
                notify(if (replaced) "Audio attached — \"${item.title}\" now plays from this device." else "Couldn't attach the audio file.")
            } catch (e: Exception) {
                notify(e.message ?: "Couldn't attach that audio file.")
            } finally {
                _state.update { it.copy(offlineAudioBusy = false) }
            }

        }
    }

    fun setAutoSearchYoutube(enabled: Boolean) {
        _state.update { it.copy(autoSearchYoutube = enabled) }
    }

    fun removeOfflineAudio(playlistId: String, itemId: String) {
        if (rejectMutationWhileSyncing()) return
        val item = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
        val localFile = item?.localFile
        if (item == null || localFile == null) {
            notify("That song has no offline copy to remove.")
            return
        }
        if ("$playlistId:$itemId" in _state.value.jellyfinDownloads ||
            "$playlistId:$itemId" in _state.value.youtubeDownloads
        ) {
            notify("Wait for the download to finish before removing its offline copy.")
            return
        }
        files.delete(localFile)
        playlists.replaceItem(playlistId, itemId, item.copy(localFile = null))
        refreshOfflineAudioState()
        notify("Offline copy removed. The song stays in your playlist.")
    }

    fun removeStoredOfflineAudio(fileName: String) {
        if (rejectOfflineAudioManagement()) return
        if (_state.value.offlineAudioFiles.none { it.fileName == fileName }) {
            notify("That offline audio file is no longer available.")
            refreshOfflineAudioState()
            return
        }
        _state.update { it.copy(offlineAudioBusy = true) }
        viewModelScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) { files.removeStoredFile(fileName) }
                if (!removed) {
                    notify("Couldn't remove that offline audio file.")
                    return@launch
                }
                val current = playlists.all()
                val updated = current.map { playlist ->
                    playlist.copy(items = playlist.items.map { item ->
                        if (item.localFile == fileName) item.copy(localFile = null) else item
                    })
                }
                if (updated != current) playlists.replaceAll(updated)
                refreshOfflineAudioState()
                notify("Offline audio removed. Any playlist song stays in your library.")
            } catch (_: Exception) {
                notify("Couldn't remove that offline audio file.")
            } finally {
                _state.update { it.copy(offlineAudioBusy = false) }
            }
        }
    }

    fun clearAllOfflineAudio() {
        if (rejectOfflineAudioManagement()) return
        val currentFiles = files.listStoredAudio()
        if (currentFiles.isEmpty()) {
            refreshOfflineAudioState()
            notify("There is no offline audio to remove.")
            return
        }
        _state.update { it.copy(offlineAudioBusy = true) }
        viewModelScope.launch {
            try {
                val removedNames = withContext(Dispatchers.IO) {
                    currentFiles.filter { files.removeStoredFile(it.name) }.mapTo(HashSet()) { it.name }
                }
                val current = playlists.all()
                val updated = current.map { playlist ->
                    playlist.copy(items = playlist.items.map { item ->
                        if (item.localFile in removedNames) item.copy(localFile = null) else item
                    })
                }
                if (updated != current) playlists.replaceAll(updated)
                refreshOfflineAudioState()
                val stillPresent = currentFiles.size - removedNames.size
                if (stillPresent == 0) notify("Removed ${removedNames.size} offline audio files. Playlist songs remain.")
                else notify("Removed ${removedNames.size} files; $stillPresent could not be deleted. Try again.")
            } catch (_: Exception) {
                notify("Couldn't clear offline audio. Try again.")
            } finally {
                _state.update { it.copy(offlineAudioBusy = false) }
            }
        }
    }

    private fun rejectOfflineAudioManagement(): Boolean {
        val current = _state.value
        when {
            current.syncBusy || current.backupBusy -> notify("Wait for the current sync or backup to finish.")
            current.offlineAudioBusy -> notify("Offline audio is already being managed.")
            current.jellyfinDownloads.isNotEmpty() || current.youtubeDownloads.isNotEmpty() ->
                notify("Wait for audio downloads to finish before managing offline storage.")
            else -> return false
        }
        return true
    }

    // ----- Automations -----

    fun saveAutomation(rule: AutomationRule): Boolean {
        if (rejectMutationWhileSyncing()) return false
        val name = rule.name.trim()
        when {
            name.isEmpty() -> { notify("Name this automation first."); return false }
            _state.value.playlists.none { it.id == rule.playlistId } -> {
                notify("Pick a playlist that still exists."); return false
            }
            !rule.hasCondition -> { notify("Set a place, a motion, or a time window."); return false }
            rule.place != null && (rule.radiusMeters < 20.0 || rule.radiusMeters > 2000.0) -> {
                notify("Trigger radius must be 20–2000 meters."); return false
            }
            (rule.fromMinute == null) != (rule.toMinute == null) -> {
                notify("Fill both time fields, or leave both empty."); return false
            }
        }
        val saved = rule.copy(name = name.take(80))
        val updated = _state.value.automations.filterNot { it.id == saved.id } + saved
        automations.replaceAll(updated)
        _state.update { it.copy(automations = updated) }
        recordCloudSyncChanges()
        notify("Automation \"${name.take(80)}\" saved${if (watchRunning) " — the watch will apply it now." else "."}")
        return true
    }

    fun deleteAutomation(id: String) {
        if (rejectMutationWhileSyncing()) return
        val updated = _state.value.automations.filterNot { it.id == id }
        automations.replaceAll(updated)
        _state.update { it.copy(automations = updated) }
        recordCloudSyncChanges()
        notify("Automation removed.")
    }

    private val watchRunning: Boolean get() = LocationWatchService.status.value.running

    // ----- Cloud sync: account auth plus client-side end-to-end encryption -----

    fun createCloudSyncAccount(server: String, email: String, password: String) {
        if (_state.value.syncBusy) return
        if (_state.value.backupBusy) {
            notify("Wait for the current backup operation to finish.")
            return
        }
        _state.update { it.copy(syncBusy = true) }
        viewModelScope.launch {
            try {
                val recoveryKey = cloudSync.createAccount(server, email, password)
                _state.update {
                    it.copy(
                        syncServer = cloudSync.serverUrl,
                        syncEffectiveServer = cloudSync.effectiveServerUrl,
                        syncUsingCustomServer = cloudSync.isUsingCustomServer,
                        syncEmail = cloudSync.accountEmail,
                        syncConnected = cloudSync.connected,
                        syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                        syncLastSyncAt = cloudSync.lastSyncAt,
                        syncPendingChanges = cloudSync.pendingChangeCount(),
                        syncFailed = false,
                        syncError = null,
                        syncRecoveryKey = recoveryKey
                    )
                }
                notify("Sync account created. Save the recovery key shown on the Connect tab before leaving this device.")
            } catch (e: Exception) {
                notify(e.message ?: "Could not create the sync account.")
            } finally {
                _state.update { it.copy(syncBusy = false) }
            }
        }
    }

    fun signInCloudSync(server: String, email: String, password: String) {
        if (_state.value.syncBusy) return
        if (_state.value.backupBusy) {
            notify("Wait for the current backup operation to finish.")
            return
        }
        _state.update { it.copy(syncBusy = true) }
        viewModelScope.launch {
            try {
                cloudSync.signIn(server, email, password)
                _state.update {
                    it.copy(
                        syncServer = cloudSync.serverUrl,
                        syncEffectiveServer = cloudSync.effectiveServerUrl,
                        syncUsingCustomServer = cloudSync.isUsingCustomServer,
                        syncEmail = cloudSync.accountEmail,
                        syncConnected = cloudSync.connected,
                        syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                        syncLastSyncAt = cloudSync.lastSyncAt,
                        syncPendingChanges = cloudSync.pendingChangeCount(),
                        syncFailed = false,
                        syncError = null,
                        syncRecoveryKey = null
                    )
                }
                notify(if (cloudSync.hasRecoveryKey) "Sync account connected." else "Account connected. Enter your saved recovery key to sync this device.")
            } catch (e: Exception) {
                notify(e.message ?: "Could not sign in to the sync account.")
            } finally {
                _state.update { it.copy(syncBusy = false) }
            }
        }
    }

    fun unlockCloudSync(rawKey: String) {
        try {
            cloudSync.unlockRecoveryKey(rawKey)
            _state.update {
                it.copy(
                    syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                    syncPendingChanges = cloudSync.pendingChangeCount(),
                    syncFailed = false,
                    syncError = null
                )
            }
            notify("Recovery key saved securely on this device. Tap Sync now to merge your library.")
        } catch (e: CloudSyncException) {
            notify(e.message ?: "That recovery key is not valid.")
        }
    }

    fun showCloudRecoveryKey() {
        val key = cloudSync.recoveryKeyForDisplay()
        if (key == null) notify("No recovery key is available on this device. Enter the key you saved when the account was created.")
        else _state.update { it.copy(syncRecoveryKey = key) }
    }

    fun forgetCloudRecoveryKey() {
        cloudSync.forgetRecoveryKey()
        _state.update { it.copy(syncHasRecoveryKey = false, syncRecoveryKey = null) }
        notify("This device's saved key was removed. Enter your recovery key again to sync.")
    }

    fun dismissCloudRecoveryKey() = _state.update { it.copy(syncRecoveryKey = null) }

    fun syncCloudData() = runCloudSync(showMessages = true)

    fun setCloudSyncAutoEnabled(enabled: Boolean) {
        cloudSync.setAutoSyncEnabled(enabled)
        _state.update { it.copy(syncAutoEnabled = enabled, syncError = null, syncFailed = false) }
        if (enabled && cloudSync.connected && cloudSync.hasRecoveryKey) runCloudSync(showMessages = false)
    }

    private fun runCloudSync(showMessages: Boolean) {
        if (_state.value.syncBusy) return
        if (_state.value.backupBusy || _state.value.offlineAudioBusy ||
            _state.value.jellyfinDownloads.isNotEmpty() || _state.value.youtubeDownloads.isNotEmpty()
        ) {
            if (showMessages) notify("Wait for the current backup or audio download to finish before syncing.")
            return
        }
        if (!cloudSync.connected) {
            if (showMessages) notify("Sign in to your sync account first.")
            return
        }
        if (!cloudSync.hasRecoveryKey) {
            if (showMessages) notify("Enter the recovery key you saved when you created the account.")
            return
        }
        _state.update { it.copy(syncBusy = true, syncError = null, syncFailed = false) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { cloudSync.sync() }
                _state.update {
                    it.copy(
                        pins = pins.all(), playlists = playlists.all(), automations = automations.all(),
                        syncConnected = cloudSync.connected,
                        syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                        syncEffectiveServer = cloudSync.effectiveServerUrl,
                        syncUsingCustomServer = cloudSync.isUsingCustomServer,
                        syncLastSyncAt = cloudSync.lastSyncAt,
                        syncPendingChanges = cloudSync.pendingChangeCount(),
                        syncFailed = false,
                        syncError = result.compactionWarning
                    )
                }
                refreshOfflineAudioState()
                if (showMessages) {
                    val warning = result.compactionWarning?.let { " History checkpoint warning: $it" }.orEmpty()
                    notify(
                        "Synced ${counted(result.pins, "pin")}, ${counted(result.playlists, "playlist")}, " +
                            "${counted(result.automations, "automation")}. " +
                            "${result.uploaded} changes sent, ${result.downloaded} received.$warning"
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = e.message ?: "Cloud sync failed. Your local data is unchanged."
                _state.update {
                    it.copy(
                        syncError = message,
                        syncFailed = true,
                        syncPendingChanges = cloudSync.pendingChangeCount()
                    )
                }
                if (showMessages) notify(message)
            } finally {
                _state.update {
                    it.copy(syncBusy = false, syncPendingChanges = cloudSync.pendingChangeCount())
                }
            }
        }
    }

    fun disconnectCloudSync() {
        if (_state.value.syncBusy) return
        _state.update { it.copy(syncBusy = true) }
        viewModelScope.launch {
            try {
                cloudSync.disconnect()
                _state.update {
                    it.copy(
                        syncConnected = false, syncHasRecoveryKey = false,
                        syncEffectiveServer = cloudSync.effectiveServerUrl,
                        syncUsingCustomServer = cloudSync.isUsingCustomServer,
                        syncRecoveryKey = null, syncLastSyncAt = cloudSync.lastSyncAt
                    )
                }
                notify("Signed out of cloud sync on this device. Local pins and playlists are untouched.")
            } finally {
                _state.update { it.copy(syncBusy = false) }
            }
        }
    }

    fun deleteCloudSyncAccount() {
        if (_state.value.syncBusy) return
        _state.update { it.copy(syncBusy = true) }
        viewModelScope.launch {
            try {
                cloudSync.deleteAccount()
                _state.update {
                    it.copy(
                        syncConnected = false, syncHasRecoveryKey = false, syncEmail = "",
                        syncEffectiveServer = cloudSync.effectiveServerUrl,
                        syncUsingCustomServer = cloudSync.isUsingCustomServer,
                        syncRecoveryKey = null, syncLastSyncAt = 0L, syncPendingChanges = 0,
                        syncFailed = false, syncError = null
                    )
                }
                notify("Sync account and remote encrypted history deleted. This device's library is untouched.")
            } catch (e: Exception) {
                notify(e.message ?: "Could not delete the sync account.")
            } finally {
                _state.update { it.copy(syncBusy = false) }
            }
        }
    }

    private fun buildOfflineAudioItems(
        playlistSnapshot: List<Playlist>,
        storedFiles: List<StoredAudioFile> = files.listStoredAudio()
    ): List<OfflineAudioItem> {
        val linked = mutableMapOf<String, Pair<PlaylistItem, String>>()
        playlistSnapshot.forEach { playlist ->
            playlist.items.forEach { item ->
                item.localFile?.let { name ->
                    if (name !in linked) linked[name] = item to playlist.name
                }
            }
        }
        return storedFiles.map { stored ->
            val reference = linked[stored.name]
            val item = reference?.first
            val playlistName = reference?.second
            OfflineAudioItem(
                fileName = stored.name,
                title = item?.title ?: "Unlinked audio file",
                detail = if (item == null) "Not attached to a playlist song" else
                    listOf(item.artist.ifBlank { "Unknown artist" }, playlistName.orEmpty())
                        .filter(String::isNotBlank).joinToString(" · "),
                sizeBytes = stored.sizeBytes
            )
        }
    }

    private fun refreshOfflineAudioState() {
        val currentPlaylists = playlists.all()
        val storedFiles = files.listStoredAudio()
        _state.update {
            it.copy(
                playlists = currentPlaylists,
                offlineAudioFiles = buildOfflineAudioItems(currentPlaylists, storedFiles),
                audioStorageBytes = storedFiles.sumOf { file -> file.sizeBytes }
            )
        }
    }

    private fun recordCloudSyncChanges() {
        // Cloud sync is opt-in. Before sign-in (or while signed out), keep no duplicate journal;
        // the first sync seeds local data and later syncs diff against the last applied snapshot.
        if (!cloudSync.connected) return
        runCatching { cloudSync.observeLocalChanges() }
        _state.update { it.copy(syncPendingChanges = cloudSync.pendingChangeCount()) }
    }

    // ----- Backup: export and import of everything local -----

    /**
     * Writes every pin, playlist, and automation to the file the user just picked.
     * The file goes wherever the system file picker sends it; nothing is uploaded.
     */
    fun exportBackup(uri: Uri) {
        if (_state.value.syncBusy || _state.value.backupBusy || _state.value.offlineAudioBusy ||
            _state.value.jellyfinDownloads.isNotEmpty() || _state.value.youtubeDownloads.isNotEmpty()
        ) {
            notify("Wait for the current sync, backup, or audio operation to finish.")
            return
        }
        _state.update { it.copy(backupBusy = true) }
        viewModelScope.launch {
            try {
                val currentPins = pins.all()
                val currentPlaylists = playlists.all()
                val currentRules = automations.all()
                val text = withContext(Dispatchers.IO) {
                    BackupFormat.export(currentPins, currentPlaylists, currentRules)
                }
                val written = withContext(Dispatchers.IO) { writeText(uri, text) }
                if (written) {
                    notify(
                        "Backup saved — ${counted(currentPins.size, "pin")}, " +
                            "${counted(currentPlaylists.size, "playlist")}, " +
                            "${counted(currentRules.size, "automation")}."
                    )
                } else {
                    notify("Couldn't write that file. Try a different location.")
                }
            } catch (e: Exception) {
                notify(e.message ?: "Couldn't export a backup.")
            } finally {
                _state.update { it.copy(backupBusy = false) }
            }
        }
    }

    /**
     * Reads a backup and merges it into what is already here. Ids that exist are kept
     * locally, so importing twice (or importing an older file) never loses an edit.
     * Audio files are not carried by backups; a reference to a file this device does not
     * have is dropped rather than imported broken.
     */
    fun importBackup(uri: Uri) {
        if (rejectMutationWhileSyncing() || _state.value.backupBusy ||
            _state.value.jellyfinDownloads.isNotEmpty() || _state.value.youtubeDownloads.isNotEmpty()
        ) return
        _state.update { it.copy(backupBusy = true) }
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { readText(uri) }
                    ?: throw BackupException("Couldn't read that file.")
                val brought = withContext(Dispatchers.IO) { BackupFormat.parse(text) }
                val restored = brought.copy(
                    playlists = brought.playlists.map { playlist ->
                        playlist.copy(items = playlist.items.map { item ->
                            item.copy(localFile = item.localFile?.takeIf(files::exists))
                        })
                    }
                )
                val addedPins = pins.merge(restored.pins)
                val (addedPlaylists, addedSongs) = playlists.merge(restored.playlists)
                val addedRules = automations.merge(restored.automations)
                _state.update {
                    it.copy(
                        pins = pins.all(),
                        playlists = playlists.all(),
                        automations = automations.all()
                    )
                }
                refreshOfflineAudioState()
                recordCloudSyncChanges()
                val total = addedPins + addedPlaylists + addedSongs + addedRules
                if (total == 0) {
                    notify("Nothing new in that backup — everything it holds is already on this device.")
                } else {
                    notify(
                        "Imported ${counted(addedPins, "new pin")}, ${counted(addedPlaylists, "playlist")}, " +
                            "${counted(addedSongs, "song")}, and ${counted(addedRules, "automation")}. " +
                            "Items already on this device were kept."
                    )
                }
            } catch (e: BackupException) {
                notify(e.message ?: "That file is not a Soundtrail backup.")
            } catch (e: Exception) {
                notify("Couldn't import that backup.")
            } finally {
                _state.update { it.copy(backupBusy = false) }
            }
        }
    }

    private fun counted(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    private fun writeText(uri: Uri, text: String): Boolean {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            // "wt" truncates a pre-existing file; not every provider accepts a mode, so fall back.
            val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri)
                ?: error("No writable stream for that file.")
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
    }

    private fun readText(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.size > BackupFormat.MAX_CHARS * 4) {
            throw BackupException("That file is too large to be a Soundtrail backup.")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun addPin(input: PinInput): Boolean {
        if (rejectMutationWhileSyncing()) return false
        val link = MusicLinkParser.fromText(input.url)
        val point = input.point
        when {
            input.title.isBlank() -> { notify("Add a song title first."); return false }
            link == null -> {
                notify("Use a Spotify, SoundCloud, YouTube Music, or Jellyfin song link.")
                return false
            }
            point == null || !point.isValid() -> {
                notify("Find your location or enter valid coordinates."); return false
            }
        }
        val pin = MusicPin(
            id = UUID.randomUUID().toString(),
            title = input.title.trim().take(120),
            artist = input.artist.trim().take(120),
            place = input.place.trim().ifBlank { "Somewhere special" }.take(100),
            note = input.note.trim().take(280),
            url = link.url,
            provider = link.provider,
            point = point,
            createdAt = System.currentTimeMillis()
        )
        return try {
            val updated = (_state.value.pins + pin).sortedByDescending { it.createdAt }
            pins.replaceAll(updated)
            _state.update { it.copy(pins = updated) }
            recordCloudSyncChanges()
            notify("Pinned ${pin.title} to ${pin.place}.")
            true
        } catch (_: Exception) {
            notify("Couldn't save this pin. Please try again.")
            false
        }
    }

    fun deletePin(id: String) {
        if (rejectMutationWhileSyncing()) return
        try {
            val updated = _state.value.pins.filterNot { it.id == id }
            pins.replaceAll(updated)
            _state.update { it.copy(pins = updated) }
            recordCloudSyncChanges()
            notify("Pin removed.")
        } catch (_: Exception) {
            notify("Couldn't remove that pin.")
        }
    }
}
