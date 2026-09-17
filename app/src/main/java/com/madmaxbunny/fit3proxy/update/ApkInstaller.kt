package com.madmaxbunny.fit3proxy.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

/**
 * Installs a downloaded APK. Prefers [PackageInstaller] session; falls back to
 * FileProvider + ACTION_VIEW / ACTION_INSTALL_PACKAGE.
 *
 * Reality: non-system apps always need one user confirmation — silent install
 * is not possible without device-owner / system privileges.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    const val ACTION_INSTALL_STATUS = "com.madmaxbunny.fit3proxy.UPDATE_INSTALL_STATUS"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun canRequestInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun intentToUnknownSourcesSettings(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun apkContentUri(context: Context, apkFile: File): Uri {
        return FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITY_SUFFIX,
            apkFile
        )
    }

    /**
     * Starts install UI. Returns true if a session/intent was launched.
     */
    fun install(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "APK missing: ${apkFile.absolutePath}")
            return false
        }
        return try {
            installViaPackageInstaller(context, apkFile)
            true
        } catch (e: Exception) {
            Log.w(TAG, "PackageInstaller failed, falling back to ACTION_VIEW", e)
            installViaActionView(context, apkFile)
        }
    }

    private fun installViaPackageInstaller(context: Context, apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        // Even with USER_ACTION_NOT_REQUIRED, non-system apps still get a confirm UI on most OEMs.
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apkFile).use { input ->
                session.openWrite("fit3proxy.apk", 0, apkFile.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val callback = Intent(ACTION_INSTALL_STATUS).apply {
                setPackage(context.packageName)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pi = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pi.intentSender)
        }
        Log.i(TAG, "PackageInstaller session $sessionId committed")
    }

    private fun installViaActionView(context: Context, apkFile: File): Boolean {
        val uri = apkContentUri(context, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_VIEW install failed", e)
            // Legacy alias
            val legacy = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            try {
                context.startActivity(legacy)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "ACTION_INSTALL_PACKAGE failed", e2)
                false
            }
        }
    }

    /**
     * Optional receiver registration for PackageInstaller status (logs only).
     */
    fun registerStatusReceiver(context: Context, onStatus: (Int, String?) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirm = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(confirm)
                    }
                }
                onStatus(status, msg)
            }
        }
        val filter = IntentFilter(ACTION_INSTALL_STATUS)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return receiver
    }
}
