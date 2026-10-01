package tech.egrie.soundtrail

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import tech.egrie.soundtrail.data.FileStore
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.PinStore
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.data.PlaylistStore
import tech.egrie.soundtrail.integrations.AudioDbService
import tech.egrie.soundtrail.integrations.DeviceApps
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.SongInfo
import tech.egrie.soundtrail.integrations.SoundCloudService
import tech.egrie.soundtrail.integrations.SpotifyService
import tech.egrie.soundtrail.integrations.TrackResult
import tech.egrie.soundtrail.integrations.YouTubeSearch
import tech.egrie.soundtrail.location.DeviceLocation
import java.util.UUID

/** Which connected catalog the Search tab queries. */
internal enum class SearchProvider(val label: String) { SPOTIFY("Spotify"), SOUNDCLOUD("SoundCloud") }

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
    val connecting: Boolean = false,
    val connectingSoundcloud: Boolean = false,
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
    val watchStatus: tech.egrie.soundtrail.automation.WatchStatus = tech.egrie.soundtrail.automation.WatchStatus(),
    val spotifyAppInstalled: Boolean = false,
    val soundcloudAppInstalled: Boolean = false,
    val youtubeAppInstalled: Boolean = false,
    val servicesLabel: String = "Checking services…"
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
    private val audioDb = AudioDbService()
    private val locationReader = DeviceLocation(application)
    private val apps = DeviceApps(application)

    private val _state = MutableStateFlow(
        AppState(
            pins = pins.all(),
            playlists = playlists.all(),
            automations = automations.all(),
            spotifyConnected = spotify.connected,
            spotifyClientId = spotify.clientId,
            soundcloudConnected = soundcloud.connected,
            soundcloudClientId = soundcloud.clientId
        )
    )
    val state = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        refreshDeviceStatus()
        if (spotify.connected) loadProfile()
        if (soundcloud.connected) loadSoundcloudProfile()
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
                spotifyConnected = spotify.connected,
                soundcloudConnected = soundcloud.connected
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
                notify("Location ready. Only saved pins stay on this device.")
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
                }
                _state.update { it.copy(results = tracks) }
            } catch (e: Exception) {
                _state.update { it.copy(searchError = e.message ?: "Search is unavailable.") }
            } finally {
                _state.update {
                    it.copy(
                        searching = false,
                        spotifyConnected = spotify.connected,
                        soundcloudConnected = soundcloud.connected
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

    // ----- Playlists -----

    fun createPlaylist(rawName: String) {
        val name = rawName.trim().take(80)
        when {
            name.isEmpty() -> notify("Give the playlist a name first.")
            playlists.all().any { it.name.equals(name, ignoreCase = true) } ->
                notify("A playlist called \"$name\" already exists.")
            else -> {
                playlists.save(
                    tech.egrie.soundtrail.data.Playlist(
                        id = UUID.randomUUID().toString(), name = name,
                        items = emptyList(), createdAt = System.currentTimeMillis()
                    )
                )
                _state.update { it.copy(playlists = playlists.all()) }
                notify("Playlist \"$name\" created.")
            }
        }
    }

    fun deletePlaylist(id: String) {
        // Imported audio belongs to the song; it leaves with the playlist.
        playlists.find(id)?.items?.forEach { item -> item.localFile?.let(files::delete) }
        playlists.remove(id)
        // Rules pointing at a deleted playlist would never fire again.
        automations.all().filter { it.playlistId == id }.forEach { automations.remove(it.id) }
        _state.update { it.copy(playlists = playlists.all(), automations = automations.all()) }
        notify("Playlist removed.")
    }

    /**
     * Adds a song and, when auto-search is on, returns the YouTube *search* URL to open.
     * Soundtrail never downloads; playback and saving stay in YouTube's own apps.
     */
    fun addToPlaylist(playlistId: String, rawTitle: String, rawArtist: String, rawUrl: String): String? {
        val title = rawTitle.trim().take(120)
        val artist = rawArtist.trim().take(120)
        val link = rawUrl.trim().takeIf(String::isNotBlank)?.let(MusicLinkParser::fromText)
        when {
            title.isEmpty() -> { notify("Add a song title first."); return null }
            playlists.find(playlistId) == null -> { notify("That playlist no longer exists."); return null }
            rawUrl.trim().isNotBlank() && link == null -> {
                notify("That link is not a supported song URL. Leave it empty to just search YouTube.")
                return null
            }
        }
        val added = playlists.addItem(
            playlistId,
            PlaylistItem(
                id = UUID.randomUUID().toString(), title = title, artist = artist,
                url = link?.url, provider = link?.provider, createdAt = System.currentTimeMillis()
            )
        )
        if (!added) return null
        _state.update { it.copy(playlists = playlists.all()) }
        notify("Added \"$title\" to ${playlists.find(playlistId)?.name.orEmpty()}.")
        return if (_state.value.autoSearchYoutube) YouTubeSearch.searchUrl(title, artist) else null
    }

    fun removePlaylistItem(playlistId: String, itemId: String) {
        playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }?.localFile?.let(files::delete)
        playlists.removeItem(playlistId, itemId)
        _state.update { it.copy(playlists = playlists.all()) }
        notify("Song removed.")
    }

    /** Attaches a user-picked audio file (their own download/import) to a playlist song. */
    fun attachLocalAudio(uri: Uri, playlistId: String, itemId: String) {
        viewModelScope.launch {
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
            val replaced = playlists.replaceItem(
                playlistId, itemId, item.copy(localFile = name)
            )
            _state.update { it.copy(playlists = playlists.all()) }
            notify(if (replaced) "Audio attached — \"${item.title}\" now plays from this device." else "Couldn't attach the audio file.")
        }
    }

    fun setAutoSearchYoutube(enabled: Boolean) {
        _state.update { it.copy(autoSearchYoutube = enabled) }
    }

    // ----- Automations -----

    fun saveAutomation(rule: AutomationRule): Boolean {
        val name = rule.name.trim()
        when {
            name.isEmpty() -> { notify("Name this automation first."); return false }
            playlists.find(rule.playlistId) == null -> { notify("Pick a playlist that still exists."); return false }
            !rule.hasCondition -> { notify("Set a place, a motion, or a time window."); return false }
            rule.place != null && (rule.radiusMeters < 20.0 || rule.radiusMeters > 2000.0) -> {
                notify("Trigger radius must be 20–2000 meters."); return false
            }
            (rule.fromMinute == null) != (rule.toMinute == null) -> {
                notify("Fill both time fields, or leave both empty."); return false
            }
        }
        automations.save(rule.copy(name = name.take(80)))
        _state.update { it.copy(automations = automations.all()) }
        notify("Automation \"${name.take(80)}\" saved${if (watchRunning) " — the watch will apply it now." else "."}")
        return true
    }

    fun deleteAutomation(id: String) {
        automations.remove(id)
        _state.update { it.copy(automations = automations.all()) }
        notify("Automation removed.")
    }

    private val watchRunning: Boolean get() = LocationWatchService.status.value.running

    fun addPin(input: PinInput): Boolean {
        val link = MusicLinkParser.fromText(input.url)
        val point = input.point
        when {
            input.title.isBlank() -> { notify("Add a song title first."); return false }
            link == null -> {
                notify("Use a Spotify, SoundCloud, or YouTube Music song link.")
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
            pins.add(pin)
            _state.update { it.copy(pins = pins.all()) }
            notify("Pinned ${pin.title} to ${pin.place}.")
            true
        } catch (_: Exception) {
            notify("Couldn't save this pin. Please try again.")
            false
        }
    }

    fun deletePin(id: String) {
        try {
            pins.remove(id)
            _state.update { it.copy(pins = pins.all()) }
            notify("Pin removed.")
        } catch (_: Exception) {
            notify("Couldn't remove that pin.")
        }
    }
}
