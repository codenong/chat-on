package com.example.chatcontinuation.fileimport

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话索引解析与过滤（0.1.4 选择导入）。 */
class HistoryIndexTest {

    private fun entry(id: String, title: String?, count: Int, updated: String): JSONObject {
        val obj = JSONObject()
            .put("id", id)
            .put("pinned", false)
            .put("message_count", count)
            .put("inserted_at", "2026-01-01T00:00:00.000Z")
            .put("updated_at", updated)
        if (title != null) obj.put("title", title) else obj.put("title", JSONObject.NULL)
        return obj
    }

    private fun array(vararg entries: JSONObject): String =
        JSONArray().apply { entries.forEach { put(it) } }.toString()

    @Test
    fun parse_validArray_mapsEntries() {
        val json = array(
            entry("a", "Alpha", 3, "2026-11-01T10:00:00.000Z"),
            entry("b", null, 5, "2026-11-02T09:30:00.000Z")
        )
        val entries = HistoryIndex.parse(json.toByteArray())!!
        assertEquals(2, entries.size)
        assertEquals("a", entries[0].id)
        assertEquals("Alpha", entries[0].title)
        assertEquals(3L, entries[0].messageCount)
        assertNull(entries[1].title)
        assertEquals(5L, entries[1].messageCount)
    }

    @Test
    fun parse_malformed_returnsNull() {
        assertNull(HistoryIndex.parse("not json".toByteArray()))
        // 根不是数组（官方导入器同样拒绝）
        assertNull(HistoryIndex.parse("""{"id":"a"}""".toByteArray()))
        // 空数组视为无有效索引
        assertNull(HistoryIndex.parse("[]".toByteArray()))
    }

    @Test
    fun parse_skipsUnsafeAndDuplicateIds() {
        val json = array(
            entry("ok", "A", 1, "2026-11-01T00:00:00.000Z"),
            entry("a/b", "path", 1, "2026-11-01T00:00:00.000Z"),
            entry("..", "dotdot", 1, "2026-11-01T00:00:00.000Z"),
            entry("ok", "dup", 2, "2026-11-01T00:00:00.000Z"),
            JSONObject().put("no_id", true)
        )
        val entries = HistoryIndex.parse(json.toByteArray())!!
        assertEquals(1, entries.size)
        assertEquals("ok", entries[0].id)
        assertEquals("A", entries[0].title)
    }

    @Test
    fun isSafeId_rejectsTraversal() {
        assertTrue(HistoryIndex.isSafeId("01858ac9-235f-432c-b0f4-b40b2fcaca5d"))
        assertFalse(HistoryIndex.isSafeId(""))
        assertFalse(HistoryIndex.isSafeId("."))
        assertFalse(HistoryIndex.isSafeId(".."))
        assertFalse(HistoryIndex.isSafeId("a/b"))
        assertFalse(HistoryIndex.isSafeId("a\\b"))
    }

    @Test
    fun filterIndex_keepsSelectedInOriginalOrder() {
        val json = array(
            entry("a", "A", 1, "2026-01-03T00:00:00.000Z"),
            entry("b", "B", 2, "2026-01-02T00:00:00.000Z"),
            entry("c", "C", 3, "2026-01-01T00:00:00.000Z")
        )
        val bytes = HistoryIndex.filterIndex(json.toByteArray(), listOf("c", "a"))
        val arr = JSONArray(String(bytes, Charsets.UTF_8))
        assertEquals(2, arr.length())
        // 保持原索引顺序（a 在 c 前），条目对象原样保留
        assertEquals("a", arr.getJSONObject(0).getString("id"))
        assertEquals("c", arr.getJSONObject(1).getString("id"))
        assertEquals("A", arr.getJSONObject(0).getString("title"))
        assertEquals(3, arr.getJSONObject(1).getInt("message_count"))
    }
}
