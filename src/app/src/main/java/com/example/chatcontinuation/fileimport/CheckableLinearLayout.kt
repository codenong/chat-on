package com.example.chatcontinuation.fileimport

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout

/**
 * 会话选择列表项的可勾选容器：外层承担 Checkable 状态，
 * 同步内部 CheckBox 指示器；点击行为由行自身 OnClickListener 驱动（0.2.3 起）。
 */
class CheckableLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), android.widget.Checkable {

    private var checked = false

    override fun isChecked(): Boolean = checked

    /**
     * 无条件同步（0.2.4）：不再早退。ListView 复用的 scrap view 可能带着
     * 与目标不一致的旧状态（早退会跳过子 CheckBox 同步，造成勾选框视觉
     * 不更新）；本方法幂等，每次绑定直接刷到目标状态最稳妥。
     */
    override fun setChecked(checked: Boolean) {
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
