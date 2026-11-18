package com.example.chatcontinuation.ui

/**
 * 外壳 UI 契约：供桥接、导入、备份等组件在不持有 Activity 的情况下
 * 请求界面能力。MainActivity 实现本接口。
 */
interface ShellUi {

    /** 显示短提示；实现需保证在主线程执行。 */
    fun toast(message: String)

    /** 在 WebView 中打开内置帮助页（可信 origin 下的 assets 页面）。 */
    fun openHelpPage(page: HelpPage)

    /** 打开 API Key 设置对话框。 */
    fun showKeySetupDialog()

    /** 打开备份与恢复说明对话框。 */
    fun showBackupGuide()

    /** 打开“关于”对话框。 */
    fun showAbout()

    /** Key 状态变化（保存/删除）后刷新依赖界面（如引导页状态行）。 */
    fun onKeyChanged()
}

/** 内置帮助页；页面随 APK 打包在 assets/help/ 下，经 AssetLoader 以可信 origin 加载。 */
enum class HelpPage(val url: String) {
    EXPORT("${WebViewConfigHelper.HELP_BASE}help_export.html"),
    API_KEY("${WebViewConfigHelper.HELP_BASE}help_apikey.html"),
    IMPORT("${WebViewConfigHelper.HELP_BASE}help_import.html"),
    PRIVACY("${WebViewConfigHelper.HELP_BASE}help_privacy.html"),
}

/** 常量集中处，避免枚举初始化顺序问题。 */
object WebViewConfigHelper {
    const val TRUSTED_ORIGIN = "https://appassets.androidplatform.net"
    const val HELP_BASE = "$TRUSTED_ORIGIN/assets/help/"
    const val CHAT_ENTRY_URL = "$TRUSTED_ORIGIN/assets/chat/index.html"
    const val IMPORT_STREAM_URL = "$TRUSTED_ORIGIN/__import_local__/current.zip"
    const val BRIDGE_NAME = "assistantNativeBridge"
}
