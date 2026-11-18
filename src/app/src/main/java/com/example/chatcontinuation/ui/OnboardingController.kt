package com.example.chatcontinuation.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.chatcontinuation.R
import com.example.chatcontinuation.fileimport.ImportController
import com.example.chatcontinuation.key.SecureKeyStore
import com.google.android.material.button.MaterialButton

/**
 * 首次启动引导（设计稿 4.1）：一句话说明 + Key 状态 + 三个帮助入口 + 导入入口。
 * 允许先导入后配置 Key；不做冗长新手教程。
 */
class OnboardingController(
    private val activity: AppCompatActivity,
    private val keyStore: SecureKeyStore,
    private val importController: ImportController,
    private val ui: ShellUi
) {

    private var rootView: View? = null

    val isVisible: Boolean
        get() = rootView != null

    fun attachIfNeeded(slot: FrameLayout) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false) || rootView != null) return

        val view = activity.layoutInflater.inflate(R.layout.view_onboarding, slot, false)
        view.findViewById<MaterialButton>(R.id.onboarding_setup_key)
            .setOnClickListener { ui.showKeySetupDialog() }
        view.findViewById<MaterialButton>(R.id.onboarding_import)
            .setOnClickListener { importController.launchImport() }
        view.findViewById<MaterialButton>(R.id.onboarding_help_export)
            .setOnClickListener { ui.openHelpPage(HelpPage.EXPORT) }
        view.findViewById<MaterialButton>(R.id.onboarding_help_apikey)
            .setOnClickListener { ui.openHelpPage(HelpPage.API_KEY) }
        view.findViewById<MaterialButton>(R.id.onboarding_help_import)
            .setOnClickListener { ui.openHelpPage(HelpPage.IMPORT) }
        view.findViewById<MaterialButton>(R.id.onboarding_enter)
            .setOnClickListener { dismiss() }

        refreshKeyStatus()
        slot.removeAllViews()
        slot.addView(view)
        slot.visibility = View.VISIBLE
        rootView = view
    }

    fun refreshKeyStatus() {
        val statusView = rootView?.findViewById<TextView>(R.id.onboarding_key_status) ?: return
        statusView.text = if (keyStore.hasKey()) {
            activity.getString(
                R.string.onboarding_key_status_configured,
                keyStore.maskedForDisplay() ?: "****"
            )
        } else {
            activity.getString(R.string.onboarding_key_status_unconfigured)
        }
    }

    /** 记录“已完成引导”，之后从菜单进入各能力。 */
    fun dismiss() {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
        val view = rootView ?: return
        rootView = null
        (view.parent as? ViewGroup)?.let { parent ->
            (parent as? FrameLayout)?.visibility = View.GONE
            parent.removeView(view)
        }
    }

    companion object {
        private const val PREFS = "shell_prefs"
        private const val KEY_DONE = "onboarding_done"
    }
}
