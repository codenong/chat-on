package com.example.chatcontinuation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import com.example.chatcontinuation.ui.ShellUi
import com.example.chatcontinuation.ui.WebViewConfigHelper
import com.example.chatcontinuation.util.LogRedactor

/**
 * WebView 安全配置（设计稿 6.1 / 七）。
 *
 * - 本地页面经 WebViewAssetLoader 以受信任 origin（appassets.androidplatform.net）
 *   加载，IndexedDB/DOM storage 挂在该 origin 下，随应用卸载清除。
 * - 关闭 file 访问与 content 访问；只允许可信本地 origin 页面运行。
 * - 非白名单导航一律交给系统浏览器；bridge 只监听可信 origin。
 */
object WebViewConfig {

    private const val TAG = "WebViewConfig"

    fun buildAssetLoader(
        context: Context,
        importHandler: WebViewAssetLoader.PathHandler
    ): WebViewAssetLoader =
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/__import_local__/", importHandler)
            .build()

    fun configureSettings(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // 显式禁用已废弃的 file 跨域能力，防御纵深
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // 交给页面自身（上游 theme.css）按系统深色模式渲染；
        // 开启算法加深会让 prefers-color-scheme 生效且不破坏已有暗色样式。
        try {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        } catch (t: Throwable) {
            LogRedactor.w(TAG, "algorithmic darkening unsupported: ${t.javaClass.simpleName}")
        }
        CookieManagerHolder.configure(webView)
        // 仅 debug 构建允许 Chrome DevTools 调试
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    fun createClient(context: Context, assetLoader: WebViewAssetLoader, ui: ShellUi): WebViewClient =
        object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return true
                when (scheme) {
                    "http", "https" -> {
                        return if (url.toString().startsWith(WebViewConfigHelper.TRUSTED_ORIGIN)) {
                            false // 可信本地内容，页面内导航
                        } else {
                            openExternally(context, url.toString(), ui)
                            true
                        }
                    }
                    // 常见外部协议交给系统处理，其余一律拦截
                    "mailto", "tel", "sms" -> {
                        openExternally(context, url.toString(), ui)
                        return true
                    }
                    else -> return true
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                LogRedactor.w(TAG, "main frame error: ${error.description}")
                val html = """
                    <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <body style="font-family:sans-serif;padding:24px;line-height:1.6">
                    <h3>${ERROR_TITLE}</h3>
                    <p>${ERROR_BODY}</p>
                    </body></html>
                """.trimIndent()
                // 以 null base 加载：错误页不属于可信 origin，无法调用受限桥接
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }

    fun openExternally(context: Context, url: String, ui: ShellUi) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            ui.toast(context.getString(com.example.chatcontinuation.R.string.key_verify_network))
        }
    }

    private const val ERROR_TITLE = "页面加载失败"
    private const val ERROR_BODY =
        "聊天核心资源未能加载。请检查存储空间后，关闭应用重新打开；若反复出现，请重新安装并在 docs/01_集成指南.md 中核对 assets/chat 目录。"

    /** Cookie 策略集中管理：允许同源 cookie，禁第三方 cookie。 */
    private object CookieManagerHolder {
        fun configure(webView: WebView) {
            android.webkit.CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, false)
            }
        }
    }
}
