package dev.nekuz.reqlab

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

object Actions {
    private const val CLIP_LABEL = "ReqLab"
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun copy(context: Context, value: String, sensitive: Boolean = false) {
        val done = try {
            val clip = ClipData.newPlainText(CLIP_LABEL, value)
            if (sensitive) {
                clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
            }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            true
        } catch (e: RuntimeException) {
            false
        }
        toast(context, if (done) R.string.copied else R.string.copy_failed)
    }

    fun toast(context: Context, text: Int) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun onTextChange(view: TextView, action: () -> Unit) {
        view.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = action()
        })
    }

    fun refuseOverLimit(view: TextView, maxLength: Int) {
        view.filters = arrayOf(
            InputFilter { source, start, end, dest, dstart, dend ->
                if (InputLimits.fits(dest.length, dend - dstart, end - start, maxLength)) {
                    null
                } else {
                    toast(view.context, R.string.input_too_large)
                    ""
                }
            },
        )
    }

    fun secureWindow(window: Window?) {
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun hardenButtons(dialog: AlertDialog) {
        dialog.window?.decorView?.filterTouchesWhenObscured = true
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.filterTouchesWhenObscured = true
        }
    }
}
