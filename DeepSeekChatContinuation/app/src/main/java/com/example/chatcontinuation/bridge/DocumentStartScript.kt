package com.example.chatcontinuation.bridge

import android.content.Context
import com.example.chatcontinuation.key.SecureKeyStore
import org.json.JSONObject

/**
 * 组装 document-start 注入脚本（assets/inject/）：
 * 1. upstream-config.js —— 适配契约（localStorage 键名、导入入口选择器等）；
 * 2. 启动期 Key 字面量 —— 把当前 Key 直接注入 JS 内存变量，
 *    保证上游首次同步读取（localStorage.getItem）即可命中，避免异步竞态；
 *    Key 只存在于页面内存，不落 localStorage（设计稿 6.2 / 七）。
 * 3. native-bridge.js —— Storage 垫片、导入投递、导出分块传输。
 *
 * Key 以 JSON 转义后嵌入脚本字符串；脚本不写日志。
 */
object DocumentStartScript {

    fun build(context: Context, keyStore: SecureKeyStore): String {
        val config = readAsset(context, "inject/upstream-config.js")
        val bridge = readAsset(context, "inject/native-bridge.js")
        val bootKey = keyStore.getKey() ?: ""
        val boot = "window.__ASSISTANT_BOOT_KEY__ = ${JSONObject.quote(bootKey)};"
        return "$config\n$boot\n$bridge\n"
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
}
