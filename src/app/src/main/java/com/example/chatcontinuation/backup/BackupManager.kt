package com.example.chatcontinuation.backup

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.chatcontinuation.R
import com.example.chatcontinuation.ui.ShellUi
import com.example.chatcontinuation.util.LogRedactor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 备份辅助（设计稿 6.1 BackupManager / 6.2）：
 * - 说明：备份走上游 设置 → 数据管理 → 导出全部历史；导出内容不含 API Key。
 * - 落盘：WebView 中 blob 下载不可用，注入脚本把导出 ZIP 分块（base64）
 *   经受限桥接发回原生，这里先写入缓存临时文件，用户选择保存位置后一次性拷贝。
 * - 恢复：使用“导入 DeepSeek 聊天记录”选择备份 ZIP（导入是覆盖操作）。
 *
 * 桥接消息协议见 assets/inject/native-bridge.js 的 exportBlob()。
 */
class BackupManager(
    private val activity: ComponentActivity,
    private val ui: ShellUi
) {

    /**
     * 非 UI 用途的 Context（cacheDir/getString），即 [activity] 本身。
     * 对话框必须直接用 [activity]：Material 组件要求 Activity 主题
     * （ThemeEnforcement 崩溃防护，见 scripts/check_ui_context.sh 审计）。
     */
    private val context: Context get() = activity

    private class Session(
        val id: String,
        val fileName: String,
        val sizeBytes: Long,
        val tempFile: File
    ) {
        @Volatile var completed: Boolean = false
        var output: FileOutputStream? = null
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val sessionRef = AtomicReference<Session?>(null)
    private val targetRef = AtomicReference<Uri?>(null)
    private val lock = Any()

    private val saveLauncher =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            onExportTarget(uri)
        }

    /** 备份与恢复说明对话框（用户可见文案强调备份不含 Key、需妥善保管）。 */
    fun showBackupGuide() {
        MaterialAlertDialogBuilder(dialogContext())
            .setTitle(R.string.backup_guide_title)
            .setMessage(R.string.backup_guide_message)
            .setPositiveButton(R.string.key_setup_cancel, null)
            .show()
    }

    /**
     * 对话框专用上下文：applicationContext 不携带 AppCompat/Material3 主题，
     * MaterialAlertDialogBuilder 会抛 "requires your app theme to be Theme.AppCompat"；
     * 显式包一层应用主题，保证任何调用点都拿到合法主题。
     */
    private fun dialogContext(): Context =
        android.view.ContextThemeWrapper(activity, R.style.Theme_DeepSeekAssistant)

    fun onExportStart(json: JSONObject) {
        val id = json.optString("id")
        val name = sanitizeFileName(json.optString("name").ifEmpty { DEFAULT_EXPORT_NAME })
        val size = json.optLong("size", -1L)
        if (size > MAX_EXPORT_BYTES) {
            // 防御：脚本侧同样有检查；出现即中止并提示
            sessionRef.getAndSet(null)?.let { cleanup(it) }
            ui.toast(context.getString(R.string.backup_export_too_large))
            return
        }
        synchronized(lock) {
            sessionRef.getAndSet(null)?.let { cleanup(it) }
            val temp = File(context.cacheDir, "assistant_export_${System.currentTimeMillis()}.zip")
            temp.delete()
            sessionRef.set(Session(id, name, size, temp))
            ui.toast(context.getString(R.string.backup_export_picking))
            saveLauncher.launch(name)
        }
    }

    fun onExportChunk(json: JSONObject) {
        val session = sessionRef.get() ?: return
        if (json.optString("id") != session.id || session.completed) return
        val data = try {
            Base64.getMimeDecoder().decode(json.optString("data"))
        } catch (t: Throwable) {
            LogRedactor.w(TAG, "export chunk decode failed")
            return
        }
        executor.execute {
            synchronized(lock) {
                val current = sessionRef.get() ?: return@execute
                if (current.id != session.id) return@execute
                try {
                    val out = current.output ?: FileOutputStream(current.tempFile, true).also {
                        current.output = it
                    }
                    out.write(data)
                } catch (t: Throwable) {
                    LogRedactor.w(TAG, "export write failed: ${t.javaClass.simpleName}")
                    failSession(current)
                }
            }
        }
    }

    fun onExportEnd(json: JSONObject) {
        val session = sessionRef.get() ?: return
        if (json.optString("id") != session.id) return
        executor.execute {
            synchronized(lock) {
                val current = sessionRef.get() ?: return@execute
                if (current.id != session.id) return@execute
                try {
                    current.output?.flush()
                } catch (_: Throwable) {
                }
                runCatching { current.output?.close() }
                current.output = null
                current.completed = true
                targetRef.get()?.let { target ->
                    copyToTarget(current, target)
                }
            }
        }
    }

    fun onExportError(json: JSONObject) {
        synchronized(lock) {
            val current = sessionRef.getAndSet(null) ?: return
            if (json.optString("id") != current.id) {
                sessionRef.set(current)
                return
            }
            cleanup(current)
            targetRef.set(null)
            ui.toast(context.getString(R.string.backup_export_receive_failed))
        }
    }

    private fun onExportTarget(uri: Uri?) {
        synchronized(lock) {
            if (uri == null) {
                val current = sessionRef.get()
                if (current?.completed == true) {
                    ui.toast(context.getString(R.string.backup_export_cancelled))
                }
                sessionRef.getAndSet(null)?.let { cleanup(it) }
                targetRef.set(null)
                return
            }
            targetRef.set(uri)
            val current = sessionRef.get()
            if (current?.completed == true) {
                copyToTarget(current, uri)
            }
        }
    }

    /** 拷贝到用户选择的位置；在后台线程执行。 */
    private fun copyToTarget(session: Session, target: Uri) {
        executor.execute {
            try {
                context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                    session.tempFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                ui.toast(context.getString(R.string.backup_export_done))
            } catch (t: Throwable) {
                LogRedactor.w(TAG, "export copy failed: ${t.javaClass.simpleName}")
                ui.toast(context.getString(R.string.backup_export_failed))
            } finally {
                synchronized(lock) {
                    sessionRef.compareAndSet(session, null)
                    targetRef.set(null)
                    cleanup(session)
                }
            }
        }
    }

    private fun failSession(session: Session) {
        if (sessionRef.getAndSet(null) === session) {
            targetRef.set(null)
            ui.toast(context.getString(R.string.backup_export_receive_failed))
        }
        cleanup(session)
    }

    private fun cleanup(session: Session) {
        runCatching { session.output?.close() }
        session.output = null
        session.tempFile.delete()
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[/\\:*?\"<>|]"""), "_").take(120)

    companion object {
        private const val TAG = "BackupManager"
        private const val DEFAULT_EXPORT_NAME = "deepseek-backup.zip"
        private const val MAX_EXPORT_BYTES = 400L * 1024 * 1024
    }
}
