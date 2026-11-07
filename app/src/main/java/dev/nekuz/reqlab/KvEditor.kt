package dev.nekuz.reqlab

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class KvEditor(
    private val box: LinearLayout,
    private val keyHint: Int,
    private val valueHint: Int,
    private val maxRows: Int,
    private val onChange: () -> Unit,
) {
    private val inflater = LayoutInflater.from(box.context)
    private var binding = false

    fun set(rows: List<Kv>) {
        binding = true
        box.removeAllViews()
        rows.take(maxRows).forEach(::addRow)
        binding = false
    }

    fun rows(): List<Kv> {
        val rows = ArrayList<Kv>(box.childCount)
        for (index in 0 until box.childCount) {
            val row = box.getChildAt(index)
            rows.add(Kv(text(row, R.id.key), text(row, R.id.value)))
        }
        return rows.filterNot { it.key.isEmpty() && it.value.isEmpty() }
    }

    fun addEmpty(): Boolean {
        if (box.childCount >= maxRows) return false
        addRow(Kv("", ""))
        box.getChildAt(box.childCount - 1).findViewById<View>(R.id.key).requestFocus()
        return true
    }

    private fun text(row: View, id: Int) = row.findViewById<TextView>(id).text.toString()

    private fun addRow(kv: Kv) {
        val row = inflater.inflate(R.layout.row_kv, box, false)
        val key = row.findViewById<EditText>(R.id.key)
        val value = row.findViewById<EditText>(R.id.value)
        key.setHint(keyHint)
        value.setHint(valueHint)
        Actions.refuseOverLimit(key, Limits.MAX_STORED_VALUE)
        Actions.refuseOverLimit(value, Limits.MAX_STORED_VALUE)
        key.setText(kv.key)
        value.setText(kv.value)
        Actions.onTextChange(key, ::changed)
        Actions.onTextChange(value, ::changed)
        row.findViewById<View>(R.id.remove).setOnClickListener {
            box.removeView(row)
            changed()
        }
        box.addView(row)
    }

    private fun changed() {
        if (!binding) onChange()
    }
}
