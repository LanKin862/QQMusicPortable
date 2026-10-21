package com.portable.qqmusic.ui.util

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.portable.qqmusic.R

/**
 * 轻量选项弹窗。
 *
 * 全应用只有这一种「⋮ → 选项列表」的样式：白底、小圆角、标题一行、下面是选项。
 * 之前直接用 `AlertDialog.Builder.setItems()`，字号跟着系统走（标题 20sp、选项 16sp），
 * 弹窗又宽又大；这里改成小一号的自绘布局，宽度也收到屏宽的 66%。
 *
 * 之所以不用 [AlertDialog.setItems] 再改字号：那套列表项布局来自 AppCompat 的
 * `select_dialog_item`，没有公开属性可以逐项调字号，只能整体换掉。
 */
object Menus {

    private fun px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    /**
     * 选项列表。
     *
     * @param checkedIndex 单选场景下当前选中的下标（画勾 + 绿色），-1 表示没有选中项。
     */
    fun show(
        context: Context,
        title: String?,
        items: List<String>,
        checkedIndex: Int = -1,
        onPick: (Int) -> Unit
    ) {
        val root = panel(context)
        val dialog = AlertDialog.Builder(context).setView(root).create()

        if (!title.isNullOrEmpty()) {
            root.addView(TextView(context).apply {
                text = title
                // 名称比选项大一档, 但比系统 dialog 标题的 20sp 小
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(px(context, 16), px(context, 10), px(context, 16), px(context, 8))
            })
        }

        items.forEachIndexed { index, label ->
            root.addView(TextView(context).apply {
                val checked = index == checkedIndex
                text = if (checked) "✓  $label" else label
                textSize = 14f
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (checked) R.color.brand else R.color.text_primary
                    )
                )
                includeFontPadding = false
                setPadding(px(context, 16), px(context, 10), px(context, 16), px(context, 10))
                background = ripple(context)
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            })
        }

        dialog.show()
        styleWindow(context, dialog, 0.66f)
    }

    /**
     * 单行输入弹窗，和选项弹窗同一套外壳。
     *
     * 输入时实时回调 [onTextChanged]（歌单内搜索是边打边过滤），
     * 「清空」清空输入框、「取消」交给调用方恢复原状、「确定」只负责收起。
     */
    fun showInput(
        context: Context,
        title: String?,
        hint: String?,
        initial: String?,
        onTextChanged: (String) -> Unit,
        onClear: () -> Unit,
        onCancel: () -> Unit,
        onConfirm: (String) -> Unit
    ) {
        val root = panel(context)
        val dialog = AlertDialog.Builder(context).setView(root).create()

        if (!title.isNullOrEmpty()) {
            root.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setPadding(px(context, 16), px(context, 12), px(context, 16), px(context, 6))
            })
        }

        val input = EditText(context).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            setSingleLine()
            includeFontPadding = false
            setText(initial.orEmpty())
            setSelection(text.length)
            // 左右留白交给外层容器, 免得下划线顶到圆角上
            setPadding(0, px(context, 10), 0, px(context, 10))
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onTextChanged(s?.toString().orEmpty())
        })
        val inputBox = android.widget.FrameLayout(context).apply {
            setPadding(px(context, 16), 0, px(context, 16), 0)
            addView(
                input,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            inputBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(context, 8), px(context, 4), px(context, 8), px(context, 6))
        }
        actions.addView(action(context, R.string.action_clear) {
            input.setText("")
            onClear()
        })
        actions.addView(android.view.View(context), LinearLayout.LayoutParams(0, px(context, 1), 1f))
        actions.addView(action(context, R.string.action_cancel) {
            onCancel()
            dialog.dismiss()
        })
        actions.addView(action(context, R.string.action_confirm) {
            onConfirm(input.text.toString().trim())
            dialog.dismiss()
        })
        root.addView(actions)

        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
        styleWindow(context, dialog, 0.8f)
    }

    /**
     * 确认弹窗，和选项弹窗同一套外壳。
     *
     * 「取消 + 确认」两个按钮靠右排在底部；[message] 只有一句话时才传。
     */
    fun confirm(
        context: Context,
        title: String?,
        message: String?,
        positive: String,
        negative: String,
        onPositive: () -> Unit
    ) {
        val root = panel(context)
        val dialog = AlertDialog.Builder(context).setView(root).create()

        if (!title.isNullOrEmpty()) {
            root.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setPadding(px(context, 16), px(context, 12), px(context, 16), px(context, 6))
            })
        }
        if (!message.isNullOrEmpty()) {
            root.addView(TextView(context).apply {
                text = message
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                includeFontPadding = false
                setPadding(px(context, 16), 0, px(context, 16), px(context, 4))
            })
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(px(context, 8), px(context, 2), px(context, 8), px(context, 6))
        }
        actions.addView(action(context, negative, R.color.text_secondary) { dialog.dismiss() })
        actions.addView(action(context, positive, R.color.brand) {
            dialog.dismiss()
            onPositive()
        })
        root.addView(actions)

        dialog.show()
        styleWindow(context, dialog, 0.72f)
    }

    private fun panel(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * context.resources.displayMetrics.density
            setColor(ContextCompat.getColor(context, R.color.bg_card))
        }
        setPadding(0, px(context, 4), 0, px(context, 4))
    }

    private fun action(
        context: Context,
        textRes: Int,
        onClick: () -> Unit
    ): TextView = action(context, context.getString(textRes), R.color.brand, onClick)

    private fun action(
        context: Context,
        text: String,
        colorRes: Int,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, colorRes))
        background = ripple(context)
        setPadding(px(context, 14), px(context, 10), px(context, 14), px(context, 10))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun styleWindow(context: Context, dialog: AlertDialog, widthRatio: Float) {
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val screen = context.resources.displayMetrics.widthPixels
            val width = (screen * widthRatio).toInt().coerceAtLeast(px(context, 200))
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun ripple(context: Context): android.graphics.drawable.Drawable? {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return ContextCompat.getDrawable(context, value.resourceId)
    }
}
