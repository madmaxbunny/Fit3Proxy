package com.madmaxbunny.fit3proxy.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.madmaxbunny.fit3proxy.model.ControlSlot
import com.madmaxbunny.fit3proxy.model.SlotRepository

/**
 * Phase 1 MediaSession prototype.
 *
 * Soft AudioFocus: request/abandon only while the dashboard switch keeps the
 * session active — avoids fighting Spotify/YouTube when idle (SOW §6.2).
 *
 * Media callbacks stay lightweight: update PlaybackState/Metadata immediately,
 * defer UI/event-log work. Do NOT rebuild FGS notifications here — that floods
 * Galaxy Wearable and freezes the Fit3 music UI.
 */
class Fit3MediaSessionManager(
    private val context: Context,
    private val slotRepository: SlotRepository
) {

    interface EventListener {
        fun onEvent(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var mediaSession: MediaSessionCompat? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false
    private var isPlayingVisual: Boolean = false
    private var active: Boolean = false

    /** Cached actions bitmask — avoid reallocating on every button press. */
    private val transportActions =
        PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_REWIND

    var eventListener: EventListener? = null

    private val _metadataPreview = MutableLiveData<MetadataPreview>()
    val metadataPreview: LiveData<MetadataPreview> = _metadataPreview

    private val _sessionActive = MutableLiveData(false)
    val sessionActive: LiveData<Boolean> = _sessionActive

    data class MetadataPreview(
        val title: String,
        val artist: String,
        val album: String,
        val playing: Boolean
    )

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            // Ack Fit3 immediately with state, then cheap slot work
            isPlayingVisual = true
            handleToggle()
            publishPlaybackState()
            publishMetadata()
            logEventDeferred("onPlay() → toggle slot")
        }

        override fun onPause() {
            isPlayingVisual = false
            handleToggle()
            publishPlaybackState()
            publishMetadata()
            logEventDeferred("onPause() → toggle slot")
        }

        override fun onSkipToNext() {
            slotRepository.next()
            publishMetadata()
            publishPlaybackState()
            logEventDeferred("onSkipToNext() → carousel next")
        }

        override fun onSkipToPrevious() {
            slotRepository.previous()
            publishMetadata()
            publishPlaybackState()
            logEventDeferred("onSkipToPrevious() → carousel previous")
        }

        override fun onFastForward() {
            slotRepository.brightnessDelta(+10)
            publishMetadata()
            logEventDeferred("onFastForward() → brightness +10%")
        }

        override fun onRewind() {
            slotRepository.brightnessDelta(-10)
            publishMetadata()
            logEventDeferred("onRewind() → brightness -10%")
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                logEventDeferred("AudioFocus lost ($change) — keeping soft session paused visually")
                hasAudioFocus = false
                isPlayingVisual = false
                publishPlaybackState()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                logEventDeferred("AudioFocus gained")
                hasAudioFocus = true
            }
        }
    }

    fun startSession() {
        if (active) return
        logEvent("startSession()")

        val session = MediaSessionCompat(context, SESSION_TAG).apply {
            setCallback(callback, mainHandler)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }
        mediaSession = session

        requestSoftAudioFocus()
        isPlayingVisual = true
        active = true
        _sessionActive.postValue(true)

        publishMetadata()
        publishPlaybackState()
        logEvent("MediaSession active — Fit3 music widget should show metadata")
    }

    fun stopSession() {
        if (!active) return
        logEvent("stopSession()")

        abandonSoftAudioFocus()
        mediaSession?.apply {
            isActive = false
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
                    .build()
            )
            release()
        }
        mediaSession = null
        isPlayingVisual = false
        active = false
        _sessionActive.postValue(false)
        _metadataPreview.postValue(MetadataPreview("—", "—", "—", false))
        logEvent("MediaSession released")
    }

    fun isActive(): Boolean = active

    fun getSessionToken(): MediaSessionCompat.Token? = mediaSession?.sessionToken

    /**
     * Brief playback-state nudge so Fit3 / Wearable wakes for an emergency alert.
     * No-op when session is off. Does not touch FGS notifications.
     */
    fun pulseForAlert() {
        if (!active || mediaSession == null) return
        val session = mediaSession ?: return
        // Tiny position bump + re-assert PLAYING/PAUSED so controllers refresh
        val state = if (isPlayingVisual) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val pulsePos = PLAYBACK_POSITION_MS + (SystemClock.elapsedRealtime() % 500)
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(transportActions)
                .setState(state, pulsePos, if (isPlayingVisual) 1.0f else 0f)
                .build()
        )
        logEventDeferred("pulseForAlert() — MediaSession nudge for Fit3 wake")
    }

    private fun handleToggle() {
        val slot = slotRepository.toggleCurrent()
        // Deferred so MediaSession ack is not blocked by string work / listeners
        logEventDeferred("toggle → ${slot.title} = ${if (slot.isOn) "ON" else "OFF"}")
    }

    private fun publishMetadata() {
        val slot = slotRepository.current()
        val album = slot.albumLabel(slotRepository.currentIndexZeroBased, slotRepository.size)
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, slot.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, slot.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, FAKE_DURATION_MS)
            .build()
        mediaSession?.setMetadata(metadata)
        // postValue is async — OK for phone UI; must not trigger Wearable notification flood
        _metadataPreview.postValue(
            MetadataPreview(slot.title, slot.artist, album, isPlayingVisual)
        )
        Log.d(TAG, "metadata title=${slot.title} artist=${slot.artist}")
    }

    private fun publishPlaybackState() {
        val state = if (isPlayingVisual) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(transportActions)
            .setState(state, PLAYBACK_POSITION_MS, if (isPlayingVisual) 1.0f else 0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
        Log.d(TAG, "playbackState=$state")
    }

    /**
     * Soft focus: hold only while session switch is ON.
     * Transient may still be interrupted by real players — by design.
     */
    private fun requestSoftAudioFocus() {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        logEvent("AudioFocus request → ${if (hasAudioFocus) "GRANTED" else "DENIED ($result)"}")
    }

    private fun abandonSoftAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
        logEvent("AudioFocus abandoned")
    }

    private fun logEvent(message: String) {
        Log.i(TAG, message)
        eventListener?.onEvent(message)
    }

    /** Non-blocking: never stall MediaSession callback ack on UI/logging. */
    private fun logEventDeferred(message: String) {
        Log.i(TAG, message)
        mainHandler.post {
            eventListener?.onEvent(message)
        }
    }

    /** Expose current slot for notification text. */
    fun currentSlot(): ControlSlot = slotRepository.current()

    companion object {
        private const val TAG = "Fit3MediaSession"
        private const val SESSION_TAG = "Fit3ProxySession"
        private const val FAKE_DURATION_MS = 60_000L
        private const val PLAYBACK_POSITION_MS = 1_000L
    }
}
