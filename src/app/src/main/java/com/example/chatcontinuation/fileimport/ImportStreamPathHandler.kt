package com.example.chatcontinuation.fileimport

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.example.chatcontinuation.util.LogRedactor
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Import Adapter 的流式通道（设计稿 6.1 Import Adapter / 阶段 2）。
 *
 * 用户经 SAF 选中的 ZIP 不做 Base64 桥接，而是挂到
 * https://appassets.androidplatform.net/__import_local__/current.zip，
 * 由页面 fetch 时经 ContentResolver 流式读取；Chromium 网络栈自行分块，
 * 大文件（数百 MB）不会整体进内存，也不阻塞 UI。
 */
class ImportStreamPathHandler(private val context: Context) : WebViewAssetLoader.PathHandler {

    private class PendingImport(val uri: Uri, val displayName: String)

    private val pending = AtomicReference<PendingImport?>()

    /** 记录当前待导入文件；投递前调用。 */
    fun setPending(uri: Uri, displayName: String) {
        pending.set(PendingImport(uri, displayName))
    }

    fun clear() {
        pending.set(null)
    }

    override fun handle(path: String): WebResourceResponse? {
        if (path != SERVED_PATH) return notFound()
        val imp = pending.get() ?: return notFound()
        return try {
            val pfd = context.contentResolver.openFileDescriptor(imp.uri, "r") ?: return notFound()
            val size = pfd.statSize
            val headers = mapOf(
                "Content-Length" to size.toString(),
                "Cache-Control" to "no-store",
                "X-Import-Filename" to sanitizeHeader(imp.displayName)
            )
            WebResourceResponse(
                "application/zip",
                null,
                200,
                "OK",
                headers,
                ParcelFileDescriptor.AutoCloseInputStream(pfd)
            )
        } catch (t: Throwable) {
            LogRedactor.w(TAG, "import stream failed: ${t.javaClass.simpleName}")
            notFound()
        }
    }

    private fun notFound(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun sanitizeHeader(name: String): String =
        name.replace(Regex("""[^\x20-\x7E]"""), "_").replace("\"", "'")

    companion object {
        private const val TAG = "ImportStream"
        const val SERVED_PATH = "current.zip"
    }
}
