package tech.egrie.soundtrail

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.lifecycle.ViewModelProvider
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.integrations.DeviceApps
import tech.egrie.soundtrail.integrations.MusicLink
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.location.DeviceLocation
import tech.egrie.soundtrail.ui.Palette
import tech.egrie.soundtrail.ui.PinDraft
import tech.egrie.soundtrail.ui.SoundtrailApp
import tech.egrie.soundtrail.ui.SoundtrailTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: MainViewModel
    private lateinit var apps: DeviceApps
    private var incomingDraft by mutableStateOf<PinDraft?>(null)

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
            val permissionRequest = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                if (grants.values.any { it }) model.locate()
                else model.notify("Location not allowed. You can enter coordinates manually.")
            }
            val onLocate: () -> Unit = {
                if (DeviceLocation(this).hasPermission()) model.locate()
                else permissionRequest.launch(arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ))
            }
            SoundtrailTheme {
                SoundtrailApp(
                    state = state,
                    messages = model.messages,
                    incomingDraft = incomingDraft,
                    onIncomingConsumed = { incomingDraft = null },
                    onLocate = onLocate,
                    onSearch = model::search,
                    onConnect = ::connectSpotify,
                    onDisconnect = model::disconnectSpotify,
                    onPlay = ::play,
                    onMap = ::showMap,
                    onDelete = model::deletePin,
                    onOpenSpotify = { if (!apps.openSpotify()) model.notify("No app can open Spotify.") },
                    onOpenYoutube = { if (!apps.openYouTubeMusic()) model.notify("No app can open YouTube Music.") },
                    onOpenDashboard = {
                        if (!apps.openWeb("https://developer.spotify.com/dashboard")) model.notify("No browser found.")
                    },
                    onOpenMicroG = {
                        if (!apps.openWeb("https://microg.org/")) model.notify("No browser found.")
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> if (intent.data?.scheme == "app.soundtrail") {
                intent.data?.let(model::finishSpotify)
                clearHandledIntent()
            }
            Intent.ACTION_SEND -> if (intent.type == "text/plain") {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val link = MusicLinkParser.fromText(text)
                if (link != null) incomingDraft = PinDraft(url = link.url)
                else model.notify("Share a Spotify track or YouTube Music song link.")
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

    private fun play(pin: MusicPin) {
        if (!apps.play(MusicLink(pin.provider, pin.url))) {
            model.notify("No app can open this song link.")
        }
    }

    private fun showMap(point: GeoPoint) {
        if (!apps.showOnMap(point)) model.notify("No maps app or browser found.")
    }
}
