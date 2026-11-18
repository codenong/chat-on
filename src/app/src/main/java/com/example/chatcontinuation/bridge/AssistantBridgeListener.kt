package com.example.chatcontinuation.bridge

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.example.chatcontinuation.R
import com.example.chatcontinuation.backup.BackupManager
import com.example.chatcontinuation.fileimport.ImportController
import com.example.chatcontinuation.key.SecureKeyStore
import com.example.chatcontinuation.ui.ShellUi
import com.example.chatcontinuation.ui.WebViewConfigHelper
import com.example.chatcontinuation.util.LogRedactor
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * 受限桥接（设计稿 6.1 / 七）：
 * - 以 origin-scoped WebMessageListener 注入，仅可信 origin、仅主框架可调用；
 *   不使用宽泛的 addJavascriptInterface。
 * - 原生→页面只发两种消息：keyValue（Key 值或空串）、deliverImport（投递导入文件）。
 * - 页面→原生的消息见 assets/inject/native-bridge.js 顶部注释。
 * - 所有回复文本均为 JSON；日志不含 Key 与聊天正文。
 */
class AssistantBridgeListener(
    private val context: Context,
    private val keyStore: SecureKeyStore,
    private val importController: ImportController,
    private val backupManager: BackupManager,
    private val ui: ShellUi
) : WebViewCompat.WebMessageListener {

    private val replyProxyRef = AtomicReference<JavaScriptReplyProxy?>()

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        if (sourceOrigin.toString() != WebViewConfigHelper.TRUSTED_ORIGIN || !isMainFrame) return
        replyProxyRef.set(replyProxy)
        val data = message.data ?: return
        val json = try {
            JSONObject(data)
        } catch (e: JSONException) {
            LogRedactor.w(TAG, "bridge: malformed message ignored")
            return
        }
        when (json.optString("type")) {
            MSG_READY -> LogRedactor.i(TAG, "bridge: page ready")

            MSG_GET_KEY -> sendKey()

            MSG_SAVE_KEY -> {
                val key = json.optString("key")
                if (key.isNotEmpty()) {
                    keyStore.saveKey(key)
                    ui.onKeyChanged()
                    sendKey()
                }
            }

            MSG_DELETE_KEY -> {
                keyStore.deleteKey()
                ui.onKeyChanged()
                sendKey()
            }

            MSG_PICK_IMPORT -> importController.launchImport()

            MSG_EXPORT_START -> backupManager.onExportStart(json)
            MSG_EXPORT_CHUNK -> backupManager.onExportChunk(json)
            MSG_EXPORT_END -> backupManager.onExportEnd(json)
            MSG_EXPORT_ERROR -> backupManager.onExportError(json)

            MSG_IMPORT_RESULT -> onImportResult(json)

            else -> LogRedactor.i(TAG, "bridge: unknown message type ignored")
        }
    }

    /**
     * 页面导入结果（0.1.3 协议）：
     * - ok=true：官方导入器已接管并在页面内展示状态，原生不重复提示；
     * - NO_INPUT_YET：入口未挂载，文件已暂存、页面在轮询等待 → 弹完整指引对话框；
     * - 其余为最终失败：释放暂存流并提示。
     */
    private fun onImportResult(json: JSONObject) {
        if (json.optBoolean("ok")) {
            importController.clearPendingStream()
            return
        }
        when (json.optString("code")) {
            CODE_NO_INPUT_YET -> ui.showImportEntryGuide()

            CODE_INPUT_TIMEOUT -> {
                importController.clearPendingStream()
                ui.toast(context.getString(R.string.import_entry_timeout))
            }

            CODE_NO_FILE_INPUT -> {
                importController.clearPendingStream()
                ui.toast(context.getString(R.string.import_failed_no_input))
            }

            else -> {
                importController.clearPendingStream()
                ui.toast(context.getString(R.string.import_failed_fetch))
            }
        }
    }

    /** 把当前 Key（或空串）推给页面；页面据此维护内存副本，不落 localStorage。 */
    fun sendKey() {
        val key = keyStore.getKey()
        post(
            JSONObject().apply {
                put("type", MSG_KEY_VALUE)
                put("value", key ?: "")
            }.toString()
        )
    }

    /**
     * 通知页面把已选择的文件投递给官方导入器。
     * @return false 表示页面尚未就绪（无可用 reply 通道），调用方应回滚。
     */
    fun deliverImport(url: String, fileName: String): Boolean =
        postIfReady(
            JSONObject().apply {
                put("type", MSG_DELIVER_IMPORT)
                put("url", url)
                put("name", fileName)
            }.toString()
        )

    private fun postIfReady(payload: String): Boolean {
        val proxy = replyProxyRef.get() ?: return false
        return try {
            proxy.postMessage(payload)
            true
        } catch (t: Throwable) {
            LogRedactor.i(TAG, "bridge: post failed (page may have reloaded)")
            false
        }
    }

    private fun post(payload: String) {
        postIfReady(payload)
    }

    companion object {
        private const val TAG = "AssistantBridge"

        const val MSG_READY = "ready"
        const val MSG_GET_KEY = "getKey"
        const val MSG_SAVE_KEY = "saveKey"
        const val MSG_DELETE_KEY = "deleteKey"
        const val MSG_PICK_IMPORT = "pickImport"
        const val MSG_KEY_VALUE = "keyValue"
        const val MSG_DELIVER_IMPORT = "deliverImport"
        const val MSG_EXPORT_START = "exportStart"
        const val MSG_EXPORT_CHUNK = "exportChunk"
        const val MSG_EXPORT_END = "exportEnd"
        const val MSG_EXPORT_ERROR = "exportError"
        const val MSG_IMPORT_RESULT = "importResult"

        const val CODE_NO_INPUT_YET = "NO_INPUT_YET"
        const val CODE_INPUT_TIMEOUT = "INPUT_TIMEOUT"
        const val CODE_NO_FILE_INPUT = "NO_FILE_INPUT"
    }
}
