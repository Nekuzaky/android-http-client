package dev.nekuz.reqlab

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

sealed class Row {
    data class Header(val text: String, val payload: String? = null) : Row()

    data class Note(val text: String) : Row()

    data class Body(val text: String) : Row()

    data class Value(
        val label: String,
        val value: String,
        val tag: String? = null,
        val tagColor: Int = R.color.text_secondary,
        val highlighted: Boolean = false,
        val payload: String? = null,
    ) : Row()
}

object StatusColors {
    fun of(status: StatusClass): Int = when (status) {
        StatusClass.SUCCESS -> R.color.level_ok
        StatusClass.REDIRECT, StatusClass.INFORMATIONAL -> R.color.level_review
        StatusClass.CLIENT_ERROR, StatusClass.SERVER_ERROR -> R.color.level_risk
        StatusClass.UNKNOWN -> R.color.text_secondary
    }
}

class RowAdapter(private val context: Context) : BaseAdapter() {
    var rows: List<Row> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private val inflater = LayoutInflater.from(context)

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 4

    override fun isEnabled(position: Int) = when (val row = rows[position]) {
        is Row.Value, is Row.Body -> true
        is Row.Header -> row.payload != null
        is Row.Note -> false
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> 0
        is Row.Note -> 1
        is Row.Body -> 2
        is Row.Value -> 3
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val row = rows[position]) {
            is Row.Header -> text(convertView, parent, R.layout.row_header, row.text)
            is Row.Note -> text(convertView, parent, R.layout.row_note, row.text)
            is Row.Body -> text(convertView, parent, R.layout.row_body, row.text)
            is Row.Value -> value(convertView, parent, row)
        }

    private fun text(convertView: View?, parent: ViewGroup, layout: Int, text: String): View {
        val view = convertView ?: inflater.inflate(layout, parent, false)
        (view as TextView).text = text
        return view
    }

    private fun value(convertView: View?, parent: ViewGroup, row: Row.Value): View {
        val view = convertView ?: inflater.inflate(R.layout.row_value, parent, false)
        view.setBackgroundResource(if (row.highlighted) R.drawable.bg_row_highlight else R.drawable.bg_row)
        view.findViewById<TextView>(R.id.label).text = row.label
        val tag = view.findViewById<TextView>(R.id.tag)
        tag.visibility = if (row.tag == null) View.GONE else View.VISIBLE
        tag.text = row.tag
        tag.setTextColor(context.getColor(row.tagColor))
        view.findViewById<View>(R.id.labelRow).visibility =
            if (row.label.isEmpty() && row.tag == null) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.value).text = row.value
        return view
    }
}
