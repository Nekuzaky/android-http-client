package dev.nekuz.reqlab

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText

class SafeEditText(context: Context, attrs: AttributeSet?) : EditText(context, attrs) {
    override fun onPreDraw(): Boolean = if (width == 0) true else super.onPreDraw()
}
