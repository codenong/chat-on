package com.example.chatcontinuation.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.chatcontinuation.BuildConfig
import com.example.chatcontinuation.R
import com.example.chatcontinuation.util.LogRedactor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于自建 Cloudflare Pages (https://chat-on.pages.dev/apk_version.json) 的版本检测与升级安装。
 *
 * 清单 JSON 示例：
 * {
 *   "versionCode": 14,
 *   "versionName": "0.2.7",
 *   "apkUrl": "https://chat-on.pages.dev/chat-on-0.2.7.apk",
 *   "sha256": "",
 *   "forceUpdate": false,
 *   "releaseNotes": "..."
 * }
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val MANIFEST_URL = "https://chat-on.pages.dev/apk_version.json"
    private const val MAX_REDIRECTS = 5

    private val checking = AtomicBoolean(false)

    @Volatile
    var pendingRelease: ReleaseInfo? = null
        private set

    fun hasPendingUpdate(): Boolean =
        pendingRelease?.let { it.versionCode > BuildConfig.VERSION_CODE } == true

    data class ReleaseInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val forceUpdate: Boolean,
        val releaseNotes: String,
    )

    fun checkForUpdate(
        activity: AppCompatActivity,
        silent: Boolean,
        onStatusChanged: ((ReleaseInfo?) -> Unit)? = null
    ) {
        if (!silent) {
            val cached = pendingRelease
            if (cached != null && cached.versionCode > BuildConfig.VERSION_CODE) {
                showUpgradeDialog(activity, cached)
                return
            }
        }
        if (!checking.compareAndSet(false, true)) {
            if (!silent) {
                toast(activity, activity.getString(R.string.update_checking))
            }
            return
        }
        LogRedactor.i(
            TAG,
            "checkForUpdate start: silent=$silent, localVersionCode=${BuildConfig.VERSION_CODE}, localVersionName=${BuildConfig.VERSION_NAME}"
        )
        if (!silent) {
            toast(activity, activity.getString(R.string.update_checking))
        }
        Thread {
            try {
                val release = fetchManifest()
                if (release != null) {
                    LogRedactor.i(
                        TAG,
                        "compare version: remote=${release.versionCode} (${release.versionName}), local=${BuildConfig.VERSION_CODE}"
                    )
                }
                val available = if (release != null && release.versionCode > BuildConfig.VERSION_CODE) {
                    release
                } else {
                    null
                }
                pendingRelease = available
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    onStatusChanged?.invoke(available)
                    if (!silent) {
                        if (available != null) {
                            showUpgradeDialog(activity, available)
                        } else if (release == null) {
                            toast(activity, activity.getString(R.string.update_no_release))
                        } else {
                            toast(
                                activity,
                                activity.getString(R.string.update_already_latest, BuildConfig.VERSION_NAME)
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                LogRedactor.w(TAG, "update check failed: ${t.javaClass.simpleName}: ${t.message}")
                if (!silent) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            toast(activity, activity.getString(R.string.update_check_failed))
                        }
                    }
                }
            } finally {
                checking.set(false)
            }
        }.start()
    }

    private fun showUpgradeDialog(activity: AppCompatActivity, release: ReleaseInfo) {
        val displayVer = release.versionName.ifBlank { release.versionCode.toString() }
        val notes = release.releaseNotes.ifBlank { activity.getString(R.string.update_no_notes) }
        val message = activity.getString(
            R.string.update_found_message,
            BuildConfig.VERSION_NAME,
            displayVer,
            notes
        )
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_found_title, displayVer))
            .setMessage(message)
            .setPositiveButton(R.string.update_upgrade_now) { _, _ ->
                startDownloadAndInstall(activity, release)
            }
        if (release.forceUpdate) {
            builder.setCancelable(false)
        } else {
            builder.setNegativeButton(R.string.update_later, null)
        }
        builder.show()
    }

    private fun startDownloadAndInstall(activity: AppCompatActivity, release: ReleaseInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            toast(activity, activity.getString(R.string.update_install_permission_hint))
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
            return
        }

        val progressDialog = createDownloadingDialog(activity)
        progressDialog.show()

        Thread {
            var apkFile: File? = null
            var verifyFailed = false
            try {
                val downloaded = downloadApk(activity, release.apkUrl, release.versionCode)
                if (release.sha256.isNotBlank() && !verifySha256(downloaded, release.sha256)) {
                    downloaded.delete()
                    verifyFailed = true
                } else {
                    apkFile = downloaded
                }
            } catch (t: Throwable) {
                LogRedactor.w(TAG, "apk download failed: ${t.javaClass.simpleName}")
            }
            activity.runOnUiThread {
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (verifyFailed) {
                    toast(activity, activity.getString(R.string.update_verify_failed))
                    return@runOnUiThread
                }
                val file = apkFile
                if (file == null || !file.exists() || file.length() <= 0L) {
                    toast(activity, activity.getString(R.string.update_download_failed))
                    return@runOnUiThread
                }
                installApk(activity, file)
            }
        }.start()
    }

    private fun createDownloadingDialog(activity: AppCompatActivity): AlertDialog {
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        val bar = ProgressBar(activity)
        val label = TextView(activity).apply {
            text = activity.getString(R.string.update_downloading)
            setPadding(pad / 2, pad / 4, 0, 0)
        }
        container.addView(bar)
        container.addView(label)
        return MaterialAlertDialogBuilder(activity)
            .setView(container)
            .setCancelable(false)
            .create()
    }

    private fun fetchManifest(): ReleaseInfo? {
        val url = "$MANIFEST_URL?t=${System.currentTimeMillis()}"
        LogRedactor.i(TAG, "fetching manifest: $url")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            useCaches = false
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "chat-on-android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            LogRedactor.i(TAG, "manifest HTTP status: $code")
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val versionCode = json.optInt("versionCode", -1)
            val rawApkUrl = json.optString("apkUrl").trim()
            if (versionCode <= 0 || rawApkUrl.isEmpty()) {
                LogRedactor.w(TAG, "manifest invalid: versionCode=$versionCode, rawApkUrlEmpty=${rawApkUrl.isEmpty()}")
                return null
            }
            val resolvedUrl = URL(URL(MANIFEST_URL), rawApkUrl).toString()
            if (!resolvedUrl.startsWith("https://")) {
                LogRedactor.w(TAG, "manifest rejected non-https apkUrl: $resolvedUrl")
                return null
            }
            LogRedactor.i(TAG, "manifest parsed: versionCode=$versionCode, apkUrl=$resolvedUrl")
            return ReleaseInfo(
                versionCode = versionCode,
                versionName = json.optString("versionName").trim(),
                apkUrl = resolvedUrl,
                sha256 = json.optString("sha256").trim(),
                forceUpdate = json.optBoolean("forceUpdate", false),
                releaseNotes = json.optString("releaseNotes").trim(),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadApk(
        activity: AppCompatActivity,
        apkUrl: String,
        versionCode: Int
    ): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val outFile = File(dir, "chat-on-$versionCode.apk")

        var currentUrl = apkUrl
        var redirects = 0
        while (true) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "chat-on-android/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == 307 || code == 308
                ) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Missing redirect Location")
                    if (++redirects > MAX_REDIRECTS) {
                        throw IllegalStateException("Too many redirects")
                    }
                    val nextUrl = URL(URL(currentUrl), location).toString()
                    if (!nextUrl.startsWith("https://")) {
                        throw IllegalStateException("Non-HTTPS redirect")
                    }
                    currentUrl = nextUrl
                    continue
                }
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output, bufferSize = 16 * 1024)
                    }
                }
                return outFile
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun verifySha256(file: File, expectedHex: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
        return actualHex.equals(expectedHex.trim(), ignoreCase = true)
    }

    private fun installApk(activity: AppCompatActivity, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun toast(activity: AppCompatActivity, message: String) {
        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
