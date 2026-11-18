package com.example.chatcontinuation.fileimport

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout

/**
 * 会话选择列表项的可勾选容器：外层承担 Checkable 状态，
 * 同步内部 CheckBox 指示器；点击行为由列表 OnItemClickListener 驱动。
 */
class CheckableLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), android.widget.Checkable {

    private var checked = false

    override fun isChecked(): Boolean = checked

    override fun setChecked(checked: Boolean) {
        if (this.checked == checked) return
        this.checked = checked
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is CheckBox) child.isChecked = checked
        }
        refreshDrawableState()
    }

    override fun toggle() {
        setChecked(!checked)
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val state = super.onCreateDrawableState(extraSpace + 1)
        if (checked) {
            View.mergeDrawableStates(state, intArrayOf(android.R.attr.state_checked))
        }
        return state
    }
}
