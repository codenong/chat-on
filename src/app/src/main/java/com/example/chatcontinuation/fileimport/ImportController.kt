package com.example.chatcontinuation.fileimport

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import com.example.chatcontinuation.R
import com.example.chatcontinuation.backup.BackupManager
import com.example.chatcontinuation.bridge.AssistantBridgeListener
import com.example.chatcontinuation.ui.ShellUi
import com.example.chatcontinuation.ui.WebViewConfigHelper
import com.example.chatcontinuation.util.LogRedactor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * 导入编排（设计稿 4.3 / 五.流程 B / 阶段 2 与 0.1.4 选择导入）：
 * 系统选择器选 ZIP → 复制到缓存并扫描索引（manifest 校验 + 会话列表）
 * → 用户在对话框中勾选要导入的对话（或「全部导入」）→ 确认覆盖提示
 * （选择导入时按官方条目顺序重建过滤包）→ 挂载流并投递给官方导入器
 * → 页面内官方覆盖确认。取消、页面未就绪、找不到导入入口都有明确反馈。
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

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val scanner = ArchiveScanner(context)
    private val builder = FilteredArchiveBuilder()

    /** 导入流程产生的临时文件（缓存副本/重建包），随流程终结清理。 */
    private val tempFiles = ArrayList<File>()

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

    /** 页面侧导入流程终结（成功或最终失败）后，释放暂存流并清理临时文件。 */
    fun clearPendingStream() {
        streamHandler.clear()
        cleanupTempFiles()
    }

    // ---------- 选择导入（0.1.4） ----------

    private fun onPicked(uri: Uri) {
        cleanupTempFiles()
        val progress = showProgress(R.string.import_scan_progress)
        executor.execute {
            try {
                val result = scanner.scan(uri)
                main.post {
                    dismissDialog(progress)
                    onScanOk(result)
                }
            } catch (t: Throwable) {
                LogRedactor.w(TAG, "archive scan failed: ${t.javaClass.simpleName}")
                main.post {
                    dismissDialog(progress)
                    ui.toast(context.getString(R.string.import_scan_failed))
                }
            }
        }
    }

    private fun onScanOk(result: ArchiveScanner.ScanResult) {
        if (!canShowUi()) {
            cleanupTempFiles()
            return
        }
        // 展示顺序：最近更新的会话在前（updated_at 为 ISO 格式，字符串比较即可）
        showSelectDialog(result, result.indexEntries.sortedByDescending { it.updatedAt })
    }

    private fun showSelectDialog(result: ArchiveScanner.ScanResult, entries: List<HistoryIndexEntry>) {
        // 对话框与内容视图统一使用带应用主题的上下文：applicationContext
        // 无 Material3 主题，MaterialAlertDialogBuilder 构造时会直接崩溃（0.1.4）
        val dctx = dialogContext()
        val view = LayoutInflater.from(dctx).inflate(R.layout.dialog_import_select, null)
        val listView = view.findViewById<ListView>(android.R.id.list)
        val countView = view.findViewById<TextView>(R.id.import_select_count)
        val checked = BooleanArray(entries.size)
        val inflater = LayoutInflater.from(dctx)

        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = entries.size
            override fun getItem(position: Int): Any = entries[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: inflater.inflate(R.layout.item_conversation_select, parent, false)
                val entry = entries[position]
                row.findViewById<TextView>(R.id.import_item_title).text =
                    entry.title ?: context.getString(R.string.import_select_untitled)
                row.findViewById<TextView>(R.id.import_item_subtitle).text = context.getString(
                    R.string.import_item_subtitle_fmt,
                    entry.messageCount,
                    formatTimestamp(entry.updatedAt)
                )
                (row as CheckableLinearLayout).isChecked = checked[position]
                return row
            }
        }

        fun selectedEntries(): List<HistoryIndexEntry> =
            entries.filterIndexed { index, _ -> checked[index] }

        fun repaintVisible() {
            for (i in 0 until listView.childCount) {
                val pos = listView.firstVisiblePosition + i
                (listView.getChildAt(i) as? CheckableLinearLayout)?.isChecked = checked[pos]
            }
        }

        val dialog = MaterialAlertDialogBuilder(dctx)
            .setTitle(R.string.import_select_title)
            .setView(view)
            .setPositiveButton(R.string.import_select_ok, null)
            .setNegativeButton(R.string.key_setup_cancel) { _, _ -> cleanupTempFiles() }
            .setNeutralButton(R.string.import_select_full) { _, _ -> showFullImportConfirm(result) }
            .create()

        fun refreshHeader() {
            val positive = listView.tag as? Button
            val n = checked.count { it }
            countView.text = context.getString(R.string.import_select_count_fmt, n, entries.size)
            positive?.isEnabled = n > 0
        }

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            checked[position] = !checked[position]
            repaintVisible()
            refreshHeader()
        }

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            listView.tag = positive
            view.findViewById<Button>(R.id.import_select_all).setOnClickListener {
                Arrays.fill(checked, true)
                repaintVisible()
                refreshHeader()
            }
            view.findViewById<Button>(R.id.import_select_none).setOnClickListener {
                Arrays.fill(checked, false)
                repaintVisible()
                refreshHeader()
            }
            positive.setOnClickListener {
                val picked = selectedEntries()
                if (picked.isEmpty()) {
                    ui.toast(context.getString(R.string.import_select_empty))
                } else {
                    dialog.dismiss()
                    val totalMessages = picked.sumOf { it.messageCount }
                    showSelectiveConfirm(result, picked.map { it.id }, picked.size, totalMessages)
                }
            }
            refreshHeader()
        }
        dialog.setOnCancelListener { cleanupTempFiles() }
        dialog.show()
    }

    /** 选择导入确认：强调「只带入选中的对话，但官方导入器会替换本机全部记录」。 */
    private fun showSelectiveConfirm(
        result: ArchiveScanner.ScanResult,
        ids: List<String>,
        selectedCount: Int,
        totalMessages: Long
    ) {
        if (!canShowUi()) {
            cleanupTempFiles()
            return
        }
        MaterialAlertDialogBuilder(dialogContext())
            .setTitle(R.string.import_confirm_title)
            .setMessage(
                context.getString(
                    R.string.import_selective_confirm_message,
                    selectedCount,
                    totalMessages
                )
            )
            .setPositiveButton(R.string.import_confirm_ok) { _, _ -> beginRebuild(result, ids) }
            .setNegativeButton(R.string.key_setup_cancel) { _, _ -> cleanupTempFiles() }
            .setNeutralButton(R.string.import_confirm_backup) { _, _ -> backupManager.showBackupGuide() }
            .show()
    }

    /** 后台按官方条目顺序重建过滤包，成功后走既有投递链路。 */
    private fun beginRebuild(result: ArchiveScanner.ScanResult, rawIds: List<String>) {
        val progress = showProgress(R.string.import_rebuild_progress)
        executor.execute {
            try {
                // 会话块顺序归一化为原索引顺序（官方导入器仅要求集合一致，
                // 但确定性顺序便于比对与排错）；索引过滤同样保持原顺序。
                val idSet = rawIds.toSet()
                val orderedIds = result.indexEntries.map { it.id }.filter { it in idSet }
                val filteredIndex = HistoryIndex.filterIndex(result.indexJsonBytes, orderedIds)
                val outFile = File.createTempFile("filtered_import_", ".zip", context.cacheDir)
                trackTemp(outFile)
                builder.build(result.sourceFile, orderedIds, filteredIndex, result.hasUserJson, outFile)
                main.post {
                    dismissDialog(progress)
                    startImport(Uri.fromFile(outFile), PARTIAL_EXPORT_NAME)
                }
            } catch (t: Throwable) {
                LogRedactor.w(TAG, "rebuild failed: ${t.javaClass.simpleName}")
                cleanupTempFiles()
                main.post {
                    dismissDialog(progress)
                    ui.toast(context.getString(R.string.import_rebuild_failed))
                }
            }
        }
    }

    // ---------- 整包导入（原有路径 / 选择对话框的「全部导入」） ----------

    private fun showFullImportConfirm(result: ArchiveScanner.ScanResult) {
        if (!canShowUi()) {
            cleanupTempFiles()
            return
        }
        val source = result.sourceFile
        val (displayName, sizeBytes) = describe(Uri.fromFile(source))
        val sizeText = humanSize(sizeBytes)
        MaterialAlertDialogBuilder(dialogContext())
            .setTitle(R.string.import_confirm_title)
            .setMessage(context.getString(R.string.import_confirm_message, displayName, sizeText))
            .setPositiveButton(R.string.import_confirm_ok) { _, _ ->
                startImport(Uri.fromFile(source), displayName)
            }
            .setNegativeButton(R.string.key_setup_cancel) { _, _ -> cleanupTempFiles() }
            .setNeutralButton(R.string.import_confirm_backup) { _, _ -> backupManager.showBackupGuide() }
            .show()
    }

    private fun startImport(uri: Uri, displayName: String) {
        val b = bridge
        if (b == null) {
            cleanupTempFiles()
            ui.toast(context.getString(R.string.import_page_not_ready))
            return
        }
        streamHandler.setPending(uri, displayName)
        val delivered = b.deliverImport(WebViewConfigHelper.IMPORT_STREAM_URL, displayName)
        if (delivered) {
            ui.toast(context.getString(R.string.import_delivered))
        } else {
            streamHandler.clear()
            cleanupTempFiles()
            ui.toast(context.getString(R.string.import_page_not_ready))
        }
    }

    // ---------- 临时文件与对话框工具 ----------

    private fun trackTemp(file: File) {
        synchronized(tempFiles) { tempFiles.add(file) }
    }

    private fun cleanupTempFiles() {
        val files: List<File>
        synchronized(tempFiles) {
            files = ArrayList(tempFiles)
            tempFiles.clear()
        }
        for (file in files) file.delete()
    }

    private fun canShowUi(): Boolean = !(activity.isFinishing || activity.isDestroyed)

    /**
     * 对话框专用上下文：applicationContext 不携带 AppCompat/Material3 主题，
     * MaterialAlertDialogBuilder 构造时会抛 "requires your app theme to be
     * Theme.AppCompat (or a descendant)"（0.1.4 线上崩溃）。显式包一层应用主题，
     * 保证任何调用点都拿到合法主题。
     */
    private fun dialogContext(): Context =
        android.view.ContextThemeWrapper(activity, R.style.Theme_DeepSeekAssistant)

    private fun showProgress(messageRes: Int): AlertDialog {
        val dctx = dialogContext()
        val pad = (dctx.resources.displayMetrics.density * 24).toInt()
        val box = LinearLayout(dctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        box.addView(TextView(dctx).apply {
            setText(messageRes)
        })
        box.addView(
            ProgressBar(dctx),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 }
        )
        val dialog = MaterialAlertDialogBuilder(dctx)
            .setView(box)
            .setCancelable(false)
            .create()
        if (canShowUi()) dialog.show()
        return dialog
    }

    private fun dismissDialog(dialog: AlertDialog?) {
        try {
            dialog?.dismiss()
        } catch (t: Throwable) {
            // 已被系统回收时忽略
        }
    }

    /** ISO 时间戳 → 本地展示（yyyy-MM-dd HH:mm）；解析失败时截断原文。 */
    private fun formatTimestamp(raw: String): String = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(raw.take(19)) ?: throw IllegalArgumentException(raw)
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(parsed)
    } catch (t: Throwable) {
        raw.take(16).replace('T', ' ')
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
        private const val TAG = "ImportController"
        private const val DEFAULT_NAME = "deepseek_data.zip"

        /** 重建包固定 ASCII 名（X-Import-Filename 头与页面侧展示均安全）。 */
        const val PARTIAL_EXPORT_NAME = "deepseek_partial_import.zip"
    }
}
