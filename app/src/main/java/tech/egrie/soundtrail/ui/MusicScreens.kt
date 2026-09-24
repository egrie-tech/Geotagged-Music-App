package tech.egrie.soundtrail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import tech.egrie.soundtrail.AppState
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.MusicProvider
import tech.egrie.soundtrail.integrations.SpotifyTrack
import tech.egrie.soundtrail.integrations.SPOTIFY_REDIRECT_URI

@Composable
internal fun MusicSearchScreen(
    state: AppState, onSearch: (String) -> Unit, onConnect: () -> Unit,
    onSelectTrack: (SpotifyTrack) -> Unit, onAddLink: (String) -> Unit,
    onOpenYoutube: () -> Unit, onNotice: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var youtubeLink by rememberSaveable { mutableStateOf("") }
    var hasSearched by rememberSaveable { mutableStateOf(false) }
    fun search() { hasSearched = query.isNotBlank(); onSearch(query) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(22.dp))
            BrandHeader()
            Spacer(Modifier.height(28.dp))
            Eyebrow("FIND YOUR NEXT SOUNDSPOT")
            Spacer(Modifier.height(8.dp))
            Text("Find a feeling.\nGive it a place.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("Find a track on Spotify, or bring a YouTube Music link.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.secondary)
            Spacer(Modifier.height(20.dp))
            SurfaceCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(Palette.spotify.copy(alpha = .17f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.MusicNote, null, tint = Palette.spotify, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Search Spotify", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(8.dp))
                if (state.spotifyConnected) {
                    Text("Browse tracks with your connected account.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(13.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Song, artist, or album") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() })
                    )
                    Spacer(Modifier.height(11.dp))
                    PrimaryButton("Search tracks", { search() }, Modifier.fillMaxWidth(),
                        enabled = !state.searching && query.isNotBlank())
                } else {
                    Text("Connect Spotify to search its track catalog. You can still pin a song link without signing in.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Connect Spotify", onConnect, Modifier.fillMaxWidth())
                }
            }
            if (state.searching) {
                Spacer(Modifier.height(19.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Finding tracks…", color = Palette.secondary)
                }
            }
            if (state.searchError != null) {
                Spacer(Modifier.height(14.dp))
                Text(state.searchError, color = Palette.coral, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.results.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionHeading("Tracks")
                Spacer(Modifier.height(4.dp))
            } else if (hasSearched && !state.searching && state.searchError == null && state.spotifyConnected) {
                Spacer(Modifier.height(14.dp))
                Text("No tracks found. Try another search.", color = Palette.secondary)
            }
        }
        items(state.results, key = { it.url }) { track ->
            TrackRow(track) { onSelectTrack(track) }
        }
        item {
            Spacer(Modifier.height(16.dp))
            SurfaceCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(Palette.youtube.copy(alpha = .16f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Link, null, tint = Palette.youtube, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("YouTube Music link", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(8.dp))
                Text("Paste a song link, or use Share in YouTube Music and choose Soundtrail.",
                    color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(13.dp))
                OutlinedTextField(
                    value = youtubeLink, onValueChange = { youtubeLink = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("music.youtube.com/watch?v=…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
                )
                Spacer(Modifier.height(11.dp))
                PrimaryButton("Pin this link", {
                    val link = MusicLinkParser.fromText(youtubeLink)
                    if (link?.provider == MusicProvider.YOUTUBE_MUSIC) onAddLink(link.url)
                    else onNotice("Paste a YouTube Music song link (not a playlist or search page).")
                }, Modifier.fillMaxWidth(), enabled = youtubeLink.isNotBlank())
                TextButton(onClick = onOpenYoutube) {
                    Text("Open YouTube Music  ↗", color = Palette.youtube)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun ConnectionsScreen(
    state: AppState,
    onConnect: (String) -> Unit, onDisconnect: () -> Unit,
    onOpenSpotify: () -> Unit, onOpenYoutube: () -> Unit,
    onOpenDashboard: () -> Unit, onOpenMicroG: () -> Unit
) {
    var clientId by rememberSaveable(state.spotifyClientId) { mutableStateOf(state.spotifyClientId) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(22.dp))
        BrandHeader()
        Spacer(Modifier.height(28.dp))
        Eyebrow("THE CONNECTIONS")
        Spacer(Modifier.height(8.dp))
        Text("All your music.\nAll your places.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Your accounts stay in their own apps. Soundtrail keeps only the links and pins you save.",
            color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(22.dp))

        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(43.dp).background(Palette.spotify.copy(alpha = .17f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Palette.spotify)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Spotify", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.spotifyConnected) "Connected" else "Not connected",
                        color = if (state.spotifyConnected) Palette.spotify else Palette.secondary,
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (state.spotifyConnected) Icon(Icons.Rounded.Check, "Connected", tint = Palette.spotify)
            }
            Spacer(Modifier.height(15.dp))
            if (state.spotifyConnected) {
                Text("Signed in${state.spotifyName?.let { " as $it" }.orEmpty()}. Search tracks and save them to places.",
                    color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(13.dp))
                PrimaryButton("Open Spotify", onOpenSpotify, Modifier.fillMaxWidth())
                TextButton(onClick = onDisconnect) { Text("Disconnect on this device", color = Palette.coral) }
            } else {
                Text("Use a Spotify Developer app's public Client ID. No client secret is stored or needed.",
                    color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text("Redirect URI to register in your Spotify dashboard:",
                    style = MaterialTheme.typography.labelMedium, color = Palette.secondary)
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(SPOTIFY_REDIRECT_URI, color = Palette.primary, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(13.dp))
                OutlinedTextField(
                    value = clientId, onValueChange = { clientId = it.trim().take(32) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Spotify Client ID") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (state.connecting) "Connecting…" else "Connect Spotify",
                    { onConnect(clientId) }, Modifier.fillMaxWidth(), enabled = !state.connecting)
                TextButton(onClick = onOpenDashboard) {
                    Text("Open Spotify Developer Dashboard  ↗", color = Palette.spotify)
                }
                Text("Developer-mode apps may require a Premium app owner and an allowlisted test account.",
                    style = MaterialTheme.typography.labelMedium, color = Palette.muted)
            }
        }
        Spacer(Modifier.height(13.dp))
        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(43.dp).background(Palette.youtube.copy(alpha = .16f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Palette.youtube)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("YouTube Music", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.youtubeAppInstalled) "Music app detected" else "Use app or web",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(13.dp))
            Text(state.servicesLabel, color = Palette.lilac, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(7.dp))
            Text("Sign in inside your YouTube Music app. On compatible devices, microG can provide Google sign-in for that app. Soundtrail does not see your Google account.",
                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(7.dp))
            Text("There is no public YouTube Music library or playback API here. Share a song link to pin it; tapping play opens your music app (or web).",
                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(15.dp))
            PrimaryButton("Open YouTube Music", onOpenYoutube, Modifier.fillMaxWidth())
            TextButton(onClick = onOpenMicroG) { Text("Learn about microG  ↗", color = Palette.lilac) }
        }
        Spacer(Modifier.height(13.dp))
        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text("Private by design", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(7.dp))
            Text("Location is requested only when you tap Locate. Pins stay on your phone; Spotify tokens are encrypted with Android Keystore. No server, scraping, or background tracking.",
                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(27.dp))
    }
}
