package com.example.chatcontinuation.fileimport

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 过滤重建导出包（0.1.4 选择导入）。
 *
 * 官方导入器（bundle 模块 8444）对条目顺序有硬性要求：
 *   manifest.json（必须首位）→ user.json → 各会话（chat_sessions/<id>.json
 *   紧跟其 <id>_files/ 附件）→ chat_session_index.json（无进行中会话时）。
 * 且最终校验要求索引 id 集合与会话文件集合完全一致。
 * 会话 json 与附件逐字节复制（保证官方校验结果与原包一致），
 * 索引按所选会话过滤后重写。重建后的包对官方导入器等效于
 * 「只含所选对话的官方导出包」。
 */
class FilteredArchiveBuilder {

    /** 重建包含 selectedIds 的导出包到 outFile。任一所选会话缺文件则整体失败。 */
    fun build(
        source: File,
        selectedIds: Collection<String>,
        filteredIndexBytes: ByteArray,
        hasUserJson: Boolean,
        outFile: File
    ) {
        // 先整体校验再落盘，避免半成品文件
        ZipFile(source).use { zip ->
            val manifest = zip.getEntry(ArchiveScanner.MANIFEST_ENTRY)
                ?: throw BuildException("manifest.json missing in source")
            val user = if (hasUserJson) zip.getEntry(ArchiveScanner.USER_ENTRY) else null
            if (hasUserJson && user == null) throw BuildException("user.json missing in source")

            val sessionJson = HashMap<String, ZipEntry>()
            val filesBySession = LinkedHashMap<String, MutableList<ZipEntry>>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (name == ArchiveScanner.MANIFEST_ENTRY ||
                    name == ArchiveScanner.INDEX_ENTRY ||
                    name == ArchiveScanner.USER_ENTRY
                ) continue
                val sessionMatch = SESSION_JSON_REGEX.matchEntire(name)
                if (sessionMatch != null) {
                    sessionJson[sessionMatch.groupValues[1]] = entry
                    continue
                }
                val filesMatch = SESSION_FILES_REGEX.matchEntire(name)
                if (filesMatch != null) {
                    filesBySession.getOrPut(filesMatch.groupValues[1]) { ArrayList() }.add(entry)
                }
            }

            val ordered = selectedIds.toList()
            for (id in ordered) {
                if (!HistoryIndex.isSafeId(id)) throw BuildException("unsafe session id: $id")
                if (!sessionJson.containsKey(id)) {
                    throw BuildException("session json missing: chat_sessions/$id.json")
                }
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { out ->
                out.setLevel(Deflater.BEST_SPEED)
                // 1) manifest 必须是首个条目（官方 checkArchiveFormat / importArchive）
                out.putNextEntry(ZipEntry(ArchiveScanner.MANIFEST_ENTRY))
                out.write(read(zip, manifest))
                out.closeEntry()
                // 2) user.json（官方导入器允许在会话开始前出现，跳过内容）
                if (user != null) {
                    out.putNextEntry(ZipEntry(ArchiveScanner.USER_ENTRY))
                    out.write(read(zip, user))
                    out.closeEntry()
                }
                // 3) 各会话：json 在前、附件紧跟（与官方状态机一致）
                for (id in ordered) {
                    out.putNextEntry(ZipEntry("chat_sessions/$id.json"))
                    out.write(read(zip, sessionJson.getValue(id)))
                    out.closeEntry()
                    for (fileEntry in filesBySession[id].orEmpty()) {
                        out.putNextEntry(ZipEntry(fileEntry.name))
                        zip.getInputStream(fileEntry).use { it.copyTo(out, COPY_BUFFER) }
                        out.closeEntry()
                    }
                }
                // 4) 过滤后的索引（此时无进行中会话，位置合法）
                out.putNextEntry(ZipEntry(ArchiveScanner.INDEX_ENTRY))
                out.write(filteredIndexBytes)
                out.closeEntry()
            }
        }
    }

    private fun read(zip: ZipFile, entry: ZipEntry): ByteArray =
        zip.getInputStream(entry).use { it.readBytes() }

    companion object {
        /** 与官方 dN 正则一致：chat_sessions/<id>.json */
        internal val SESSION_JSON_REGEX = Regex("^chat_sessions/([^/]+)\\.json$")
        /** 与官方附件目录一致：chat_sessions/<id>_files/... */
        internal val SESSION_FILES_REGEX = Regex("^chat_sessions/([^/]+)_files/.+")
        private const val COPY_BUFFER = 64 shl 10
    }
}

/** 重建失败。 */
class BuildException(message: String) : IOException(message)
