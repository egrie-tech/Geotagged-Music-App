package tech.egrie.soundtrail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import tech.egrie.soundtrail.AppState
import tech.egrie.soundtrail.PinInput
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.integrations.SpotifyTrack
import java.io.Serializable
import java.util.Locale

internal data class PinDraft(
    val nonce: Long = System.nanoTime(),
    val title: String = "",
    val artist: String = "",
    val url: String = ""
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

private enum class Tab(val label: String) { HOME("Explore"), SEARCH("Search"), PINS("Pins"), CONNECT("Connect") }

@Composable
internal fun SoundtrailApp(
    state: AppState,
    messages: Flow<String>,
    incomingDraft: PinDraft?,
    onIncomingConsumed: () -> Unit,
    onLocate: () -> Unit,
    onSearch: (String) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onPlay: (MusicPin) -> Unit,
    onMap: (GeoPoint) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenYoutube: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenMicroG: () -> Unit,
    onSavePin: (PinInput) -> Boolean,
    onNotice: (String) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // Keep an unfinished pin editor intact across rotation and process recreation.
    var draft by rememberSaveable { mutableStateOf<PinDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<MusicPin?>(null) }
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(messages) { messages.collectLatest { snackbars.showSnackbar(it) } }
    LaunchedEffect(incomingDraft?.nonce) {
        if (incomingDraft != null) {
            draft = incomingDraft
            onIncomingConsumed()
        }
    }
    BackHandler(draft != null) { draft = null }

    pendingDelete?.let { pin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove this pin?") },
            text = { Text("${pin.title} will be removed from this device. Your music account is not affected.") },
            confirmButton = {
                TextButton(onClick = { onDelete(pin.id); pendingDelete = null }) {
                    Text("Remove", color = Palette.coral)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep it") }
            },
            containerColor = Palette.elevated
        )
    }

    Scaffold(
        containerColor = Palette.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
            SnackbarHost(snackbars) { data ->
                Snackbar(data, containerColor = Palette.elevated, contentColor = Palette.text)
            }
        },
        bottomBar = { if (draft == null) BottomTabs(tab) { tab = it } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val editor = draft
            if (editor != null) {
                PinEditor(
                    draft = editor,
                    currentLocation = state.location,
                    locating = state.locating,
                    onBack = { draft = null },
                    onLocate = onLocate,
                    onSave = { input ->
                        if (onSavePin(input)) { draft = null; tab = Tab.PINS; true } else false
                    }
                )
            } else when (tab) {
                Tab.HOME -> HomeScreen(
                    state, onNewPin = { draft = PinDraft() }, onLocate = onLocate,
                    onSeePins = { tab = Tab.PINS }, onConnections = { tab = Tab.CONNECT },
                    onPlay = onPlay, onMap = onMap, onDelete = { pendingDelete = it }
                )
                Tab.SEARCH -> MusicSearchScreen(
                    state, onSearch, onConnect = { tab = Tab.CONNECT },
                    onSelectTrack = { draft = PinDraft(title = it.title, artist = it.artist, url = it.url) },
                    onAddLink = { draft = PinDraft(url = it) },
                    onOpenYoutube = onOpenYoutube, onNotice = onNotice
                )
                Tab.PINS -> PinsScreen(
                    state, onNewPin = { draft = PinDraft() }, onLocate = onLocate,
                    onPlay = onPlay, onMap = onMap, onDelete = { pendingDelete = it }
                )
                Tab.CONNECT -> ConnectionsScreen(
                    state, onConnect, onDisconnect, onOpenSpotify, onOpenYoutube,
                    onOpenDashboard, onOpenMicroG
                )
            }
        }
    }
}

@Composable
private fun BottomTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.background).navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Tab.entries.forEach { tab ->
            val selectedTab = tab == selected
            val icon = when (tab) {
                Tab.HOME -> Icons.Rounded.Home
                Tab.SEARCH -> Icons.Rounded.Search
                Tab.PINS -> Icons.Rounded.Bookmark
                Tab.CONNECT -> Icons.Rounded.Settings
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (selectedTab) Palette.elevated else Color.Transparent)
                    .clickable { onSelect(tab) }.padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = tab.label,
                    tint = if (selectedTab) Palette.primary else Palette.muted,
                    modifier = Modifier.size(23.dp))
                Spacer(Modifier.height(3.dp))
                Text(tab.label, color = if (selectedTab) Palette.text else Palette.muted,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: AppState,
    onNewPin: () -> Unit,
    onLocate: () -> Unit,
    onSeePins: () -> Unit,
    onConnections: () -> Unit,
    onPlay: (MusicPin) -> Unit,
    onMap: (GeoPoint) -> Unit,
    onDelete: (MusicPin) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(22.dp))
        BrandHeader()
        Spacer(Modifier.height(27.dp))
        HomeHero(onNewPin)
        Spacer(Modifier.height(18.dp))
        LocationBanner(state.location, state.locating, onLocate)
        Spacer(Modifier.height(27.dp))
        SectionHeading(if (state.location != null) "Around you" else "Your soundspots", "View all", onSeePins)
        Spacer(Modifier.height(10.dp))
        val nearby = state.location?.let { point ->
            state.pins.sortedBy { point.distanceTo(it.point) }
        } ?: state.pins
        if (nearby.isEmpty()) {
            SurfaceCard(Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.LocationOn, null, tint = Palette.primary,
                    modifier = Modifier.size(29.dp))
                Spacer(Modifier.height(12.dp))
                Text("No pins yet. Every place has a first song.",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Find a track or share a song link, then save it to a place you love.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.secondary)
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Drop your first pin", onNewPin)
            }
        } else {
            nearby.take(2).forEach { pin ->
                PinCard(pin, state.location?.distanceTo(pin.point),
                    onPlay = { onPlay(pin) }, onMap = { onMap(pin.point) }, onDelete = { onDelete(pin) })
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        SurfaceCard(Modifier.fillMaxWidth()) {
            Eyebrow("YOUR MUSIC, YOUR WAY", Palette.lilac)
            Spacer(Modifier.height(9.dp))
            Text("Two worlds. One map.", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("Search with Spotify, or pin a YouTube Music link. Playback stays in your music app.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.secondary)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onConnections) { Text("Manage connections  →", color = Palette.primary) }
        }
        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun HomeHero(onNewPin: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(313.dp).clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF344238), Color(0xFF242D29), Color(0xFF20252C))))
    ) {
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width * .88f, size.height * .36f)
            for (radius in listOf(.17f, .31f, .45f)) {
                drawCircle(Palette.primary.copy(alpha = .16f), radius = size.width * radius,
                    center = center, style = Stroke(width = 1.dp.toPx()))
            }
            drawCircle(Palette.primary.copy(alpha = .35f), radius = 6.dp.toPx(),
                center = Offset(size.width * .73f, size.height * .18f))
            drawCircle(Palette.coral.copy(alpha = .7f), radius = 4.dp.toPx(),
                center = Offset(size.width * .66f, size.height * .71f))
            drawCircle(Palette.primary.copy(alpha = .75f), radius = 3.dp.toPx(),
                center = Offset(size.width * .95f, size.height * .78f))
        }
        Column(Modifier.fillMaxSize().padding(23.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("THE SOUNDTRACK TO EVERYWHERE")
            Column {
                Text("Every place\nhas a song.", style = MaterialTheme.typography.displayLarge,
                    color = Palette.text)
                Spacer(Modifier.height(10.dp))
                Text("Leave music where life happens.\nCome back and press play.",
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD2DCCE))
            }
            PrimaryButton("Drop a pin  ↗", onNewPin)
        }
    }
}

@Composable
private fun LocationBanner(point: GeoPoint?, locating: Boolean, onLocate: () -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Palette.primaryDark, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MyLocation, null, tint = Palette.primary, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (point == null) "Find your location" else "You’re here",
                    style = MaterialTheme.typography.titleMedium)
                Text(if (point == null) "Only used when you ask" else
                    String.format(Locale.getDefault(), "%.4f, %.4f", point.latitude, point.longitude),
                    style = MaterialTheme.typography.bodyMedium, color = Palette.secondary)
            }
            TextButton(onClick = onLocate, enabled = !locating) {
                if (locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (point == null) "Locate" else "Refresh", color = Palette.primary)
            }
        }
    }
}

@Composable
private fun PinsScreen(
    state: AppState, onNewPin: () -> Unit, onLocate: () -> Unit,
    onPlay: (MusicPin) -> Unit, onMap: (GeoPoint) -> Unit, onDelete: (MusicPin) -> Unit
) {
    val ordered = state.location?.let { point -> state.pins.sortedBy { point.distanceTo(it.point) } }
        ?: state.pins
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            Spacer(Modifier.height(22.dp))
            BrandHeader()
            Spacer(Modifier.height(28.dp))
            Eyebrow("YOUR COLLECTION")
            Spacer(Modifier.height(8.dp))
            Text("Places worth\nplaying back.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("${state.pins.size} saved ${if (state.pins.size == 1) "soundspot" else "soundspots"} · " +
                if (state.location == null) "newest first" else "nearest first",
                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(18.dp))
            PrimaryButton("+  Add a song pin", onNewPin, Modifier.fillMaxWidth())
            if (state.location == null && state.pins.isNotEmpty()) {
                TextButton(onClick = onLocate) { Text("Locate me to sort by distance", color = Palette.primary) }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (ordered.isEmpty()) {
            item {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Bookmark, null, tint = Palette.lilac,
                        modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Your collection starts here", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("Save a Spotify or YouTube Music song at your current location — or enter a place yourself.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(ordered, key = { it.id }) { pin ->
            PinCard(pin, state.location?.distanceTo(pin.point),
                onPlay = { onPlay(pin) }, onMap = { onMap(pin.point) }, onDelete = { onDelete(pin) })
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
