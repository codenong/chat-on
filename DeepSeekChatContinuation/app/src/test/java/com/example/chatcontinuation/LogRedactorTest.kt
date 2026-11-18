package com.example.chatcontinuation

import com.example.chatcontinuation.util.LogRedactor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设计稿第七节第 8 条：日志、埋点、崩溃报告中不得出现完整 Key。 */
class LogRedactorTest {

    @Test
    fun `redacts sk style keys`() {
        val message = "request failed with key sk-abc123XYZ456_-def and status 401"
        val redacted = LogRedactor.redact(message)
        assertFalse(redacted.contains("abc123XYZ456"))
        assertTrue(redacted.contains("sk-[REDACTED]"))
        assertTrue(redacted.contains("status 401"))
    }

    @Test
    fun `keeps normal text untouched`() {
        val message = "import finished: 12 sessions, 1 skipped"
        assertTrue(LogRedactor.redact(message) == message)
    }

    @Test
    fun `redacts keys embedded in json`() {
        val message = """{"apiKey":"sk-Ab12Cd34Ef56Gh78"}"""
        val redacted = LogRedactor.redact(message)
        assertFalse(redacted.contains("Ab12Cd34Ef56"))
    }
}
