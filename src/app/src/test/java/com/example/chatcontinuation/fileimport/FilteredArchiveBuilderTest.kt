package com.example.chatcontinuation.fileimport

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 过滤重建：验证条目顺序符合官方导入器状态机要求
 * （manifest 首位 → user.json → 会话 json+附件 → 过滤后索引），
 * 会话与附件逐字节一致，索引集合与所选会话完全一致。
 */
class FilteredArchiveBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeSource(): File {
        val index = JSONArray()
        for (spec in listOf(Triple("a", "Alpha", 3), Triple("b", "Beta", 5), Triple("c", "Gamma", 7))) {
            index.put(
                org.json.JSONObject()
                    .put("id", spec.first)
                    .put("title", spec.second)
                    .put("pinned", false)
                    .put("message_count", spec.third)
                    .put("inserted_at", "2026-01-01T00:00:00.000Z")
                    .put("updated_at", "2026-01-0${spec.third}T00:00:00.000Z")
            )
        }
        val f = tmp.newFile("source.zip")
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            fun entry(name: String, content: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
            entry("manifest.json", """{"export_format_version":3}""")
            entry("user.json", """{"id":"user-1"}""")
            entry("chat_sessions/a.json", """{"id":"a","messages":[]}""")
            entry("chat_sessions/a_files/1_image.webp", "WEBP-CONTENT-A")
            entry("chat_sessions/b.json", """{"id":"b","messages":[]}""")
            entry("chat_sessions/c.json", """{"id":"c","messages":[]}""")
            entry("chat_sessions/c_files/2_截圖 2026-10-31 09.13.webp", "WEBP-CONTENT-C-中文文件名")
            entry("chat_session_index.json", index.toString())
        }
        return f
    }

    private fun bytes(zip: ZipFile, name: String): ByteArray =
        zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }

    @Test
    fun build_selectedSubset_followsOfficialEntryOrder() {
        val source = makeSource()
        val scan = ArchiveScanner.scanFile(source)
        // 会话块顺序 = 调用方传入顺序（官方导入器仅要求索引集合与会话集合一致，
        // 不要求会话块顺序）；ImportController 编排层负责归一化为原索引顺序。
        val selected = listOf("c", "a")
        val filteredIndex = HistoryIndex.filterIndex(scan.indexJsonBytes, selected)
        val out = tmp.newFile("filtered.zip")
        FilteredArchiveBuilder().build(source, selected, filteredIndex, scan.hasUserJson, out)

        ZipFile(out).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(
                listOf(
                    "manifest.json",
                    "user.json",
                    "chat_sessions/c.json",
                    "chat_sessions/c_files/2_截圖 2026-10-31 09.13.webp",
                    "chat_sessions/a.json",
                    "chat_sessions/a_files/1_image.webp",
                    "chat_session_index.json"
                ),
                names
            )
            // 会话 json、附件（含中文文件名）、manifest 内容逐字节一致
            ZipFile(source).use { src ->
                assertArrayEquals(bytes(src, "chat_sessions/a.json"), bytes(zip, "chat_sessions/a.json"))
                assertArrayEquals(
                    bytes(src, "chat_sessions/c_files/2_截圖 2026-10-31 09.13.webp"),
                    bytes(zip, "chat_sessions/c_files/2_截圖 2026-10-31 09.13.webp")
                )
                assertArrayEquals(bytes(src, "manifest.json"), bytes(zip, "manifest.json"))
                assertArrayEquals(bytes(src, "user.json"), bytes(zip, "user.json"))
            }
            // 索引仅含所选 id（保持原顺序），条目对象原样保留
            val arr = JSONArray(bytes(zip, "chat_session_index.json").decodeToString())
            assertEquals(2, arr.length())
            assertEquals("a", arr.getJSONObject(0).getString("id"))
            assertEquals("c", arr.getJSONObject(1).getString("id"))
            assertEquals(7, arr.getJSONObject(1).getInt("message_count"))
        }
        // 重建包自身可通过扫描（manifest 首位、版本 3、索引可解析）
        val rescan = ArchiveScanner.scanFile(out)
        assertEquals(listOf("a", "c"), rescan.indexEntries.map { it.id })
        assertTrue(rescan.hasUserJson)
    }

    @Test
    fun build_missingSessionFile_failsAtomically() {
        val source = makeSource()
        val out = tmp.newFile("bad.zip")
        try {
            FilteredArchiveBuilder().build(source, listOf("a", "ghost"), ByteArray(0), true, out)
            fail("expected BuildException")
        } catch (e: BuildException) {
            // 校验在落盘前完成：失败的重建不应留下半成品内容
            assertTrue(out.length() <= 0L || !out.exists())
        }
    }

    @Test
    fun build_unsafeId_fails() {
        val source = makeSource()
        val out = tmp.newFile("bad2.zip")
        try {
            FilteredArchiveBuilder().build(source, listOf("../escape"), ByteArray(0), true, out)
            fail("expected BuildException")
        } catch (e: BuildException) {
            // ok
        }
    }
}
