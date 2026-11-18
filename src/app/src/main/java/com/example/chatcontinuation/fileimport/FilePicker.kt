package com.example.chatcontinuation.fileimport

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 系统文件选择器封装（设计稿 6.1 FilePicker / 阶段 2）。
 *
 * 使用 Storage Access Framework 的 OpenDocument，不申请任何宽泛存储权限；
 * 只列出 ZIP 相关 MIME（部分机型把 zip 标成 application/octet-stream，一并放行，
 * 真正的格式校验交给上游官方导入器，不凭文件名判断）。
 */
class FilePicker(
    activity: ComponentActivity,
    private val onPicked: (Uri?) -> Unit
) {

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onPicked(uri)
        }

    fun launch() {
        launcher.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream"
            )
        )
    }
}
