package com.madmaxbunny.fit3proxy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            binding.tvLastVolumeEvent.text =
                "step=${preview.volumeStep} | ${preview.lastVolumeEvent}"
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
