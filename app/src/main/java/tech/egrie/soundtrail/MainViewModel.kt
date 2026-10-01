package tech.egrie.soundtrail

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.PinStore
import tech.egrie.soundtrail.integrations.DeviceApps
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.SoundCloudService
import tech.egrie.soundtrail.integrations.SpotifyService
import tech.egrie.soundtrail.integrations.TrackResult
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
    private val spotify = SpotifyService(application)
    private val soundcloud = SoundCloudService(application)
    private val locationReader = DeviceLocation(application)
    private val apps = DeviceApps(application)

    private val _state = MutableStateFlow(
        AppState(
            pins = pins.all(),
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
