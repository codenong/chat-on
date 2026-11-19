package com.example.chatcontinuation.fileimport

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.TextView
import android.widget.ListView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import com.example.chatcontinuation.R
import com.example.chatcontinuation.backup.BackupManager
import com.example.chatcontinuation.bridge.AssistantBridgeListener
import com.example.chatcontinuation.ui.ShellUi
import com.example.chatcontinuation.ui.WebViewConfigHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 导入编排（0.2.1：选择导入 × 追加导入）：
 * 系统选择器选 ZIP → 原生扫描官方导出包索引（[ArchiveScanner]，只读 manifest
 * 与会话索引，不解压会话）→ 弹出会话勾选对话框（全选/清空/计数；「全部导入」
 * 整包直走；「导入所选」按所选会话过滤重建 ZIP——[FilteredArchiveBuilder] 保证
 * 官方条目顺序与「索引 id 集合 == 会话文件集合」校验全部成立）→ 通知页面走
 * appendImport：页面内直接调用官方解析器（importArchive → IndexedDB 暂存区）与
 * 官方常规写库函数（E1 按会话 upsert）把所选内容**追加**进现网库，跳过官方
 * 「清空现网库再搬入暂存」的提交步骤（Xz）——现有对话全部保留，同 id 会话按
 * 导入侧覆盖。
 *
 * 兜底：官方页面更新导致模块指纹失效（MODULE_NOT_FOUND）时，回退 0.1.x
 * 旧链路——投递给官方页面导入入口，由官方覆盖式导入（页面内自行确认）；
 * 此时投递的是用户勾选后重建的包，语义一致。
 *
 * 线程约定：扫描与重建在工作线程，对话框与状态（[importing]/[tempFiles]）
 * 只在主线程读写；[AssistantBridgeListener] 的回调也在主线程。
 */
class ImportController(
    private val activity: ComponentActivity,
    private val streamHandler: ImportStreamPathHandler,
    @Suppress("unused") private val backupManager: BackupManager, // 旧链路保留注入
    private val ui: ShellUi
) {

    /**
     * 非 UI 用途的 Context（contentResolver/cacheDir/getString），即 [activity] 本身。
     * 对话框与布局填充必须直接用 [activity]：Material 组件要求 Activity 主题
     * （ThemeEnforcement 崩溃防护，见 scripts/check_ui_context.sh 审计）。
     */
    private val context: Context get() = activity

    /** 由 MainActivity 在桥接构造完成后注入。 */
    var bridge: AssistantBridgeListener? = null

    private val main = Handler(Looper.getMainLooper())

    /** 导入进行中：防重入 + 超时兜底判定。 */
    private val importing = AtomicBoolean(false)

    /** 本次导入产生的缓存文件（原包副本、过滤重建包），终结后统一删除。仅主线程读写。 */
    private val tempFiles = ArrayList<File>()

    private val scanner = ArchiveScanner(context)
    private val builder = FilteredArchiveBuilder()

    private val timeoutRunnable = Runnable {
        if (importing.getAndSet(false)) {
            streamHandler.clear()
            deleteTempFiles()
            ui.toast(context.getString(R.string.import_append_timeout))
        }
    }

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

    /** 页面侧导入流程终结（成功或最终失败）后，释放暂存流与缓存文件并复位状态。 */
    fun clearPendingStream() {
        main.removeCallbacks(timeoutRunnable)
        importing.set(false)
        streamHandler.clear()
        deleteTempFiles()
    }

    // ---------- 选择导入（0.1.4 引入，0.2.1 接入追加链路） ----------

    private fun onPicked(uri: Uri) {
        if (!importing.compareAndSet(false, true)) {
            ui.toast(context.getString(R.string.import_append_busy))
            return
        }
        val displayName = describeName(uri)
        ui.toast(context.getString(R.string.import_scan_progress))
        Thread {
            val scan = try {
                scanner.scan(uri)
            } catch (t: Throwable) {
                main.post {
                    importing.set(false)
                    ui.toast(context.getString(R.string.import_scan_failed))
                }
                return@Thread
            }
            main.post {
                tempFiles.add(scan.sourceFile)
                showSelectDialog(scan, displayName)
            }
        }.start()
    }

    private fun showSelectDialog(scan: ArchiveScanner.ScanResult, displayName: String) {
        val entries = scan.indexEntries
        // 0.2.3 根治误导入事故：默认不勾选（点谁选谁）。此前默认全选 + 点击反选，
        // 用户「点选一个」的直觉操作实际产生 N-1 勾选，点「导入所选」后整包导入。
        val checked = BooleanArray(entries.size)

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_import_select, null)
        val listView = view.findViewById<ListView>(android.R.id.list)
        val countView = view.findViewById<TextView>(R.id.import_select_count)

        // 主按钮引用在 dialog.show() 后才有；闭包经此 var 实时联动
        var positiveButton: TextView? = null

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = entries.size
            override fun getItem(position: Int): HistoryIndexEntry = entries[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val root = convertView ?: LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_conversation_select, parent, false)
                val entry = entries[position]
                root.findViewById<TextView>(R.id.import_item_title).text =
                    entry.title ?: context.getString(R.string.import_select_untitled)
                root.findViewById<TextView>(R.id.import_item_subtitle).text = subtitle(entry)
                // 0.2.4 勾选视觉三层兜底：① 子 CheckBox 直接按模型赋值（不依赖
                // CheckableLinearLayout 内部同步链）；② 行容器 Checkable 状态照旧；
                // ③ 点击时立即同步本行视觉（见下方点击监听），不等重排。
                root.findViewById<CheckBox>(R.id.import_item_check).isChecked = checked[position]
                (root as CheckableLinearLayout).isChecked = checked[position]
                // 点击挂行自身（0.2.3）：不依赖 ListView 点击派发，杜绝
                // 「行可点击但 onItemClickListener 收不到」一类变体；复用时按新 position 重挂
                root.setOnClickListener {
                    checked[position] = !checked[position]
                    // 立即反馈本行（0.2.4）：真机上 notify 后的重绑存在视觉滞后，
                    // 直接赋值保证点击瞬间勾选框就切换，重排后按模型再校准
                    (root as CheckableLinearLayout).isChecked = checked[position]
                    notifyDataSetChanged()
                    refreshCount(countView, checked, entries.size, positiveButton)
                }
                return root
            }
        }
        listView.adapter = adapter

        fun refreshAll() {
            adapter.notifyDataSetChanged()
            refreshCount(countView, checked, entries.size, positiveButton)
        }

        view.findViewById<View>(R.id.import_select_all).setOnClickListener {
            checked.fill(true)
            refreshAll()
        }
        view.findViewById<View>(R.id.import_select_none).setOnClickListener {
            checked.fill(false)
            refreshAll()
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.import_select_title)
            .setView(view)
            .setPositiveButton(R.string.import_select_ok, null)
            .setNegativeButton(R.string.key_setup_cancel) { _, _ -> cancelSelection() }
            .setNeutralButton(R.string.import_select_full, null)
            .create()
        dialog.setOnCancelListener { cancelSelection() }
        dialog.show()

        positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        // 勾选为空时按钮已禁用（refreshCount 联动）；toast 兜底语义保持
        positiveButton.setOnClickListener {
            val ids = selectedIds(entries, checked)
            if (ids.isEmpty()) {
                ui.toast(context.getString(R.string.import_select_empty))
                return@setOnClickListener
            }
            dialog.dismiss()
            if (ids.size == entries.size) {
                // 全选与整包内容一致，跳过重建直接追加
                startAppend(scan.sourceFile, displayName)
            } else {
                rebuildAndAppend(scan, ids, displayName)
            }
        }
        // 整包追加：不重建，等价 0.2.0 行为（按钮文案「全部导入」，语义明确）
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss()
            startAppend(scan.sourceFile, displayName)
        }
        refreshCount(countView, checked, entries.size, positiveButton)
    }

    /** 主线程：取消选择对话框，清理缓存并复位（幂等）。 */
    private fun cancelSelection() {
        main.removeCallbacks(timeoutRunnable)
        importing.set(false)
        streamHandler.clear()
        deleteTempFiles()
        ui.toast(context.getString(R.string.import_cancelled))
    }

    /** 按所选会话过滤重建导出包（工作线程），成功后进入追加导入。 */
    private fun rebuildAndAppend(
        scan: ArchiveScanner.ScanResult,
        ids: List<String>,
        displayName: String
    ) {
        ui.toast(context.getString(R.string.import_rebuild_progress))
        Thread {
            try {
                val filteredIndex = HistoryIndex.filterIndex(scan.indexJsonBytes, ids)
                val outFile = File.createTempFile("filtered_import_", ".zip", context.cacheDir)
                builder.build(scan.sourceFile, ids, filteredIndex, scan.hasUserJson, outFile)
                main.post {
                    tempFiles.add(outFile)
                    startAppend(outFile, displayName)
                }
            } catch (t: Throwable) {
                main.post {
                    ui.toast(context.getString(R.string.import_rebuild_failed))
                    importing.set(false)
                    deleteTempFiles()
                }
            }
        }.start()
    }

    // ---------- 追加导入（0.2.0） ----------

    /** 主线程：挂流并通知页面执行追加导入；超时兜底见 [timeoutRunnable]。 */
    private fun startAppend(file: File, displayName: String) {
        streamHandler.setPending(Uri.fromFile(file), displayName)
        // 0.2.3：URL 附时间戳查询参数，杜绝 WebView HTTP 缓存命中上一次导入的旧包
        // （服务端固定路径 current.zip；WebViewAssetLoader 仅按路径匹配，查询串安全）
        val url = WebViewConfigHelper.IMPORT_STREAM_URL + "?t=" + System.currentTimeMillis()
        val delivered = bridge?.appendImport(url, displayName) == true
        if (!delivered) {
            clearPendingStream()
            ui.toast(context.getString(R.string.import_page_not_ready))
            return
        }
        ui.toast(context.getString(R.string.import_append_start))
        main.postDelayed(timeoutRunnable, APPEND_TIMEOUT_MS)
    }

    /**
     * 页面模块指纹失效（MODULE_NOT_FOUND）时的兜底：走 0.1.x 旧链路——
     * 当前暂存文件（可能是过滤重建包）投递给官方页面导入入口，官方覆盖式
     * 导入（页面内确认）。
     */
    fun fallbackToLegacyImport() {
        val b = bridge
        val name = streamHandler.pendingName()
        main.removeCallbacks(timeoutRunnable)
        importing.set(false)
        if (b == null || name == null) {
            clearPendingStream()
            ui.toast(context.getString(R.string.import_page_not_ready))
            return
        }
        val delivered = b.deliverImport(
            WebViewConfigHelper.IMPORT_STREAM_URL + "?t=" + System.currentTimeMillis(), name
        )
        if (delivered) {
            ui.toast(context.getString(R.string.import_delivered))
        } else {
            clearPendingStream()
            ui.toast(context.getString(R.string.import_page_not_ready))
        }
    }

    // ---------- 展示细节 ----------

    /** 列表副标题：消息数 + 更新时间（时间解析失败时原样展示）。 */
    private fun subtitle(entry: HistoryIndexEntry): String {
        val formatted = formatUpdatedAt(entry.updatedAt)
        return if (formatted.isBlank()) {
            context.getString(R.string.import_item_subtitle_no_time, entry.messageCount)
        } else {
            context.getString(R.string.import_item_subtitle_fmt, entry.messageCount, formatted)
        }
    }

    /** 官方 updated_at 为 UTC ISO-8601；转本地简洁格式，失败回退原串。 */
    private fun formatUpdatedAt(raw: String): String = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(raw) ?: return raw
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(parsed)
    } catch (t: Throwable) {
        raw
    }

    /** 计数联动：计数文本 + 主按钮「导入所选(N)」实时反映将导入数；N=0 时禁用按钮（防误点）。 */
    private fun refreshCount(
        countView: TextView,
        checked: BooleanArray,
        total: Int,
        positiveButton: TextView?
    ) {
        val n = checked.count { it }
        countView.text = context.getString(R.string.import_select_count_fmt, n, total)
        positiveButton?.text = context.getString(R.string.import_select_ok_fmt, n)
        positiveButton?.isEnabled = n > 0
    }

    private fun selectedIds(entries: List<HistoryIndexEntry>, checked: BooleanArray): List<String> =
        entries.mapIndexedNotNull { index, entry -> if (checked[index]) entry.id else null }

    private fun deleteTempFiles() {
        for (file in tempFiles) file.delete()
        tempFiles.clear()
    }

    /** 读取所选文件的展示名；读不到时用默认名（不阻断流程）。 */
    private fun describeName(uri: Uri): String = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            } ?: DEFAULT_NAME
    } catch (t: Throwable) {
        DEFAULT_NAME
    }

    companion object {
        /** 追加导入结果等待上限：官方解析大档案可能较慢，放宽到 5 分钟。 */
        private const val APPEND_TIMEOUT_MS = 5 * 60 * 1000L
        private const val DEFAULT_NAME = "deepseek_data.zip"
    }
}
