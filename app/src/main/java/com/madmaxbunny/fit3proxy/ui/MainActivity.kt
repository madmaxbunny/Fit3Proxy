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
import com.madmaxbunny.fit3proxy.Fit3ProxyApp
import com.madmaxbunny.fit3proxy.R
import com.madmaxbunny.fit3proxy.databinding.ActivityMainBinding
import com.madmaxbunny.fit3proxy.service.Fit3ProxyForegroundService
import com.madmaxbunny.fit3proxy.session.Fit3MediaSessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Material dashboard: session switch, live metadata preview, scrolling event log.
 */
class MainActivity : AppCompatActivity(), Fit3MediaSessionManager.EventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: Fit3MediaSessionManager
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

        val app = application as Fit3ProxyApp
        sessionManager = app.mediaSessionManager
        sessionManager.eventListener = this

        ensureNotificationPermission()
        bindUi()
        observeSession()

        appendLog("대시보드 준비 완료 (Phase 1 MediaSession 프로토타입)")
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

    private fun bindUi() {
        binding.switchSession.setOnCheckedChangeListener { _, isChecked ->
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

        binding.btnClearLog.setOnClickListener {
            logBuilder.clear()
            binding.tvEventLog.text = ""
        }

        // Sync switch if service already running (process retained)
        if (sessionManager.isActive()) {
            binding.switchSession.isChecked = true
            binding.tvSessionStatus.setText(R.string.session_active)
        }
    }

    private fun observeSession() {
        sessionManager.metadataPreview.observe(this) { preview ->
            binding.tvPreviewTitle.text = preview.title
            binding.tvPreviewArtist.text = preview.artist
            binding.tvPreviewAlbum.text = preview.album
        }
        sessionManager.sessionActive.observe(this) { active ->
            if (binding.switchSession.isChecked != active) {
                binding.switchSession.setOnCheckedChangeListener(null)
                binding.switchSession.isChecked = active
                binding.tvSessionStatus.setText(
                    if (active) R.string.session_active else R.string.session_inactive
                )
                bindSwitchListenerOnly()
            }
        }
    }

    private fun bindSwitchListenerOnly() {
        binding.switchSession.setOnCheckedChangeListener { _, isChecked ->
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
