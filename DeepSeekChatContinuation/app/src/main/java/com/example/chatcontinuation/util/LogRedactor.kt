package com.example.chatcontinuation.util

/**
 * 日志脱敏（设计稿第七节第 8 条）：
 * 任何写日志、埋点、崩溃上报的调用，其内容必须先经过这里。
 * 上游 chat-demo 不感知本类；壳层自己的日志一律使用 redact()。
 */
object LogRedactor {

    /** DeepSeek API Key 形如 sk-xxxx；按前缀匹配做兜底脱敏，宁可误杀不放过。 */
    private val KEY_LIKE = Regex("""sk-[A-Za-z0-9_\-]{6,}""")

    fun redact(message: String): String = KEY_LIKE.replace(message, "sk-[REDACTED]")

    /** 包装 android.util.Log 的 w 级别。 */
    fun w(tag: String, message: String) {
        android.util.Log.w(tag.take(23), redact(message))
    }

    /** 包装 android.util.Log 的 i 级别。 */
    fun i(tag: String, message: String) {
        android.util.Log.i(tag.take(23), redact(message))
    }
}
