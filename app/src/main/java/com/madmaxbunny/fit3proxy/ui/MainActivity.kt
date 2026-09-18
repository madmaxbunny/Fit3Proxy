package com.madmaxbunny.fit3proxy.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.media.AudioManager
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.madmaxbunny.fit3proxy.BuildConfig
import com.madmaxbunny.fit3proxy.Fit3ProxyApp
import com.madmaxbunny.fit3proxy.R
import com.madmaxbunny.fit3proxy.databinding.ActivityMainBinding
import com.madmaxbunny.fit3proxy.notification.AlertNotifier
import com.madmaxbunny.fit3proxy.service.Fit3ProxyForegroundService
import com.madmaxbunny.fit3proxy.session.Fit3MediaSessionManager
import com.madmaxbunny.fit3proxy.update.ApkDownloader
import com.madmaxbunny.fit3proxy.update.ApkInstaller
import com.madmaxbunny.fit3proxy.update.AppUpdateChecker
import com.madmaxbunny.fit3proxy.update.ReleaseInfo
import com.madmaxbunny.fit3proxy.push.PushApiClient
import com.madmaxbunny.fit3proxy.push.PushPrefs
import com.madmaxbunny.fit3proxy.push.PushTokenRegistrar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Material dashboard: session switch, live metadata preview, alert test, event log,
 * GitHub Releases in-app update, and FCM token + Push API registration.
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

    private var updateJob: Job? = null
    private var pendingApk: File? = null
    private var latestRelease: ReleaseInfo? = null
    private var installReceiver: BroadcastReceiver? = null
    private var silentCheckDone = false
    private var pushJob: Job? = null
    private var currentFcmToken: String? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appendLog("POST_NOTIFICATIONS ${if (granted) "granted" else "denied"}")
        }

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (ApkInstaller.canRequestInstall(this)) {
                pendingApk?.let { launchInstall(it) }
            } else {
                Toast.makeText(this, R.string.update_need_permission, Toast.LENGTH_LONG).show()
                appendLog("업데이트: 알 수 없는 앱 설치 권한 거부")
            }
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
        registerInstallReceiver()

        appendLog("대시보드 준비 완료 (Phase 2 + in-app updater)")
        appendLog("슬롯 ${app.slotRepository.size}개 로드 (인메모리 데모)")
        appendLog(
            "2D 매트릭스 ${app.slotRepository.matrix.rowCount}×${app.slotRepository.matrix.colCount} " +
                "(Next/Prev=Axis A, Fit3 vol=Axis B per-slot)"
        )

        // Auto-check on launch (fail soft)
        checkForUpdate(userInitiated = false)

        bindPushUi()
        // Fetch FCM token; auto-register when API key + userId present
        refreshFcmTokenAndMaybeRegister(userInitiated = false)
    }

    override fun onDestroy() {
        if (sessionManager.eventListener === this) {
            sessionManager.eventListener = null
        }
        installReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        installReceiver = null
        updateJob?.cancel()
        pushJob?.cancel()
        super.onDestroy()
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
                    adjustLocalMusicVolume(event.keyCode, logEvent = event.repeatCount == 0)
                }
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
        if (logEvent) {
            appendLog(
                "phone VOLUME key → STREAM_MUSIC adjust (local UI; remVol unchanged)"
            )
        }
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

        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdate(userInitiated = true)
        }

        binding.btnInstallUpdate.setOnClickListener {
            val apk = pendingApk
            if (apk == null || !apk.exists()) {
                Toast.makeText(this, R.string.update_status_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ensureInstallPermissionThenInstall(apk)
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
            // Re-attempt Push API register on session start (idempotent upsert)
            refreshFcmTokenAndMaybeRegister(userInitiated = false)
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

    private fun registerInstallReceiver() {
        installReceiver = ApkInstaller.registerStatusReceiver(this) { status, msg ->
            appendLog("PackageInstaller status=$status ${msg.orEmpty()}")
        }
    }

    private fun checkForUpdate(userInitiated: Boolean) {
        if (updateJob?.isActive == true) return
        binding.tvUpdateStatus.setText(R.string.update_status_checking)
        binding.btnCheckUpdate.isEnabled = false
        binding.progressUpdate.visibility = View.GONE
        if (!userInitiated && silentCheckDone) {
            binding.btnCheckUpdate.isEnabled = true
            return
        }

        updateJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AppUpdateChecker.checkLatest(BuildConfig.VERSION_CODE)
            }
            binding.btnCheckUpdate.isEnabled = true

            when (result) {
                is AppUpdateChecker.Result.Available -> {
                    latestRelease = result.info
                    showCompare(result.info)
                    appendLog(
                        "업데이트: 새 버전 ${result.info.versionName} " +
                            "(code ${result.info.versionCode}) 발견 → 다운로드"
                    )
                    binding.tvUpdateStatus.text = getString(
                        R.string.update_status_available,
                        result.info.versionName,
                        result.info.versionCode
                    )
                    downloadAndPrepare(result.info)
                }
                is AppUpdateChecker.Result.UpToDate -> {
                    latestRelease = result.info
                    result.info?.let { showCompare(it) }
                    binding.tvUpdateStatus.text = getString(
                        R.string.update_status_uptodate,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                        result.info?.versionName ?: BuildConfig.VERSION_NAME,
                        result.info?.versionCode ?: BuildConfig.VERSION_CODE
                    )
                    binding.btnInstallUpdate.visibility = View.GONE
                    binding.btnInstallUpdate.isEnabled = false
                    pendingApk = null
                    if (userInitiated) {
                        Toast.makeText(this@MainActivity, R.string.update_toast_uptodate, Toast.LENGTH_SHORT).show()
                        appendLog("업데이트: 이미 최신")
                    } else {
                        appendLog("업데이트: 최신 확인 (자동)")
                    }
                }
                is AppUpdateChecker.Result.Failed -> {
                    binding.tvUpdateStatus.text =
                        getString(R.string.update_status_failed, result.reason)
                    if (userInitiated) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.update_toast_offline,
                            Toast.LENGTH_SHORT
                        ).show()
                        appendLog("업데이트 실패: ${result.reason}")
                    } else {
                        // Fail soft on launch — silent
                        appendLog("업데이트 자동확인 스킵: ${result.reason}")
                    }
                }
            }
            silentCheckDone = true
        }
    }

    private fun showCompare(info: ReleaseInfo) {
        binding.tvUpdateCompare.visibility = View.VISIBLE
        binding.tvUpdateCompare.text = getString(
            R.string.update_compare_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            info.versionName,
            info.versionCode
        )
    }

    private suspend fun downloadAndPrepare(info: ReleaseInfo) {
        binding.progressUpdate.visibility = View.VISIBLE
        binding.progressUpdate.progress = 0
        binding.btnInstallUpdate.visibility = View.VISIBLE
        binding.btnInstallUpdate.isEnabled = false
        try {
            val file = withContext(Dispatchers.IO) {
                ApkDownloader.download(this@MainActivity, info) { downloaded, total ->
                    mainHandler.post {
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            binding.progressUpdate.isIndeterminate = false
                            binding.progressUpdate.progress = pct
                            binding.tvUpdateStatus.text =
                                getString(R.string.update_status_downloading, pct)
                        } else {
                            binding.progressUpdate.isIndeterminate = true
                            binding.tvUpdateStatus.text =
                                getString(R.string.update_status_downloading, 0)
                        }
                    }
                }
            }
            pendingApk = file
            binding.progressUpdate.isIndeterminate = false
            binding.progressUpdate.progress = 100
            binding.tvUpdateStatus.setText(R.string.update_status_ready)
            binding.btnInstallUpdate.isEnabled = true
            appendLog("업데이트: APK 다운로드 완료 (${file.name}, ${file.length()} bytes)")
        } catch (e: Exception) {
            binding.progressUpdate.visibility = View.GONE
            binding.btnInstallUpdate.visibility = View.GONE
            binding.tvUpdateStatus.text =
                getString(R.string.update_status_failed, e.message ?: e.javaClass.simpleName)
            appendLog("업데이트 다운로드 실패: ${e.message}")
        }
    }

    private fun ensureInstallPermissionThenInstall(apk: File) {
        if (!ApkInstaller.canRequestInstall(this)) {
            appendLog("업데이트: 알 수 없는 앱 설치 설정 화면으로 이동")
            Toast.makeText(this, R.string.update_need_permission, Toast.LENGTH_LONG).show()
            installPermissionLauncher.launch(ApkInstaller.intentToUnknownSourcesSettings(this))
            return
        }
        launchInstall(apk)
    }

    private fun launchInstall(apk: File) {
        appendLog("업데이트: 설치 UI 시작 (사용자 확인 1회 필요)")
        val ok = ApkInstaller.install(this, apk)
        if (!ok) {
            Toast.makeText(
                this,
                getString(R.string.update_status_failed, "install intent"),
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun bindPushUi() {
        binding.etPushUserId.setText(PushPrefs.getUserId(this))
        val cached = PushPrefs.getLastToken(this)
        currentFcmToken = cached
        binding.tvFcmToken.text = PushTokenRegistrar.truncateToken(cached)
        val lastStatus = PushPrefs.getLastStatus(this)
        if (lastStatus.isNotBlank()) {
            binding.tvFcmStatus.text = lastStatus
        }
        if (!PushApiClient.hasApiKey()) {
            binding.tvFcmStatus.setText(R.string.fcm_status_missing_key)
            appendLog("FCM: PUSH_API_KEY 미설정 — 토큰만 로컬 표시, Push API 등록 불가")
        } else {
            appendLog("FCM: PUSH_API_KEY 빌드에 포함됨 — 토큰 등록 가능")
        }

        binding.btnRegisterToken.setOnClickListener {
            persistUserIdFromField()
            refreshFcmTokenAndMaybeRegister(userInitiated = true)
        }

        binding.etPushUserId.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistUserIdFromField()
        }
    }

    private fun persistUserIdFromField() {
        val userId = binding.etPushUserId.text?.toString()?.trim().orEmpty()
            .ifBlank { PushPrefs.DEFAULT_USER_ID }
        PushPrefs.setUserId(this, userId)
        if (binding.etPushUserId.text?.toString()?.trim().isNullOrBlank()) {
            binding.etPushUserId.setText(userId)
        }
    }

    /**
     * Fetch FCM token for debug display; register with Push API when key+userId present.
     * On launch (userInitiated=false) auto-attempts register if possible.
     */
    private fun refreshFcmTokenAndMaybeRegister(userInitiated: Boolean) {
        if (pushJob?.isActive == true) return
        persistUserIdFromField()
        binding.btnRegisterToken.isEnabled = false
        if (userInitiated) {
            binding.tvFcmStatus.text = "등록 중…"
        } else if (binding.tvFcmStatus.text.isNullOrBlank()) {
            binding.tvFcmStatus.text = "FCM 토큰 확인 중…"
        }

        pushJob = lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                PushTokenRegistrar.fetchAndMaybeRegister(
                    this@MainActivity,
                    registerIfPossible = true
                )
            }
            currentFcmToken = outcome.token
            binding.tvFcmToken.text = PushTokenRegistrar.truncateToken(outcome.token)
            binding.tvFcmStatus.text = outcome.statusMessage
            binding.btnRegisterToken.isEnabled = true
            if (userInitiated) {
                val toast = if (outcome.registered) {
                    "토큰 등록 성공"
                } else {
                    outcome.statusMessage.take(80)
                }
                Toast.makeText(this@MainActivity, toast, Toast.LENGTH_SHORT).show()
            }
            appendLog(
                "FCM: token=${PushTokenRegistrar.truncateToken(outcome.token)} | ${outcome.statusMessage}"
            )
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
