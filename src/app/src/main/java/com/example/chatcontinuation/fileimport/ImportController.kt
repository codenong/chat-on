package com.example.chatcontinuation.fileimport

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import com.example.chatcontinuation.R
import com.example.chatcontinuation.backup.BackupManager
import com.example.chatcontinuation.bridge.AssistantBridgeListener
import com.example.chatcontinuation.ui.ShellUi
import com.example.chatcontinuation.ui.WebViewConfigHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 导入编排（设计稿 4.3 / 五.流程 B / 阶段 2）：
 * 系统选择器选 ZIP → 显示文件名与覆盖提示（建议备份，可跳转备份说明）
 * → 用户确认后才挂载流并投递给官方导入器 → 页面内仍会再弹官方覆盖确认。
 * 取消、页面未就绪、找不到导入入口都有明确反馈。
 */
class ImportController(
    private val activity: ComponentActivity,
    private val context: Context,
    private val streamHandler: ImportStreamPathHandler,
    private val backupManager: BackupManager,
    private val ui: ShellUi
) {

    /** 由 MainActivity 在桥接构造完成后注入。 */
    var bridge: AssistantBridgeListener? = null

    private val picker = FilePicker(activity) { uri ->
        if (uri == null) {
            ui.toast(context.getString(R.string.import_cancelled))
        } else {
            onPicked(uri)
        }
    }

    fun launchImport() {
        picker.launch()
    }

    private fun onPicked(uri: Uri) {
        val (displayName, sizeBytes) = describe(uri)
        val sizeText = humanSize(sizeBytes)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.import_confirm_title)
            .setMessage(
                context.getString(R.string.import_confirm_message, displayName, sizeText)
            )
            .setPositiveButton(R.string.import_confirm_ok) { _, _ -> startImport(uri, displayName) }
            .setNegativeButton(R.string.key_setup_cancel, null)
            .setNeutralButton(R.string.import_confirm_backup) { _, _ -> backupManager.showBackupGuide() }
            .show()
    }

    private fun startImport(uri: Uri, displayName: String) {
        val b = bridge
        if (b == null) {
            ui.toast(context.getString(R.string.import_page_not_ready))
            return
        }
        streamHandler.setPending(uri, displayName)
        val delivered = b.deliverImport(WebViewConfigHelper.IMPORT_STREAM_URL, displayName)
        if (delivered) {
            ui.toast(context.getString(R.string.import_delivered))
        } else {
            streamHandler.clear()
            ui.toast(context.getString(R.string.import_page_not_ready))
        }
    }

    /** 读取显示名与大小；读不到时用默认名，大小 -1 表示未知。 */
    private fun describe(uri: Uri): Pair<String, Long> {
        var name = DEFAULT_NAME
        var size = -1L
        try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor: Cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0) ?: DEFAULT_NAME
                        size = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                    }
                }
            if (size < 0) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            }
        } catch (t: Throwable) {
            // 信息读取失败不阻断流程，交给官方导入器校验
        }
        return name to size
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 0 -> context.getString(R.string.file_size_kb, 0f)
        bytes < 1024 -> context.getString(R.string.file_size_bytes, bytes)
        bytes < 1024 * 1024 -> context.getString(R.string.file_size_kb, bytes / 1024f)
        else -> context.getString(R.string.file_size_mb, bytes / 1024f / 1024f)
    }

    companion object {
        private const val DEFAULT_NAME = "deepseek_data.zip"
    }
}
