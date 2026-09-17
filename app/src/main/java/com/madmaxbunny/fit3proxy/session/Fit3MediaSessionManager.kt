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
import androidx.media.VolumeProviderCompat
import com.madmaxbunny.fit3proxy.model.ControlSlot
import com.madmaxbunny.fit3proxy.model.SlotRepository

/**
 * Phase 1 MediaSession prototype + remote volume remap (0.3.1).
 *
 * Soft AudioFocus: request/abandon only while the dashboard switch keeps the
 * session active — avoids fighting Spotify/YouTube when idle (SOW §6.2).
 *
 * While session is ON, playback volume is routed to [VolumeProviderCompat]
 * (ABSOLUTE) so Fit3 / Wearable volume that hits the remote provider becomes
 * app events (volumeStep ±10 / remVol) instead of only changing STREAM_MUSIC.
 * Phone hardware volume keys are intercepted in [com.madmaxbunny.fit3proxy.ui.MainActivity]
 * (foreground) and adjust local STREAM_MUSIC with FLAG_SHOW_UI — they must not
 * change remVol. On stop, [setPlaybackToLocal] restores normal phone media volume.
 *
 * 0.3.0 used RELATIVE; on Samsung/Fit3 the system remote-volume bar often
 * appeared without delivering [VolumeProviderCompat.onAdjustVolume], so remVol
 * stayed stuck. 0.3.1 uses ABSOLUTE, always calls [setCurrentVolume] after each
 * change (synchronously in the provider callback), and hops metadata/UI onto
 * the main looper.
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
    private var volumeProvider: VolumeProviderCompat? = null
    private var hasAudioFocus: Boolean = false
    private var isPlayingVisual: Boolean = false
    private var active: Boolean = false

    /** Demo counter shown on Fit3 metadata / phone dashboard (0–100). */
    @Volatile
    private var volumeStep: Int = 50
    @Volatile
    private var lastVolumeEvent: String = "—"

    /** Cached actions bitmask — avoid reallocating on every button press. */
    private val transportActions =
        PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_SEEK_TO

    var eventListener: EventListener? = null

    private val _metadataPreview = MutableLiveData<MetadataPreview>()
    val metadataPreview: LiveData<MetadataPreview> = _metadataPreview

    private val _sessionActive = MutableLiveData(false)
    val sessionActive: LiveData<Boolean> = _sessionActive

    data class MetadataPreview(
        val title: String,
        val artist: String,
        val album: String,
        val playing: Boolean,
        val volumeStep: Int = 50,
        val lastVolumeEvent: String = "—"
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
            handleBrightnessDelta(+10, "onFastForward()")
        }

        override fun onRewind() {
            handleBrightnessDelta(-10, "onRewind()")
        }

        /**
         * Some Wearable/Fit3 bridges map FF/REW to seek instead of onFastForward/onRewind.
         * Treat seek-past-current as +10 and seek-before as -10 brightness.
         */
        override fun onSeekTo(pos: Long) {
            val delta = if (pos >= PLAYBACK_POSITION_MS) +10 else -10
            handleBrightnessDelta(delta, "onSeekTo(pos=$pos)")
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

        attachRemoteVolume(session)
        requestSoftAudioFocus()
        isPlayingVisual = true
        active = true
        _sessionActive.postValue(true)

        publishMetadata()
        publishPlaybackState()
        logEvent("MediaSession active — Fit3 music widget should show metadata")
        logEvent(
            "Remote volume ON (ABSOLUTE) — Fit3/Wearable volume → VolumeProvider " +
                "(step=$volumeStep, delta=±$VOLUME_DELTA). " +
                "Phone HW volume keys (app foreground) → STREAM_MUSIC local; remVol unchanged. " +
                "System remote-volume bar may appear for Fit3; remVol + event log are the real feedback."
        )
    }

    fun stopSession() {
        if (!active) return
        logEvent("stopSession()")

        abandonSoftAudioFocus()
        mediaSession?.apply {
            // Restore normal local media volume before tearing down the session.
            try {
                setPlaybackToLocal(AudioManager.STREAM_MUSIC)
                logEvent("setPlaybackToLocal(STREAM_MUSIC) — phone media volume restored")
            } catch (t: Throwable) {
                Log.w(TAG, "setPlaybackToLocal failed", t)
                logEvent("setPlaybackToLocal failed: ${t.message}")
            }
            isActive = false
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
                    .build()
            )
            release()
        }
        mediaSession = null
        volumeProvider = null
        isPlayingVisual = false
        active = false
        _sessionActive.postValue(false)
        _metadataPreview.postValue(
            MetadataPreview("—", "—", "—", false, volumeStep, lastVolumeEvent)
        )
        logEvent("MediaSession released — remote volume abandoned")
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

    private fun attachRemoteVolume(session: MediaSessionCompat) {
        // ABSOLUTE: Samsung/Fit3 often shows the remote volume panel for RELATIVE
        // without invoking onAdjustVolume. ABSOLUTE still receives onAdjustVolume
        // for key presses and onSetVolumeTo for absolute UI / OEM paths.
        val provider = object : VolumeProviderCompat(
            VOLUME_CONTROL_ABSOLUTE,
            VOLUME_MAX,
            volumeStep
        ) {
            override fun onAdjustVolume(direction: Int) {
                // May run on a binder thread. Update provider volume synchronously
                // so the system remote-volume UI stays in sync, then hop UI work.
                Log.i(
                    TAG,
                    "onAdjustVolume(direction=$direction) thread=${Thread.currentThread().name}"
                )
                val delta = directionToDelta(direction)
                val before = volumeStep
                val after = if (delta == 0) {
                    before
                } else {
                    (before + delta).coerceIn(0, VOLUME_MAX)
                }
                volumeStep = after
                // Critical: always re-assert so session/UI do not treat adjust as no-op.
                setCurrentVolume(after)

                val label = when {
                    delta > 0 -> "volumeUp"
                    delta < 0 -> "volumeDown"
                    else -> "volumeSame"
                }
                lastVolumeEvent = if (delta == 0) {
                    "$label (no-op, step=$after)"
                } else {
                    "$label → step=$after (was $before)"
                }

                mainHandler.post {
                    publishMetadata()
                    publishPlaybackState(nudgePosition = true)
                    if (delta == 0) {
                        logEvent(
                            "onAdjustVolume(dir=$direction) → no-op | volumeStep=$after"
                        )
                    } else {
                        logEvent(
                            "$label → custom action | volumeStep=$after " +
                                "(dir=$direction, delta=$delta, setCurrentVolume, ABSOLUTE)"
                        )
                    }
                }
            }

            override fun onSetVolumeTo(volume: Int) {
                Log.i(
                    TAG,
                    "onSetVolumeTo(volume=$volume) thread=${Thread.currentThread().name}"
                )
                val before = volumeStep
                val after = volume.coerceIn(0, VOLUME_MAX)
                volumeStep = after
                setCurrentVolume(after)

                val label = when {
                    after > before -> "volumeUp"
                    after < before -> "volumeDown"
                    else -> "volumeSet"
                }
                lastVolumeEvent = "$label → step=$after (setTo, was $before)"

                mainHandler.post {
                    publishMetadata()
                    publishPlaybackState(nudgePosition = true)
                    logEvent(
                        "$label → custom action | volumeStep=$after " +
                            "(onSetVolumeTo($volume), setCurrentVolume, ABSOLUTE)"
                    )
                }
            }
        }
        volumeProvider = provider
        session.setPlaybackToRemote(provider)
        // Re-assert so session/UI start in sync with our demo counter.
        provider.setCurrentVolume(volumeStep)
        logEvent(
            "setPlaybackToRemote(VolumeProviderCompat ABSOLUTE, max=$VOLUME_MAX, " +
                "current=$volumeStep) — expect volumeUp/volumeDown in log on key press"
        )
    }

    /** Map ADJUST_* / OEM ±1-style directions to ±[VOLUME_DELTA] (0 = no-op). */
    private fun directionToDelta(direction: Int): Int = when {
        direction == AudioManager.ADJUST_RAISE || direction > 0 -> VOLUME_DELTA
        direction == AudioManager.ADJUST_LOWER || direction < 0 -> -VOLUME_DELTA
        else -> 0
    }

    private fun handleToggle() {
        val slot = slotRepository.toggleCurrent()
        // Deferred so MediaSession ack is not blocked by string work / listeners
        logEventDeferred("toggle → ${slot.title} = ${if (slot.isOn) "ON" else "OFF"}")
    }

    private fun publishMetadata() {
        val slot = slotRepository.current()
        val album = slot.albumLabel(slotRepository.currentIndexZeroBased, slotRepository.size)
        // Artist carries slot status + remapped volumeStep so Fit3 can show feedback.
        val artistWithVol = "${slot.artist} | remVol: $volumeStep"
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, slot.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artistWithVol)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, FAKE_DURATION_MS)
            .build()
        mediaSession?.setMetadata(metadata)
        // postValue is async — OK for phone UI; must not trigger Wearable notification flood
        _metadataPreview.postValue(
            MetadataPreview(
                title = slot.title,
                artist = artistWithVol,
                album = album,
                playing = isPlayingVisual,
                volumeStep = volumeStep,
                lastVolumeEvent = lastVolumeEvent
            )
        )
        Log.d(TAG, "metadata title=${slot.title} artist=$artistWithVol remVol=$volumeStep")
    }

    /**
     * Apply brightness delta on the current slot, push live Artist metadata +
     * a PlaybackState nudge so Fit3 refreshes (metadata-only updates are often ignored),
     * and log the resulting brightness.
     */
    private fun handleBrightnessDelta(delta: Int, source: String) {
        val before = slotRepository.current()
        if (!before.supportsBrightness) {
            publishPlaybackState(nudgePosition = true)
            logEventDeferred(
                "$source → brightness unsupported on ${before.title} (no-op)"
            )
            return
        }
        val slot = slotRepository.brightnessDelta(delta)
        // Fit3/Wearable often caches Artist until PlaybackState changes — nudge position.
        publishMetadata()
        publishPlaybackState(nudgePosition = true)
        logEventDeferred(
            "$source → brightness ${if (delta >= 0) "+" else ""}$delta% → " +
                "${slot.brightnessPercent}% (${slot.title})"
        )
    }

    private fun publishPlaybackState(nudgePosition: Boolean = false) {
        val state = if (isPlayingVisual) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val position = if (nudgePosition) {
            PLAYBACK_POSITION_MS + (SystemClock.elapsedRealtime() % 500)
        } else {
            PLAYBACK_POSITION_MS
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(transportActions)
            .setState(state, position, if (isPlayingVisual) 1.0f else 0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
        Log.d(TAG, "playbackState=$state pos=$position")
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
        private const val VOLUME_MAX = 100
        /** Demo step size per volume key (clamp 0–100). */
        private const val VOLUME_DELTA = 10
    }
}
