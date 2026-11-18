package com.example.chatcontinuation.fileimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出包扫描：manifest 首位 + 版本 3 + 索引解析。 */
class ArchiveScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipEntries(out: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
    }

    private fun validSource(): File {
        val f = tmp.newFile("export.zip")
        val index = org.json.JSONArray().put(
            org.json.JSONObject()
                .put("id", "a")
                .put("title", "Alpha")
                .put("pinned", false)
                .put("message_count", 3)
                .put("inserted_at", "2026-01-01T00:00:00.000Z")
                .put("updated_at", "2026-01-02T00:00:00.000Z")
        )
        zipEntries(
            f,
            "manifest.json" to """{"export_format_version":3}""",
            "user.json" to """{"id":"u1"}""",
            "chat_sessions/a.json" to """{"id":"a","messages":[]}""",
            "chat_session_index.json" to index.toString()
        )
        return f
    }

    @Test
    fun scanFile_valid_returnsIndexAndUserFlag() {
        val result = ArchiveScanner.scanFile(validSource())
        assertEquals(1, result.indexEntries.size)
        assertEquals("a", result.indexEntries[0].id)
        assertEquals("Alpha", result.indexEntries[0].title)
        assertEquals(3L, result.indexEntries[0].messageCount)
        assertTrue(result.hasUserJson)
        assertTrue(result.indexJsonBytes.decodeToString().contains("Alpha"))
    }

    @Test(expected = ScanException::class)
    fun scanFile_manifestNotFirst_fails() {
        val f = tmp.newFile("bad1.zip")
        zipEntries(
            f,
            "chat_sessions/a.json" to "{}",
            "manifest.json" to """{"export_format_version":3}"""
        )
        ArchiveScanner.scanFile(f)
    }

    @Test(expected = ScanException::class)
    fun scanFile_wrongVersion_fails() {
        val f = tmp.newFile("bad2.zip")
        zipEntries(f, "manifest.json" to """{"export_format_version":4}""")
        ArchiveScanner.scanFile(f)
    }

    @Test(expected = ScanException::class)
    fun scanFile_missingIndex_fails() {
        val f = tmp.newFile("bad3.zip")
        zipEntries(
            f,
            "manifest.json" to """{"export_format_version":3}""",
            "user.json" to "{}"
        )
        ArchiveScanner.scanFile(f)
    }

    @Test
    fun scanFile_noUserJson_flagFalse() {
        val f = tmp.newFile("export2.zip")
        val index = org.json.JSONArray().put(
            org.json.JSONObject()
                .put("id", "a")
                .put("message_count", 0)
                .put("updated_at", "2026-01-01T00:00:00.000Z")
        )
        zipEntries(
            f,
            "manifest.json" to """{"export_format_version":3}""",
            "chat_sessions/a.json" to "{}",
            "chat_session_index.json" to index.toString()
        )
        val result = ArchiveScanner.scanFile(f)
        assertFalse(result.hasUserJson)
    }
}
