package tech.egrie.soundtrail.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.egrie.soundtrail.integrations.JellyfinPlayback

/** What the now-playing bar shows. */
internal data class NowPlaying(
    val title: String,
    val artist: String,
    val itemId: String,
    val playing: Boolean,
    val buffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val error: String? = null,
    /** True once the item has played to its end; a play action rewinds instead of no-oping. */
    val completed: Boolean = false
)

/**
 * Streams songs from the user's own Jellyfin server inside Soundtrail — the one provider whose
 * playback does not belong in another app, because the library is the user's own hardware.
 *
 * Nothing is downloaded and nothing is cached: the server is asked for a stream for this item,
 * and the access token travels in a request header (never in a URL, and never written to disk).
 * [PlaybackService] registers a media session and a MediaStyle notification around this player
 * so streaming — and its controls — continue when Soundtrail is not on screen. There is one
 * process-wide instance ([shared]) because the service and the UI must drive the same stream.
 */
internal class JellyfinPlayer private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: JellyfinPlayer? = null

        /** The app's single player: the UI and [PlaybackService] must never own two streams. */
        fun shared(context: Context): JellyfinPlayer = instance ?: synchronized(this) {
            instance ?: JellyfinPlayer(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var sources: List<String> = emptyList()
    private var authorization: String? = null
    private var sourceIndex = 0
    private var ticker: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var pausedForFocus = false

    /** Starts an item from its resolved stream URLs, trying the direct stream before transcoding. */
    fun play(title: String, artist: String, itemId: String, playback: JellyfinPlayback) {
        release()
        sources = playback.urls
        authorization = playback.authorization
        sourceIndex = 0
        _state.value = NowPlaying(
            title = title, artist = artist, itemId = itemId,
            playing = false, buffering = true, positionMs = 0, durationMs = 0
        )
        openCurrentSource()
        // Bring up the foreground notification/session so the stream outlives this screen.
        PlaybackService.ensureStarted(appContext)
    }

    fun toggle() {
        val media = player ?: return
        val current = _state.value ?: return
        if (current.error != null || current.buffering) return
        if (current.playing) {
            media.pause()
            ticker?.cancel()
            _state.update { it?.copy(playing = false, positionMs = media.currentPosition.toLong()) }
        } else {
            requestFocus()
            if (current.completed) media.seekTo(0)
            media.start()
            _state.update { it?.copy(playing = true, completed = false) }
            startTicker()
            PlaybackService.ensureStarted(appContext)
        }
    }

    /** Stops playback and clears the bar. */
    fun stop() {
        release()
        _state.value = null
    }

    private fun openCurrentSource() {
        val url = sources.getOrNull(sourceIndex)
        if (url == null) {
            fail("Couldn't stream this song. It may have been moved on the server.")
            return
        }
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // The token rides in a header, so it never lands in a URL that could be logged.
            media.setDataSource(appContext, Uri.parse(url), headers())
            media.setOnPreparedListener { prepared ->
                prepared.start()
                requestFocus()
                _state.update {
                    it?.copy(playing = true, buffering = false, durationMs = prepared.duration.coerceAtLeast(0).toLong())
                }
                startTicker()
            }
            media.setOnCompletionListener { finished ->
                ticker?.cancel()
                _state.update {
                    it?.copy(
                        playing = false,
                        positionMs = finished.duration.coerceAtLeast(0).toLong(),
                        completed = true
                    )
                }
            }
            media.setOnErrorListener { _, _, _ ->
                // The stored format may not be decodable here; the next source is the transcode.
                advancePastFailure(media)
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            advancePastFailure(media)
        }
    }

    private fun headers(): Map<String, String> = mapOf(
        "Authorization" to authorization.orEmpty(),
        "Accept" to "*/*"
    )

    private fun advancePastFailure(failed: MediaPlayer) {
        runCatching {
            failed.reset()
            failed.release()
        }
        if (player === failed) player = null
        sourceIndex += 1
        if (sourceIndex < sources.size) {
            openCurrentSource()
        } else {
            fail("Couldn't stream this song. The server may be offline or the file unplayable here.")
        }
    }

    private fun fail(message: String) {
        ticker?.cancel()
        abandonFocus()
        _state.update { it?.copy(playing = false, buffering = false, error = message) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && _state.value?.playing == true) {
                player?.let { media ->
                    val position = media.currentPosition.toLong()
                    _state.update { it?.copy(positionMs = position) }
                }
                delay(500)
            }
        }
    }

    private fun release() {
        ticker?.cancel()
        ticker = null
        runCatching {
            player?.reset()
            player?.release()
        }
        player = null
        sources = emptyList()
        sourceIndex = 0
        pausedForFocus = false
        abandonFocus()
    }

    // ----- Audio focus: pause for calls and other players, then resume when it is polite -----

    private fun requestFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> scope.launch { pauseForFocus(resumeLater = false) }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                        scope.launch { pauseForFocus(resumeLater = true) }
                    AudioManager.AUDIOFOCUS_GAIN -> scope.launch { resumeAfterFocus() }
                }
            }
            .build()
            .also { focusRequest = it }
        runCatching { manager.requestAudioFocus(request) }
    }

    private fun pauseForFocus(resumeLater: Boolean) {
        val media = player ?: return
        if (_state.value?.playing != true) return
        pausedForFocus = resumeLater
        media.pause()
        ticker?.cancel()
        _state.update { it?.copy(playing = false, positionMs = media.currentPosition.toLong()) }
    }

    private fun resumeAfterFocus() {
        val media = player ?: return
        if (!pausedForFocus || _state.value?.playing == true) return
        pausedForFocus = false
        media.start()
        _state.update { it?.copy(playing = true) }
        startTicker()
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
    }
}
