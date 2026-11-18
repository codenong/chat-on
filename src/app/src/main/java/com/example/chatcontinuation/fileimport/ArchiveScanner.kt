package com.example.chatcontinuation.fileimport

import android.content.Context
import android.net.Uri
import com.example.chatcontinuation.util.LogRedactor
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 官方导出包扫描（0.1.4 选择导入）：
 * 把 SAF 选中的 zip 复制到 cacheDir（ZipFile 需要真实文件做随机访问），
 * 然后只读取 manifest.json 与 chat_session_index.json 做校验与解析，
 * 不解压会话与附件。失败抛 ScanException，调用方回退整包导入。
 *
 * 校验规则来自官方导入器（bundle 模块 8444）：
 * manifest.json 必须是首个条目且 export_format_version === 3。
 */
class ArchiveScanner(private val context: Context) {

    class ScanResult(
        /** cacheDir 中的副本，供重建与整包导入挂流使用，随导入流程清理。 */
        val sourceFile: File,
        val indexEntries: List<HistoryIndexEntry>,
        /** 原始索引字节，过滤重建时用。 */
        val indexJsonBytes: ByteArray,
        val hasUserJson: Boolean
    )

    /** 复制到缓存并扫描。 */
    fun scan(uri: Uri): ScanResult {
        val copied = copyToCache(uri)
        return try {
            scanFile(copied)
        } catch (t: Throwable) {
            copied.delete()
            throw t
        }
    }

    /** 复制到 cacheDir；空流视为无效导出包。 */
    private fun copyToCache(uri: Uri): File {
        val out = File.createTempFile("picked_import_", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output ->
                    input.copyTo(output, COPY_BUFFER)
                }
            } ?: throw ScanException("cannot open picked file")
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        if (out.length() == 0L) {
            out.delete()
            throw ScanException("picked file is empty")
        }
        return out
    }

    companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val USER_ENTRY = "user.json"
        const val INDEX_ENTRY = "chat_session_index.json"
        private const val COPY_BUFFER = 1 shl 20

        /** 对真实文件扫描（随机访问，只读 manifest 与索引；可单测）。 */
        fun scanFile(source: File): ScanResult {
            ZipFile(source).use { zip ->
                val first: ZipEntry = zip.entries().nextElement()
                if (first.name != MANIFEST_ENTRY) {
                    throw ScanException("first entry is not manifest.json: ${first.name}")
                }
                val manifest = try {
                    JSONObject(String(readEntry(zip, first), Charsets.UTF_8))
                } catch (t: Throwable) {
                    throw ScanException("manifest.json is not valid JSON")
                }
                val version = manifest.optInt("export_format_version", -1)
                if (version != 3) throw ScanException("unsupported export_format_version=$version")

                val indexEntry = zip.getEntry(INDEX_ENTRY)
                    ?: throw ScanException("missing $INDEX_ENTRY")
                val indexBytes = readEntry(zip, indexEntry)
                val entries = HistoryIndex.parse(indexBytes)
                    ?: throw ScanException("chat_session_index.json parse failed")
                return ScanResult(
                    sourceFile = source,
                    indexEntries = entries,
                    indexJsonBytes = indexBytes,
                    hasUserJson = zip.getEntry(USER_ENTRY) != null
                )
            }
        }

        private fun readEntry(zip: ZipFile, entry: ZipEntry): ByteArray =
            zip.getInputStream(entry).use { it.readBytes() }
    }
}

/** 扫描失败：不是可解析的官方导出包。 */
class ScanException(message: String) : IOException(message) {
    init {
        LogRedactor.i("ArchiveScan", "scan failed: $message")
    }
}
