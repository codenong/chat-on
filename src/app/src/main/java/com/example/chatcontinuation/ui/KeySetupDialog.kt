package com.example.chatcontinuation.ui

import android.text.method.PasswordTransformationMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.chatcontinuation.R
import com.example.chatcontinuation.key.KeyVerifier
import com.example.chatcontinuation.key.SecureKeyStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/**
 * API Key 设置对话框（设计稿 4.1 / 五.流程 A / 七）。
 *
 * - 输入默认隐藏（7.6），可临时显示；
 * - “验证并保存”用 GET /models 校验，成功才落盘（与上游 verifyApiKey 行为一致）；
 * - 结果文案来自设计稿第八节：Key 无效 / 余额不足 / 网络错误 / 服务错误；
 * - 提供删除已保存 Key 的入口（7.7）；
 * - 验证在后台线程执行，Key 不进日志。
 */
class KeySetupDialog private constructor(
    private val activity: AppCompatActivity,
    private val keyStore: SecureKeyStore,
    private val onKeyChanged: () -> Unit
) {

    private lateinit var dialog: AlertDialog
    private lateinit var input: TextInputEditText
    private lateinit var status: TextView
    private lateinit var toggleButton: MaterialButton
    private var visible = false

    private fun show() {
        val view = activity.layoutInflater.inflate(R.layout.view_key_setup, null)
        input = view.findViewById(R.id.key_input)
        status = view.findViewById(R.id.key_status)
        toggleButton = view.findViewById(R.id.key_toggle_visible)

        status.text = if (keyStore.hasKey()) {
            activity.getString(
                R.string.onboarding_key_status_configured,
                keyStore.maskedForDisplay() ?: "****"
            )
        } else {
            activity.getString(R.string.key_no_saved_key)
        }

        toggleButton.setOnClickListener {
            visible = !visible
            input.transformationMethod =
                if (visible) null else PasswordTransformationMethod.getInstance()
            toggleButton.setText(if (visible) R.string.key_setup_hide else R.string.key_setup_show)
        }

        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.key_setup_title)
            .setView(view)
            .setPositiveButton(R.string.key_setup_verify_save, null)
            .setNegativeButton(R.string.key_setup_cancel, null)
            .setNeutralButton(R.string.key_setup_delete, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            neutral.isEnabled = keyStore.hasKey()
            positive.setOnClickListener { verifyAndSave(positive) }
            neutral.setOnClickListener {
                keyStore.deleteKey()
                status.text = activity.getString(R.string.key_deleted)
                onKeyChanged()
                neutral.isEnabled = false
            }
        }
        dialog.show()
    }

    private fun verifyAndSave(button: android.widget.Button) {
        val key = input.text?.toString()?.trim().orEmpty()
        if (key.isEmpty()) {
            status.text = activity.getString(R.string.key_setup_empty)
            return
        }
        status.text = activity.getString(R.string.key_verify_running)
        button.isEnabled = false
        Thread {
            val result = KeyVerifier.verify(key)
            activity.runOnUiThread {
                button.isEnabled = true
                when (result) {
                    KeyVerifier.Result.Ok -> {
                        keyStore.saveKey(key)
                        status.text = activity.getString(R.string.key_verify_ok)
                        onKeyChanged()
                        dialog.dismiss()
                    }
                    KeyVerifier.Result.InvalidKey ->
                        status.text = activity.getString(R.string.key_verify_invalid)
                    KeyVerifier.Result.InsufficientBalance ->
                        status.text = activity.getString(R.string.key_verify_insufficient)
                    KeyVerifier.Result.NetworkError ->
                        status.text = activity.getString(R.string.key_verify_network)
                    is KeyVerifier.Result.ServerError ->
                        status.text = activity.getString(R.string.key_verify_server, result.code)
                }
            }
        }.start()
    }

    companion object {
        fun show(activity: AppCompatActivity, keyStore: SecureKeyStore, onKeyChanged: () -> Unit) {
            KeySetupDialog(activity, keyStore, onKeyChanged).show()
        }
    }
}
