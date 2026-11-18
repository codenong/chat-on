package com.example.chatcontinuation.fileimport

import org.json.JSONArray

/**
 * 官方导出包 chat_session_index.json 的一条会话摘要。
 *
 * schema 来自官方导入器（bundle 模块 8444）：根为数组，元素含
 * id/title/pinned/message_count/inserted_at/updated_at；
 * 最终校验要求「索引 id 集合 == 实际导入的会话文件集合」完全一致。
 */
data class HistoryIndexEntry(
    val id: String,
    val title: String?,
    val messageCount: Long,
    val updatedAt: String,
    /** 过滤重建时原样回写的原始索引条目。 */
    val source: org.json.JSONObject
)

object HistoryIndex {

    /**
     * 解析官方导出的会话索引。解析失败返回 null（调用方回退到整包导入）；
     * 个别损坏条目跳过；重复 id 只保留首个（官方导入器会拒绝重复 id）。
     */
    fun parse(bytes: ByteArray): List<HistoryIndexEntry>? = try {
        val root = JSONArray(String(bytes, Charsets.UTF_8))
        val out = ArrayList<HistoryIndexEntry>(root.length())
        val seen = HashSet<String>()
        for (i in 0 until root.length()) {
            val obj = root.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (!isSafeId(id) || !seen.add(id)) continue
            out += HistoryIndexEntry(
                id = id,
                title = if (obj.isNull("title")) null
                else obj.optString("title").takeIf { it.isNotEmpty() },
                messageCount = obj.optLong("message_count", 0L),
                updatedAt = obj.optString("updated_at"),
                source = obj
            )
        }
        if (out.isEmpty()) null else out
    } catch (t: Throwable) {
        null
    }

    /**
     * 生成过滤后的索引字节（仅保留所选会话，条目对象原样保留，
     * 顺序与原索引一致，满足官方导入器的集合一致性校验）。
     */
    fun filterIndex(rawBytes: ByteArray, selectedIds: Collection<String>): ByteArray {
        val selected = selectedIds.toSet()
        val root = JSONArray(String(rawBytes, Charsets.UTF_8))
        val out = org.json.JSONArray()
        for (i in 0 until root.length()) {
            val obj = root.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (isSafeId(id) && id in selected) out.put(obj)
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** 与官方导入器一致的 id 安全性：不得含路径分隔符，不得为 . / ..。 */
    fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id != "." && id != ".." &&
            !id.contains('/') && !id.contains('\\')
}
