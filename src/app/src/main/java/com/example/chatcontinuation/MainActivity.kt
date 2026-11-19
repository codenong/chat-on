package com.example.chatcontinuation

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import com.example.chatcontinuation.backup.BackupManager
import com.example.chatcontinuation.bridge.AssistantBridgeListener
import com.example.chatcontinuation.bridge.DocumentStartScript
import com.example.chatcontinuation.fileimport.ImportController
import com.example.chatcontinuation.fileimport.ImportStreamPathHandler
import com.example.chatcontinuation.key.SecureKeyStore
import com.example.chatcontinuation.ui.HelpPage
import com.example.chatcontinuation.ui.KeySetupDialog
import com.example.chatcontinuation.ui.OnboardingController
import com.example.chatcontinuation.ui.ShellUi
import com.example.chatcontinuation.ui.WebViewConfigHelper
import com.example.chatcontinuation.util.LogRedactor
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 承载 WebView 的壳 Activity（设计稿 6.1）。
 * 聊天业务全部在上游固定 commit 的 chat-demo 内；本类只做组装与系统级能力编排。
 */
class MainActivity : AppCompatActivity(), ShellUi {

    private lateinit var webView: WebView
    private lateinit var secureKeyStore: SecureKeyStore
    private lateinit var streamHandler: ImportStreamPathHandler
    private lateinit var backupManager: BackupManager
    private lateinit var importController: ImportController
    private lateinit var bridgeListener: AssistantBridgeListener
    private lateinit var onboarding: OnboardingController

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 页面内官方导入按钮（隐藏 <input type="file"> 触发 click）的进行中回调。 */
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = pendingFileChooser
            pendingFileChooser = null
            callback?.onReceiveValue(uri?.let { arrayOf(it) })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 组件按依赖顺序构造；ActivityResultLauncher 必须在 STARTED 前注册
        secureKeyStore = SecureKeyStore(this)
        streamHandler = ImportStreamPathHandler(this)
        val assetLoader = WebViewConfig.buildAssetLoader(applicationContext, streamHandler)

        backupManager = BackupManager(this, this)
        importController = ImportController(this, streamHandler, backupManager, this)
        bridgeListener = AssistantBridgeListener(
            applicationContext, secureKeyStore, importController, backupManager, this
        )
        importController.bridge = bridgeListener
        onboarding = OnboardingController(this, secureKeyStore, importController, this)

        // WebView：安全配置 → 客户端 → 受限桥接 → document-start 脚本 → 加载入口
        webView = findViewById(R.id.web_view)
        WebViewConfig.configureSettings(webView)
        webView.webViewClient = WebViewConfig.createClient(applicationContext, assetLoader, this)
        // 页面内官方「导入聊天记录」按钮点击隐藏 input 时回调这里；
        // 不实现则官方按钮点击无任何反应（WebView 默认无文件选择能力）
        webView.webChromeClient = fileChooserClient()
        try {
            WebViewCompat.addWebMessageListener(
                webView,
                WebViewConfigHelper.BRIDGE_NAME,
                setOf(WebViewConfigHelper.TRUSTED_ORIGIN),
                bridgeListener
            )
            val script = DocumentStartScript.build(applicationContext, secureKeyStore)
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                script,
                setOf(WebViewConfigHelper.TRUSTED_ORIGIN)
            )
        } catch (t: Throwable) {
            LogRedactor.w(TAG, "webview bridge setup failed: ${t.javaClass.simpleName}")
        }

        // blob 下载由注入脚本接管并经桥接落盘；到达此监听说明拦截失败或非 blob 下载
        webView.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("blob:")) {
                toast(getString(R.string.backup_export_receive_failed))
            } else {
                WebViewConfig.openExternally(this, url, this)
            }
        }
        webView.loadUrl(WebViewConfigHelper.CHAT_ENTRY_URL)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item -> handleMenuItem(item.itemId) }

        onboarding.attachIfNeeded(findViewById(R.id.onboarding_slot))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    onboarding.isVisible -> onboarding.dismiss()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    /**
     * 页面内官方「导入聊天记录」按钮会对隐藏 <input type="file"> 调 click()，
     * Chromium 回调 onShowFileChooser；这里桥接到系统 SAF 选择器，把用户选的
     * ZIP 以 content:// 返回给页面官方导入器（不申请任何存储权限）。
     */
    private fun fileChooserClient(): WebChromeClient =
        object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                // 上一次选择未完成时先作废其回调，避免 WebView 侧永久挂起
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback
                val accept = fileChooserParams.acceptTypes
                    ?.filter { it.isNotBlank() && it.contains("/") }
                    ?.toSet()
                    ?.ifEmpty { setOf("application/zip", "application/x-zip-compressed") }
                    ?: setOf("application/zip", "application/x-zip-compressed")
                return try {
                    fileChooserLauncher.launch(accept.toTypedArray() + "application/octet-stream")
                    true
                } catch (t: Throwable) {
                    pendingFileChooser = null
                    LogRedactor.w(TAG, "file chooser launch failed: ${t.javaClass.simpleName}")
                    false
                }
            }
        }

    private fun handleMenuItem(itemId: Int): Boolean = when (itemId) {
        R.id.action_setup_key -> {
            showKeySetupDialog(); true
        }
        R.id.action_import -> {
            importController.launchImport(); true
        }
        R.id.action_backup_guide -> {
            showBackupGuide(); true
        }
        R.id.action_help_export -> {
            openHelpPage(HelpPage.EXPORT); true
        }
        R.id.action_help_apikey -> {
            openHelpPage(HelpPage.API_KEY); true
        }
        R.id.action_help_import -> {
            openHelpPage(HelpPage.IMPORT); true
        }
        R.id.action_privacy -> {
            openHelpPage(HelpPage.PRIVACY); true
        }
        R.id.action_about -> {
            showAbout(); true
        }
        else -> false
    }

    // ---------- ShellUi ----------

    override fun toast(message: String) {
        mainHandler.post {
            // LENGTH_LONG：导入/备份提示多为多步引导，短时 Toast 用户来不及读完
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun openHelpPage(page: HelpPage) {
        onboarding.dismiss()
        webView.loadUrl(page.url)
    }

    override fun showKeySetupDialog() {
        KeySetupDialog.show(this, secureKeyStore) { onKeyChanged() }
    }

    /** 导入入口未打开：完整指引（暂存等待 + 页面设置路径 + 页面内按钮替代路径）。 */
    override fun showImportEntryGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_entry_guide_title)
            .setMessage(R.string.import_entry_guide_message)
            .setPositiveButton(R.string.import_entry_guide_ok, null)
            .show()
    }

    override fun showBackupGuide() {
        backupManager.showBackupGuide()
    }

    override fun showAbout() {
        val message = getString(
            R.string.about_format,
            getString(R.string.app_name),
            BuildConfig.VERSION_NAME,
            readVendoredCommit()
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(message)
            .setPositiveButton(R.string.key_setup_cancel, null)
            .show()
    }

    override fun onKeyChanged() {
        onboarding.refreshKeyStatus()
    }

    // ---------- 内部 ----------

    private fun readVendoredCommit(): String = try {
        assets.open("chat/COMMIT.txt").use { input ->
            val text = input.readBytes().toString(Charsets.UTF_8).trim()
            if (text.isEmpty()) getString(R.string.about_commit_unknown) else text
        }
    } catch (t: Throwable) {
        getString(R.string.about_commit_unknown)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
