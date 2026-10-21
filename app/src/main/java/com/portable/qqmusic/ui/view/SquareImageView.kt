package com.portable.qqmusic.ui.view

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.imageview.ShapeableImageView

/**
 * 正方形的 ShapeableImageView。
 *
 * 播放页封面原来用 fitCenter 塞进一个「宽 match_parent、高 weight=1」的框里 ——
 * 图片是正方形, 框比图片高, 于是圆角画在框的四个角上, 而那里根本没有像素,
 * 看上去就是「没加圆角」。这里把控件自己也变成正方形, 圆角就和图片边缘对齐了。
 */
class SquareImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ShapeableImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        val modeW = MeasureSpec.getMode(widthSpec)
        val modeH = MeasureSpec.getMode(heightSpec)
        val side = when {
            modeW == MeasureSpec.UNSPECIFIED -> h
            modeH == MeasureSpec.UNSPECIFIED -> w
            else -> minOf(w, h)
        }
        if (side > 0) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
            )
        } else {
            super.onMeasure(widthSpec, heightSpec)
        }
    }
}
