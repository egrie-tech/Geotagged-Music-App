package tech.egrie.soundtrail

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import tech.egrie.soundtrail.automation.LocationWatchService
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.integrations.DeviceApps
import tech.egrie.soundtrail.integrations.MusicLink
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.MusicProvider
import tech.egrie.soundtrail.integrations.YouTubeDataService
import tech.egrie.soundtrail.integrations.YouTubeSearch
import tech.egrie.soundtrail.location.DeviceLocation
import tech.egrie.soundtrail.ui.ListsActions
import tech.egrie.soundtrail.ui.Palette
import tech.egrie.soundtrail.ui.PinDraft
import tech.egrie.soundtrail.ui.SoundtrailApp
import tech.egrie.soundtrail.ui.SoundtrailTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var model: MainViewModel
    private lateinit var apps: DeviceApps
    private var incomingDraft by mutableStateOf<PinDraft?>(null)
    private var listsOpenSignal by mutableStateOf(0)

    private val youtubeSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data == null) {
            if (result.resultCode == Activity.RESULT_OK) model.notify("Google services returned no sign-in result.")
            return@registerForActivityResult
        }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            model.connectYouTubeData(account)
        } catch (e: ApiException) {
            if (e.statusCode != GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
                model.notify(
                    "Google sign-in failed (${e.statusCode}). Check the Android OAuth setup and " +
                        "that Google Play services or compatible microG is available."
                )
            }
        } catch (e: Exception) {
            model.notify(e.message ?: "Could not finish Google sign-in.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Palette.background.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Palette.background.toArgb())
        )
        val provider = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))
        model = provider[MainViewModel::class.java]
        apps = DeviceApps(this)
        if (savedInstanceState == null) handleIncoming(intent)

        setContent {
            val state by model.state.collectAsState()
            val nowPlaying by model.player.state.collectAsState()
            val permissionRequest = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                if (grants.values.any { it }) model.locate()
                else model.notify("Location not allowed. You can enter coordinates manually.")
            }
            val watchPermissions = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val located = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
                if (located) startWatch()
                else model.notify("Location permission is needed to watch for triggers.")
            }
            // Picking the user's own audio file for a playlist song (imported, plays offline).
            var importTarget by mutableStateOf<Pair<String, PlaylistItem>?>(null)
            val audioPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                val (playlistId, item) = importTarget ?: return@rememberLauncherForActivityResult
                importTarget = null
                if (uri != null) model.attachLocalAudio(uri, playlistId, item.id)
            }
            val toggleWatch: (Boolean) -> Unit = { enable ->
                if (!enable) {
                    LocationWatchService.stop(this)
                    model.notify("Watch stopped. Automations stay saved but won't fire.")
                } else {
                    val needed = buildList {
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.filter {
                        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (needed.isEmpty()) startWatch() else watchPermissions.launch(needed.toTypedArray())
                }
            }
            val onLocate: () -> Unit = {
                if (DeviceLocation(this).hasPermission()) model.locate()
                else permissionRequest.launch(arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ))
            }
            // The backup file is chosen and written/read entirely through the system picker.
            val exportBackupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri -> if (uri != null) model.exportBackup(uri) }
            val importBackupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) model.importBackup(uri) }
            SoundtrailTheme {
                SoundtrailApp(
                    state = state,
                    messages = model.messages,
                    incomingDraft = incomingDraft,
                    onIncomingConsumed = { incomingDraft = null },
                    openListsSignal = listsOpenSignal,
                    nowPlaying = nowPlaying,
                    lists = ListsActions(
                        onToggleWatch = toggleWatch,
                        onToggleAutoSearch = model::setAutoSearchYoutube,
                        onNewPlaylist = model::createPlaylist,
                        onDeletePlaylist = model::deletePlaylist,
                        onAddSong = { playlistId, title, artist, url ->
                            val search = model.addToPlaylist(playlistId, title, artist, url)
                            if (search != null && !apps.openYouTubeMusicSearch(search)) {
                                model.notify("No YouTube Music app or browser found.")
                            }
                        },
                        onRemoveSong = model::removePlaylistItem,
                        onPlaySong = ::playPlaylistItem,
                        onSearchSong = { item ->
                            if (!apps.searchYouTubeMusic(item.title, item.artist)) {
                                model.notify("No YouTube Music app or browser found.")
                            }
                        },
                        onGetSong = { item ->
                            // This external action shares a link only; the separate YouTube offline
                            // action uses the self-hosted audio endpoint after rights confirmation.
                            val link = item.url?.let(MusicLinkParser::parse)
                            val target = if (link != null && link.provider == MusicProvider.JELLYFIN) {
                                model.jellyfinWebUrl(link) ?: link.url
                            } else {
                                item.url ?: YouTubeSearch.searchUrl(item.title, item.artist)
                            }
                            if (!apps.shareUrl(target)) model.notify("Nothing can receive this link.")
                        },
                        onDownloadJellyfin = model::downloadJellyfinOffline,
                        onDownloadYouTube = model::downloadYouTubeOffline,
                        onRemoveOfflineAudio = model::removeOfflineAudio,
                        onRemoveOfflineFile = model::removeStoredOfflineAudio,
                        onClearAllOfflineAudio = model::clearAllOfflineAudio,
                        onImportAudio = { playlistId, item ->
                            importTarget = playlistId to item
                            try {
                                audioPicker.launch(arrayOf("audio/*"))
                            } catch (e: Exception) {
                                importTarget = null
                                model.notify("No file picker is available on this device.")
                            }
                        },
                        onSaveRule = { rule -> model.saveAutomation(rule) },
                        onDeleteRule = model::deleteAutomation
                    ),
                    onLocate = onLocate,
                    onSearch = model::search,
                    onSelectSearchProvider = model::selectSearchProvider,
                    onLookup = model::lookupSong,
                    onConnect = ::connectSpotify,
                    onDisconnect = model::disconnectSpotify,
                    onConnectSoundcloud = ::connectSoundcloud,
                    onDisconnectSoundcloud = model::disconnectSoundcloud,
                    onConnectJellyfin = model::connectJellyfin,
                    onDisconnectJellyfin = model::disconnectJellyfin,
                    onOpenJellyfin = { if (!apps.openWeb(model.jellyfinServerUrl())) model.notify("No browser found.") },
                    onConnectYouTubeData = ::connectYouTubeData,
                    onDisconnectYouTubeData = model::disconnectYouTubeData,
                    backupBusy = state.backupBusy,
                    onExportBackup = {
                        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                        try {
                            exportBackupLauncher.launch("soundtrail-backup-$stamp.json")
                        } catch (e: Exception) {
                            model.notify("No file picker is available on this device.")
                        }
                    },
                    onImportBackup = {
                        try {
                            importBackupLauncher.launch(
                                arrayOf("application/json", "text/plain", "application/octet-stream")
                            )
                        } catch (e: Exception) {
                            model.notify("No file picker is available on this device.")
                        }
                    },
                    onCreateCloudSyncAccount = model::createCloudSyncAccount,
                    onSignInCloudSync = model::signInCloudSync,
                    onUnlockCloudSync = model::unlockCloudSync,
                    onSyncCloudData = model::syncCloudData,
                    onShowCloudRecoveryKey = model::showCloudRecoveryKey,
                    onForgetCloudRecoveryKey = model::forgetCloudRecoveryKey,
                    onDismissCloudRecoveryKey = model::dismissCloudRecoveryKey,
                    onSetCloudSyncAutoEnabled = model::setCloudSyncAutoEnabled,
                    onDisconnectCloudSync = model::disconnectCloudSync,
                    onDeleteCloudSyncAccount = model::deleteCloudSyncAccount,
                    onOpenYouTubeSetup = {
                        if (!apps.openWeb("https://console.cloud.google.com/apis/credentials")) {
                            model.notify("No browser found.")
                        }
                    },
                    onTogglePlayback = model::togglePlayback,
                    onStopPlayback = model::stopPlayback,
                    onPlay = ::play,
                    onMap = ::showMap,
                    onDelete = model::deletePin,
                    onOpenSpotify = { if (!apps.openSpotify()) model.notify("No app can open Spotify.") },
                    onOpenYoutube = { if (!apps.openYouTubeMusic()) model.notify("No app can open YouTube Music.") },
                    onSearchYoutubeMusic = { query ->
                        if (!apps.searchYouTubeMusic(query)) {
                            model.notify("No YouTube Music app or browser found.")
                        }
                    },
                    onOpenYoutubeLibrary = {
                        if (!apps.openYouTubeMusicLibrary()) {
                            model.notify("No YouTube Music app or browser found.")
                        }
                    },
                    onOpenSoundcloud = { if (!apps.openSoundCloud()) model.notify("No app can open SoundCloud.") },
                    onOpenDashboard = {
                        if (!apps.openWeb("https://developer.spotify.com/dashboard")) model.notify("No browser found.")
                    },
                    onOpenMicroG = {
                        if (!apps.openWeb("https://microg.org/")) model.notify("No browser found.")
                    },
                    onOpenSoundcloudApps = {
                        if (!apps.openWeb("https://developers.soundcloud.com/docs/api/register-app")) {
                            model.notify("No browser found.")
                        }
                    },
                    onSavePin = model::addPin,
                    onNotice = model::notify
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.refreshDeviceStatus()
    }

    // Deliberately no stopPlayback() in onDestroy: Jellyfin streaming now continues in the
    // background under PlaybackService's notification. The bar's Stop button, the
    // notification's Stop action, or disconnecting the server ends it.

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun startWatch() {
        LocationWatchService.start(this)
        model.notify("Watching place, motion, and time from the notification. Tap a trigger to play.")
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.hasExtra(LocationWatchService.EXTRA_OPEN_LISTS) == true) listsOpenSignal++
        intent?.getStringExtra(LocationWatchService.EXTRA_PLAY_PLAYLIST)?.let { playlistId ->
            listsOpenSignal++
            val first = model.state.value.playlists.find { it.id == playlistId }?.items?.firstOrNull()
            if (first == null) model.notify("That playlist is empty or missing.")
            else playPlaylistItem(first)
        }
        when (intent?.action) {
            Intent.ACTION_VIEW -> if (intent.data?.scheme == "app.soundtrail") {
                intent.data?.let { uri ->
                    when (uri.host) {
                        "spotify-auth" -> model.finishSpotify(uri)
                        "soundcloud-auth" -> model.finishSoundcloud(uri)
                    }
                }
                clearHandledIntent()
            }
            Intent.ACTION_SEND -> if (intent.type == "text/plain") {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val link = MusicLinkParser.fromText(text)
                if (link != null) incomingDraft = PinDraft(url = link.url)
                else model.notify("Share a Spotify, SoundCloud, or YouTube Music song link.")
                clearHandledIntent()
            }
        }
    }

    private fun clearHandledIntent() {
        // Avoid consuming a single-use OAuth code or share intent again on activity recreation.
        setIntent(Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN })
    }

    private fun connectSpotify(id: String) {
        try {
            val uri = model.beginSpotify(id)
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            model.cancelSpotify()
            model.notify("Install a browser to sign in with Spotify.")
        } catch (e: SecurityException) {
            model.cancelSpotify()
            model.notify("Could not open Spotify sign-in in a browser.")
        } catch (e: Exception) {
            model.notify(e.message ?: "Could not start Spotify sign-in.")
        }
    }

    private fun connectSoundcloud(id: String, secret: String) {
        try {
            val uri = model.beginSoundcloud(id, secret)
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            model.cancelSoundcloud()
            model.notify("Install a browser to sign in with SoundCloud.")
        } catch (e: SecurityException) {
            model.cancelSoundcloud()
            model.notify("Could not open SoundCloud sign-in in a browser.")
        } catch (e: Exception) {
            model.notify(e.message ?: "Could not start SoundCloud sign-in.")
        }
    }

    private fun connectYouTubeData() {
        if (model.state.value.connectingYouTubeData) return
        try {
            val client = GoogleSignIn.getClient(this, YouTubeDataService.signInOptions())
            youtubeSignInLauncher.launch(client.signInIntent)
        } catch (e: Exception) {
            model.notify(
                "Could not start Google sign-in. Install or configure Google Play services or " +
                    "a compatible microG build."
            )
        }
    }

    /** Jellyfin songs stream inside Soundtrail; everything else is handed to the user's apps. */
    private fun play(pin: MusicPin) {
        val link = MusicLink(pin.provider, pin.url)
        if (link.provider == MusicProvider.JELLYFIN) {
            model.playJellyfin(link, pin.title, pin.artist)
            return
        }
        if (!apps.play(link)) {
            model.notify("No app can open this song link.")
        }
    }

    /** Plays locally imported audio first; then the linked song; else a YouTube Music search. */
    private fun playPlaylistItem(item: PlaylistItem) {
        if (item.localFile != null && apps.playLocalFile(item.localFile)) return
        val url = item.url
        if (url != null) {
            val link = MusicLinkParser.parse(url)
            if (link != null && link.provider == MusicProvider.JELLYFIN) {
                model.playJellyfin(link, item.title, item.artist)
                return
            }
            if ((link != null && apps.play(link)) || apps.openWeb(url)) return
        }
        if (!apps.searchYouTubeMusic(item.title, item.artist)) {
            model.notify("No YouTube Music app or browser found.")
        }
    }

    private fun showMap(point: GeoPoint) {
        if (!apps.showOnMap(point)) model.notify("No maps app or browser found.")
    }
}
