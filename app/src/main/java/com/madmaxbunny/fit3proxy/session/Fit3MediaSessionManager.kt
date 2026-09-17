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
import com.madmaxbunny.fit3proxy.model.ControlMatrix
import com.madmaxbunny.fit3proxy.model.ControlSlot
import com.madmaxbunny.fit3proxy.model.SlotRepository

/**
 * MediaSession + remote volume remap + 2D control matrix (0.4.0).
 *
 * Soft AudioFocus: request/abandon only while the dashboard switch keeps the
 * session active — avoids fighting Spotify/YouTube when idle (SOW §6.2).
 *
 * **2D matrix (0.4.0):**
 * - Axis A (rows): Next / Previous → [SlotRepository.next]/[previous] (slotIndex wrap)
 * - Axis B (cols): Fit3 volume ↑/↓ → per-slot [ControlMatrix] level (0…100 step 10, clamp)
 * - Each slot remembers its own level; switching rows restores that row's column
 * - Play/Pause toggles **current slot** `isOn` (per-row); PlaybackState follows that slot
 * - Next/Prev restores PlaybackState from the **newly selected** slot's saved `isOn`
 *
 * While session is ON, playback volume is routed to [VolumeProviderCompat]
 * (ABSOLUTE) so Fit3 / Wearable volume becomes Axis B (remVol / level).
 * Phone hardware volume keys are intercepted in [com.madmaxbunny.fit3proxy.ui.MainActivity]
 * (foreground) and adjust local STREAM_MUSIC — they must not change remVol.
 * On stop, [setPlaybackToLocal] restores normal phone media volume.
 *
 * Media callbacks stay lightweight: update PlaybackState/Metadata immediately,
 * defer UI/event-log work. Do NOT rebuild FGS notifications here.
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
    private val matrix: ControlMatrix get() = slotRepository.matrix

    private var mediaSession: MediaSessionCompat? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var volumeProvider: VolumeProviderCompat? = null
    private var hasAudioFocus: Boolean = false
    private var isPlayingVisual: Boolean = false
    private var active: Boolean = false

    /** Last Axis-B / remVol event string for dashboard. */
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
        /** Current slot toggle for dashboard: "ON" / "OFF". */
        val slotToggle: String = "OFF",
        val volumeStep: Int = 50,
        val lastVolumeEvent: String = "—",
        val matrixPosition: String = "—",
        val matrixGrid: String = ""
    )

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            handleToggle()
            syncPlayingVisualFromCurrentSlot()
            publishPlaybackState()
            publishMetadata()
            logEventDeferred(
                "onPlay() → toggle slot | isOn=${slotRepository.current().isOn} " +
                    "| PlaybackState=${if (isPlayingVisual) "PLAYING" else "PAUSED"}"
            )
        }

        override fun onPause() {
            handleToggle()
            syncPlayingVisualFromCurrentSlot()
            publishPlaybackState()
            publishMetadata()
            logEventDeferred(
                "onPause() → toggle slot | isOn=${slotRepository.current().isOn} " +
                    "| PlaybackState=${if (isPlayingVisual) "PLAYING" else "PAUSED"}"
            )
        }

        override fun onSkipToNext() {
            val before = matrix.currentSlotIndex
            slotRepository.next()
            val after = matrix.currentSlotIndex
            // Axis A move → sync VolumeProvider + PlaybackState from this row
            syncRemoteVolumeToCurrentLevel()
            syncPlayingVisualFromCurrentSlot()
            publishMetadata()
            // Nudge so Fit3 refresh play/pause icon for the new slot's saved isOn
            publishPlaybackState(nudgePosition = true)
            val slot = slotRepository.current()
            logEventDeferred(
                "axis A (slot) next → row ${after + 1}/${matrix.rowCount} " +
                    "(was ${before + 1}) | level L${matrix.currentLevelPercent()}% | " +
                    "toggle=${if (slot.isOn) "ON" else "OFF"} | ${matrix.albumLabel()}"
            )
        }

        override fun onSkipToPrevious() {
            val before = matrix.currentSlotIndex
            slotRepository.previous()
            val after = matrix.currentSlotIndex
            syncRemoteVolumeToCurrentLevel()
            syncPlayingVisualFromCurrentSlot()
            publishMetadata()
            publishPlaybackState(nudgePosition = true)
            val slot = slotRepository.current()
            logEventDeferred(
                "axis A (slot) prev → row ${after + 1}/${matrix.rowCount} " +
                    "(was ${before + 1}) | level L${matrix.currentLevelPercent()}% | " +
                    "toggle=${if (slot.isOn) "ON" else "OFF"} | ${matrix.albumLabel()}"
            )
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
                // Restore play/pause icon from current slot's saved toggle
                syncPlayingVisualFromCurrentSlot()
                publishPlaybackState()
                publishMetadata()
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
        // Drive Fit3 play/pause from current row's saved isOn (not a global session flag)
        syncPlayingVisualFromCurrentSlot()
        active = true
        _sessionActive.postValue(true)

        publishMetadata()
        publishPlaybackState()
        logEvent("MediaSession active — Fit3 music widget should show metadata")
        logEvent(
            "2D matrix ON — Axis A: Next/Prev → slot | Axis B: Fit3 volume → per-slot level " +
                "(${matrix.rowCount}×${matrix.colCount}, step=${ControlMatrix.LEVEL_STEP_PERCENT}). " +
                "Phone HW volume keys (app foreground) → STREAM_MUSIC local; remVol unchanged."
        )
        logEvent(
            "Remote volume ON (ABSOLUTE) — Fit3/Wearable volume → VolumeProvider " +
                "(cell=${matrix.albumLabel()}, remVol=${matrix.currentLevelPercent()}). " +
                "System remote-volume bar may appear for Fit3; remVol + event log are the real feedback."
        )
    }

    fun stopSession() {
        if (!active) return
        logEvent("stopSession()")

        abandonSoftAudioFocus()
        mediaSession?.apply {
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
            MetadataPreview(
                title = "—",
                artist = "—",
                album = "—",
                playing = false,
                slotToggle = if (matrix.currentSlot().isOn) "ON" else "OFF",
                volumeStep = matrix.currentLevelPercent(),
                lastVolumeEvent = lastVolumeEvent,
                matrixPosition = matrix.positionReadout(),
                matrixGrid = matrix.gridText()
            )
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
        val initial = matrix.currentLevelPercent()
        val provider = object : VolumeProviderCompat(
            VOLUME_CONTROL_ABSOLUTE,
            VOLUME_MAX,
            initial
        ) {
            override fun onAdjustVolume(direction: Int) {
                Log.i(
                    TAG,
                    "onAdjustVolume(direction=$direction) thread=${Thread.currentThread().name}"
                )
                val delta = directionToDelta(direction)
                val (before, after, changed) = when {
                    delta > 0 -> matrix.levelUp()
                    delta < 0 -> matrix.levelDown()
                    else -> {
                        val cur = matrix.currentLevelPercent()
                        Triple(cur, cur, false)
                    }
                }
                // Critical: always re-assert so session/UI do not treat adjust as no-op.
                setCurrentVolume(after)

                val label = when {
                    delta > 0 -> "volumeUp"
                    delta < 0 -> "volumeDown"
                    else -> "volumeSame"
                }
                lastVolumeEvent = if (!changed) {
                    "$label (no-op, remVol=$after) | ${matrix.albumLabel()}"
                } else {
                    "$label → remVol=$after (was $before) | ${matrix.albumLabel()}"
                }

                mainHandler.post {
                    publishMetadata()
                    publishPlaybackState(nudgePosition = true)
                    if (!changed) {
                        logEvent(
                            "axis B (level) $label no-op | remVol=$after | ${matrix.albumLabel()} " +
                                "(dir=$direction, ABSOLUTE)"
                        )
                    } else {
                        logEvent(
                            "axis B (level) $label → remVol=$after (was $before) | " +
                                "${matrix.albumLabel()} (dir=$direction, delta=$delta, setCurrentVolume, ABSOLUTE)"
                        )
                    }
                }
            }

            override fun onSetVolumeTo(volume: Int) {
                Log.i(
                    TAG,
                    "onSetVolumeTo(volume=$volume) thread=${Thread.currentThread().name}"
                )
                val (before, after, changed) = matrix.setLevelPercent(volume)
                setCurrentVolume(after)

                val label = when {
                    after > before -> "volumeUp"
                    after < before -> "volumeDown"
                    else -> "volumeSet"
                }
                lastVolumeEvent = "$label → remVol=$after (setTo, was $before) | ${matrix.albumLabel()}"

                mainHandler.post {
                    publishMetadata()
                    publishPlaybackState(nudgePosition = true)
                    logEvent(
                        "axis B (level) $label → remVol=$after (was $before) | " +
                            "${matrix.albumLabel()} (onSetVolumeTo($volume), setCurrentVolume, ABSOLUTE" +
                            if (changed) ")" else ", no-op)"
                    )
                }
            }
        }
        volumeProvider = provider
        session.setPlaybackToRemote(provider)
        provider.setCurrentVolume(initial)
        logEvent(
            "setPlaybackToRemote(VolumeProviderCompat ABSOLUTE, max=$VOLUME_MAX, " +
                "current=$initial) — Axis B level; expect volumeUp/volumeDown in log on Fit3 key"
        )
    }

    /** After Axis A (slot) change, push this row's remembered remVol into the provider. */
    private fun syncRemoteVolumeToCurrentLevel() {
        val level = matrix.currentLevelPercent()
        try {
            volumeProvider?.setCurrentVolume(level)
        } catch (t: Throwable) {
            Log.w(TAG, "syncRemoteVolumeToCurrentLevel failed", t)
        }
    }

    /**
     * Drive MediaSession play/pause visual from the **current slot's** saved [ControlSlot.isOn].
     * Each matrix row keeps its own toggle; Next/Prev must call this so Fit3 shows the right icon.
     */
    private fun syncPlayingVisualFromCurrentSlot() {
        isPlayingVisual = slotRepository.current().isOn
    }

    /** Map ADJUST_* / OEM ±1-style directions to ±[VOLUME_DELTA] (0 = no-op). */
    private fun directionToDelta(direction: Int): Int = when {
        direction == AudioManager.ADJUST_RAISE || direction > 0 -> VOLUME_DELTA
        direction == AudioManager.ADJUST_LOWER || direction < 0 -> -VOLUME_DELTA
        else -> 0
    }

    private fun handleToggle() {
        val slot = slotRepository.toggleCurrent()
        logEventDeferred("toggle → ${slot.title} = ${if (slot.isOn) "ON" else "OFF"}")
    }

    private fun publishMetadata() {
        val title = matrix.titleLabel()
        val artist = matrix.artistLabel()
        val album = matrix.albumLabel()
        val remVol = matrix.currentLevelPercent()
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, FAKE_DURATION_MS)
            .build()
        mediaSession?.setMetadata(metadata)
        val slotOn = slotRepository.current().isOn
        _metadataPreview.postValue(
            MetadataPreview(
                title = title,
                artist = artist,
                album = album,
                playing = isPlayingVisual,
                slotToggle = if (slotOn) "ON" else "OFF",
                volumeStep = remVol,
                lastVolumeEvent = lastVolumeEvent,
                matrixPosition = matrix.positionReadout(),
                matrixGrid = matrix.gridText()
            )
        )
        Log.d(TAG, "metadata title=$title artist=$artist album=$album remVol=$remVol")
    }

    /**
     * Apply brightness delta on the current slot (FF/REW — independent of matrix level).
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
        syncPlayingVisualFromCurrentSlot()
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
        /** Demo step size per volume key (matches ControlMatrix.LEVEL_STEP_PERCENT). */
        private const val VOLUME_DELTA = 10
    }
}
