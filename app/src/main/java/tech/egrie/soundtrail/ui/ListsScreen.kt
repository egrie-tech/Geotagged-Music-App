package tech.egrie.soundtrail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.egrie.soundtrail.AppState
import tech.egrie.soundtrail.automation.AutomationRule
import tech.egrie.soundtrail.automation.MotionKind
import tech.egrie.soundtrail.data.Playlist
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.integrations.MusicLinkParser
import java.time.LocalTime
import java.util.UUID

/** Everything the Lists tab can ask the app to do. */
data class ListsActions(
    val onToggleWatch: (Boolean) -> Unit,
    val onToggleAutoSearch: (Boolean) -> Unit,
    val onNewPlaylist: (String) -> Unit,
    val onDeletePlaylist: (String) -> Unit,
    val onAddSong: (playlistId: String, title: String, artist: String, url: String) -> Unit,
    val onRemoveSong: (playlistId: String, itemId: String) -> Unit,
    val onPlaySong: (PlaylistItem) -> Unit,
    val onSearchSong: (PlaylistItem) -> Unit,
    val onSaveRule: (AutomationRule) -> Unit,
    val onDeleteRule: (String) -> Unit
)

@Composable
internal fun ListsScreen(state: AppState, actions: ListsActions, onNotice: (String) -> Unit) {
    var openPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingRule by rememberSaveable { mutableStateOf(false) }
    var newPlaylistName by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(22.dp))
            BrandHeader()
            Spacer(Modifier.height(28.dp))
            Eyebrow("TRIGGERS & LISTS")
            Spacer(Modifier.height(8.dp))
            Text("Music that\nshows up first.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("Build playlists, then let places, motion, or the time of day call them up.",
                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            WatchCard(state, actions)
            Spacer(Modifier.height(6.dp))
        }
        val openList = openPlaylistId?.let { id -> state.playlists.firstOrNull { it.id == id } }
        if (openList != null) {
            item {
                PlaylistDetailCard(
                    playlist = openList, state = state, actions = actions, onNotice = onNotice,
                    onBack = { openPlaylistId = null }
                )
                Spacer(Modifier.height(20.dp))
            }
        } else {
            item {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.QueueMusic, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(9.dp))
                        Text("Playlists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    if (state.playlists.isEmpty()) {
                        Text("No playlists yet. Make one, add songs, then point an automation at it.",
                            color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }
                    state.playlists.forEach { playlist ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .clickable { openPlaylistId = playlist.id }
                                .padding(vertical = 9.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                                Text("${playlist.items.size} ${if (playlist.items.size == 1) "song" else "songs"}",
                                    color = Palette.secondary, style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(onClick = { actions.onDeletePlaylist(playlist.id) }) {
                                Icon(Icons.Rounded.Delete, "Delete ${playlist.name}",
                                    tint = Palette.muted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newPlaylistName, onValueChange = { newPlaylistName = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("New playlist name") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Create playlist", {
                        actions.onNewPlaylist(newPlaylistName)
                        newPlaylistName = ""
                    }, Modifier.fillMaxWidth(), enabled = newPlaylistName.isNotBlank())
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        item {
            SurfaceCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Schedule, null, tint = Palette.lilac, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Automations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { creatingRule = !creatingRule }) {
                        Text(if (creatingRule) "Close" else "+ New", color = Palette.primary)
                    }
                }
                if (state.automations.isEmpty() && !creatingRule) {
                    Text("No automations yet. Point a playlist at a place, a way of moving, or a time window.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                }
                state.automations.forEach { rule ->
                    val playlistName = state.playlists.firstOrNull { it.id == rule.playlistId }?.name ?: "missing playlist"
                    AutomationRow(rule, playlistName,
                        onToggle = { actions.onSaveRule(rule.copy(enabled = !rule.enabled)) },
                        onDelete = { actions.onDeleteRule(rule.id) })
                }
                if (creatingRule) {
                    Spacer(Modifier.height(8.dp))
                    RuleForm(state, onNotice, onSave = { rule ->
                        actions.onSaveRule(rule)
                        creatingRule = false
                    })
                }
            }
            Spacer(Modifier.height(13.dp))
            Text("Triggers re-arm after a 15-minute cooldown. Motion is inferred on-device from GPS speed; the watch runs as a visible notification and never sends your location anywhere.",
                color = Palette.muted, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(27.dp))
        }
    }
}

@Composable
private fun WatchCard(state: AppState, actions: ListsActions) {
    var nowMinute by rememberSaveable { mutableStateOf(LocalTime.now().get(java.time.temporal.ChronoField.MINUTE_OF_DAY)) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMinute = LocalTime.now().get(java.time.temporal.ChronoField.MINUTE_OF_DAY)
            delay(30_000)
        }
    }
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Palette.lilac.copy(alpha = .15f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MyLocation, null, tint = Palette.lilac, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Watch location & time", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.watchStatus.running) {
                        val fix = state.watchStatus.location
                        "${state.watchStatus.motion.label} · ${state.watchStatus.clock()}" +
                            fix?.let { " · ${"%.4f, %.4f".format(it.latitude, it.longitude)}" }.orEmpty()
                    } else {
                        "Off · ${"%02d:%02d".format(nowMinute / 60, nowMinute % 60)} now"
                    },
                    color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(checked = state.watchStatus.running, onCheckedChange = actions.onToggleWatch)
        }
        Spacer(Modifier.height(8.dp))
        Text("While watching, a persistent notification checks your place, movement, and the time, and raises your automations. Location stays on this device.",
            color = Palette.muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-search YouTube when adding songs", style = MaterialTheme.typography.titleMedium)
                Text("Opens YouTube search results in your browser or the YouTube app.",
                    color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = state.autoSearchYoutube, onCheckedChange = actions.onToggleAutoSearch)
        }
        Text("Soundtrail never downloads YouTube content — playback and saving stay in YouTube's own apps.",
            color = Palette.muted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PlaylistDetailCard(
    playlist: Playlist, state: AppState, actions: ListsActions,
    onNotice: (String) -> Unit, onBack: () -> Unit
) {
    var title by rememberSaveable(playlist.id) { mutableStateOf("") }
    var artist by rememberSaveable(playlist.id) { mutableStateOf("") }
    var url by rememberSaveable(playlist.id) { mutableStateOf("") }
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(playlist.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.Close, "Close ${playlist.name}", tint = Palette.muted)
            }
        }
        if (playlist.items.isEmpty()) {
            Text("Empty. Add a song below — a link is optional; without one, Soundtrail searches YouTube for it.",
                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
        }
        playlist.items.forEach { item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    Text(item.artist.ifBlank { item.provider?.label ?: "Link not set" },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = Palette.secondary, style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = { actions.onPlaySong(item) }, Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.PlayArrow, "Play ${item.title}", tint = Palette.primary)
                }
                IconButton(onClick = { actions.onSearchSong(item) }, Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Search, "Search ${item.title} on YouTube", tint = Palette.lilac)
                }
                IconButton(onClick = { actions.onRemoveSong(playlist.id, item.id) }, Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Close, "Remove ${item.title}", tint = Palette.muted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Song title *") }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = artist, onValueChange = { artist = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Artist (optional)") }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Spotify, SoundCloud, or YouTube link (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
        )
        if (url.isNotBlank() && MusicLinkParser.parse(url) == null) {
            Spacer(Modifier.height(6.dp))
            Text("That link is not a supported song URL; it will be ignored and YouTube search will be used.",
                color = Palette.coral, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Add song", {
            actions.onAddSong(playlist.id, title, artist, url)
            title = ""; artist = ""; url = ""
        }, Modifier.fillMaxWidth(), enabled = title.isNotBlank())
    }
}

@Composable
private fun AutomationRow(rule: AutomationRule, playlistName: String, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.titleMedium,
                color = if (rule.enabled) Palette.text else Palette.muted)
            Text(rule.summary(playlistName), maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = Palette.secondary, style = MaterialTheme.typography.labelMedium)
        }
        Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, "Delete ${rule.name}", tint = Palette.muted, modifier = Modifier.size(18.dp))
        }
    }
}

private fun AutomationRule.summary(playlistName: String): String {
    val parts = buildList {
        place?.let { add("near a saved spot (${radiusMeters.toInt()} m)") }
        activity?.let { add("while ${it.label.lowercase()}") }
        if (fromMinute != null && toMinute != null) {
            add("· %02d:%02d–%02d:%02d".format(fromMinute!! / 60, fromMinute!! % 60, toMinute!! / 60, toMinute!! % 60))
        }
    }
    return "▶ $playlistName · " + (parts.joinToString(" ").ifBlank { "no conditions" })
}

@Composable
private fun RuleForm(state: AppState, onNotice: (String) -> Unit, onSave: (AutomationRule) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var playlistId by rememberSaveable { mutableStateOf("") }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var usePlace by rememberSaveable { mutableStateOf(false) }
    var radius by rememberSaveable { mutableStateOf("150") }
    var activityName by rememberSaveable { mutableStateOf("Any motion") }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }

    if (playlistId.isBlank() && state.playlists.isNotEmpty()) playlistId = state.playlists.first().id

    Column {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Automation name *") }
        )
        Spacer(Modifier.height(10.dp))
        Box {
            OutlinedTextField(
                value = state.playlists.firstOrNull { it.id == playlistId }?.name.orEmpty(),
                onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Playlist") },
                trailingIcon = {
                    TextButton(onClick = { menuOpen = true }) { Text("Choose", color = Palette.primary) }
                }
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (state.playlists.isEmpty()) {
                    DropdownMenuItem(text = { Text("Create a playlist first") }, onClick = { menuOpen = false })
                }
                state.playlists.forEach { playlist ->
                    DropdownMenuItem(
                        text = { Text(playlist.name) },
                        onClick = { playlistId = playlist.id; menuOpen = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("When should it fire? Set any combination.", style = MaterialTheme.typography.labelMedium,
            color = Palette.secondary)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(Palette.primary.copy(alpha = .15f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MyLocation, null, tint = Palette.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (usePlace) "Place set — fires within ${radius.toIntOrNull()?.coerceIn(20, 2000) ?: 150} m"
                else "At a place",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = {
                if (state.location == null) onNotice("Find your location first (Explore tab, Locate).")
                else usePlace = !usePlace
            }) { Text(if (usePlace) "Clear" else "Use my location", color = Palette.primary) }
        }
        if (usePlace) {
            OutlinedTextField(
                value = radius, onValueChange = { radius = it.take(4) }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Trigger radius in meters (20–2000)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf<Pair<String, MotionKind?>>(
                "Any motion" to null,
                MotionKind.WALKING.label to MotionKind.WALKING,
                MotionKind.RUNNING.label to MotionKind.RUNNING,
                MotionKind.DRIVING.label to MotionKind.DRIVING
            ).forEach { (label, kind) ->
                val on = activityName == label
                Text(
                    label,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) Palette.lilac else Palette.elevated)
                        .clickable { activityName = label }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = if (on) Palette.background else Palette.secondary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = from, onValueChange = { from = it }, modifier = Modifier.weight(1f),
                singleLine = true, label = { Text("From (HH:MM)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value = to, onValueChange = { to = it }, modifier = Modifier.weight(1f),
                singleLine = true, label = { Text("Until (HH:MM)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done)
            )
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Save automation", {
            val fromMinute = from.trim().parseMinutes()
            val toMinute = to.trim().parseMinutes()
            when {
                name.isBlank() -> onNotice("Name this automation first.")
                playlistId.isBlank() -> onNotice("Pick a playlist to trigger.")
                !usePlace && activityName == "Any motion" && fromMinute == null ->
                    onNotice("Set a place, a motion, or a time window.")
                from.isBlank() != to.isBlank() ->
                    onNotice("Fill both time fields, or leave both empty.")
                (fromMinute == null) != (toMinute == null) ->
                    onNotice("Use HH:MM in both time fields, or leave both empty.")
                usePlace && (radius.toIntOrNull()?.let { it in 20..2000 } != true) ->
                    onNotice("Radius must be 20–2000 meters.")
                else -> onSave(AutomationRule(
                    id = UUID.randomUUID().toString(),
                    name = name.trim().take(80),
                    playlistId = playlistId,
                    place = if (usePlace) state.location else null,
                    radiusMeters = radius.toIntOrNull()?.coerceIn(20, 2000)?.toDouble() ?: 150.0,
                    activity = MotionKind.entries.firstOrNull { it.label == activityName },
                    fromMinute = fromMinute,
                    toMinute = toMinute
                ))
            }
        }, Modifier.fillMaxWidth())
    }
}

private fun String.parseMinutes(): Int? {
    val parts = split(":")
    if (parts.size != 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}
