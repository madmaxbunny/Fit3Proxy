package com.madmaxbunny.fit3proxy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.media.AudioManager
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.madmaxbunny.fit3proxy.BuildConfig
import com.madmaxbunny.fit3proxy.Fit3ProxyApp
import com.madmaxbunny.fit3proxy.R
import com.madmaxbunny.fit3proxy.databinding.ActivityMainBinding
import com.madmaxbunny.fit3proxy.notification.AlertNotifier
import com.madmaxbunny.fit3proxy.service.Fit3ProxyForegroundService
import com.madmaxbunny.fit3proxy.session.Fit3MediaSessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Material dashboard: session switch, live metadata preview, alert test, event log.
 * Phase 2 adds emergency alert fire + Notification Action event logging.
 *
 * Phone hardware volume keys are handled here (STREAM_MUSIC + FLAG_SHOW_UI) and
 * consumed so they do not reach the MediaSession VolumeProvider / remVol path.
 * Fit3 / Wearable remote volume still goes through VolumeProviderCompat.
 */
class MainActivity : AppCompatActivity(), Fit3MediaSessionManager.EventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: Fit3MediaSessionManager
    private lateinit var app: Fit3ProxyApp
    private val logBuilder = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appendLog("POST_NOTIFICATIONS ${if (granted) "granted" else "denied"}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        app = application as Fit3ProxyApp
        sessionManager = app.mediaSessionManager
        sessionManager.eventListener = this

        ensureNotificationPermission()
        bindAppVersion()
        bindUi()
        observeSession()
        observeEventLog()

        appendLog("대시보드 준비 완료 (Phase 2 Notification Actions)")
        appendLog("슬롯 ${app.slotRepository.size}개 로드 (인메모리 데모)")
        appendLog(
            "2D 매트릭스 ${app.slotRepository.matrix.rowCount}×${app.slotRepository.matrix.colCount} " +
                "(Next/Prev=Axis A, Fit3 vol=Axis B per-slot)"
        )
    }


    /**
     * Foreground phone volume buttons → normal STREAM_MUSIC UX.
     * Consume so MediaSession remote VolumeProvider does not remap them to remVol.
     * Fit3/Wearable volume still hits VolumeProviderCompat.onAdjustVolume / onSetVolumeTo.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // Include key-repeat so hold-to-change matches system volume UX.
                    adjustLocalMusicVolume(event.keyCode, logEvent = event.repeatCount == 0)
                }
                // Consume DOWN and UP (and repeats) so the session never sees them.
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun adjustLocalMusicVolume(keyCode: Int, logEvent: Boolean) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return
        }
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
        // Do not touch remVol / volumeStep — phone local volume only.
        if (logEvent) {
            appendLog(
                "phone VOLUME key → STREAM_MUSIC adjust (local UI; remVol unchanged)"
            )
        }
    }

    override fun onDestroy() {
        if (sessionManager.eventListener === this) {
            sessionManager.eventListener = null
        }
        super.onDestroy()
    }

    override fun onEvent(message: String) {
        mainHandler.post { appendLog(message) }
    }

    private fun bindAppVersion() {
        val label = getString(
            R.string.app_version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        binding.appVersionLabel.text = label
        supportActionBar?.subtitle = label
    }

    private fun bindUi() {
        binding.switchSession.setOnCheckedChangeListener { _, isChecked ->
            onSessionSwitch(isChecked)
        }

        binding.btnClearLog.setOnClickListener {
            logBuilder.clear()
            binding.tvEventLog.text = ""
        }

        binding.btnTestAlert.setOnClickListener {
            appendLog("UI: 긴급 알림 테스트 버튼 → AlertNotifier")
            AlertNotifier.fireTestEmergencyAlert(this)
        }

        if (sessionManager.isActive()) {
            binding.switchSession.isChecked = true
            binding.tvSessionStatus.setText(R.string.session_active)
        }
    }

    private fun onSessionSwitch(isChecked: Boolean) {
        if (isChecked) {
            Fit3ProxyForegroundService.start(this)
            binding.tvSessionStatus.setText(R.string.session_active)
            appendLog("UI: 세션 ON → FGS + MediaSession 시작")
        } else {
            Fit3ProxyForegroundService.stop(this)
            binding.tvSessionStatus.setText(R.string.session_inactive)
            appendLog("UI: 세션 OFF → FGS + MediaSession 중지")
        }
    }

    private fun observeSession() {
        sessionManager.metadataPreview.observe(this) { preview ->
            binding.tvPreviewTitle.text = preview.title
            binding.tvPreviewArtist.text = preview.artist
            binding.tvPreviewAlbum.text = preview.album
            binding.tvSlotToggle.text = preview.slotToggle
            binding.tvLastVolumeEvent.text =
                "remVol=${preview.volumeStep} | ${preview.lastVolumeEvent}"
            binding.tvMatrixPosition.text = preview.matrixPosition
            binding.tvMatrixGrid.text = preview.matrixGrid
        }
        sessionManager.sessionActive.observe(this) { active ->
            if (binding.switchSession.isChecked != active) {
                binding.switchSession.setOnCheckedChangeListener(null)
                binding.switchSession.isChecked = active
                binding.tvSessionStatus.setText(
                    if (active) R.string.session_active else R.string.session_inactive
                )
                binding.switchSession.setOnCheckedChangeListener { _, isChecked ->
                    onSessionSwitch(isChecked)
                }
            }
        }
    }

    private fun observeEventLog() {
        app.eventLogStore.events.observe(this) { message ->
            appendLog(message)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun appendLog(message: String) {
        val line = "[${timeFormat.format(Date())}] $message\n"
        logBuilder.append(line)
        binding.tvEventLog.text = logBuilder.toString()
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
