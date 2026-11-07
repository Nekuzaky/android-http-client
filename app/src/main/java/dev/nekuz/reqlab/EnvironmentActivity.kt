package dev.nekuz.reqlab

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class EnvironmentActivity : Activity() {
    private lateinit var nameInput: EditText
    private lateinit var note: TextView
    private lateinit var editor: KvEditor
    private lateinit var environmentId: String
    private var ready = false
    private var dirty = false
    private var deleted = false
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Actions.secureWindow(window)
        val stores = Stores.get(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val info = id?.let { stores.environments.find(it) }
        if (id == null || info == null) {
            finish()
            return
        }
        environmentId = id
        setContentView(R.layout.activity_environment)
        nameInput = findViewById(R.id.nameInput)
        note = findViewById(R.id.note)
        editor = KvEditor(
            findViewById<LinearLayout>(R.id.variablesBox),
            R.string.variable_name,
            R.string.variable_value,
            EnvironmentStore.MAX_VARIABLES,
        ) { edited() }

        val memory = Stores.liveEnvironment?.takeIf { it.id == id }
        if (memory != null) {
            nameInput.setText(memory.name)
            editor.set(memory.rows)
            dirty = memory.dirty
        } else {
            nameInput.setText(info.name)
            val rows = stores.environments.rows(id)
            if (rows == null) Actions.toast(this, R.string.environment_unreadable)
            editor.set(rows.orEmpty())
        }
        Actions.onTextChange(nameInput) { edited() }
        ready = true
        updateNote()

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.addVariable).setOnClickListener {
            if (!editor.addEmpty()) Actions.toast(this, R.string.too_many_rows)
        }
        findViewById<View>(R.id.deleteEnvironment).setOnClickListener { confirmDelete() }
    }

    override fun onPause() {
        super.onPause()
        if (!ready || deleted) return
        Stores.liveEnvironment = EnvironmentDraft(environmentId, nameInput.text.toString(), editor.rows(), dirty)
        if (!dirty) return
        if (Stores.get(this).environments.update(environmentId, nameInput.text.toString(), editor.rows())) {
            dirty = false
            Stores.liveEnvironment = EnvironmentDraft(environmentId, nameInput.text.toString(), editor.rows(), false)
        } else {
            Actions.toast(this, R.string.environment_save_failed)
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        if (isFinishing && ready && !dirty) Stores.liveEnvironment = null
        if (deleted) Stores.liveEnvironment = null
        super.onDestroy()
    }

    private fun edited() {
        if (!ready) return
        dirty = true
        updateNote()
    }

    private fun updateNote() {
        val rows = editor.rows()
        val invalid = Variables.invalidNames(rows)
        val tooLong = rows.any { it.key.length > Limits.MAX_STORED_KEY || it.value.length > Limits.MAX_STORED_VALUE }
        note.visibility = if (invalid.isEmpty() && !tooLong) View.GONE else View.VISIBLE
        note.text = when {
            tooLong -> getString(R.string.variable_too_long)
            invalid.isEmpty() -> ""
            else -> getString(R.string.variable_invalid, ForeignText.clean(invalid.joinToString(", "), ForeignText.MAX_LABEL))
        }
    }

    private fun confirmDelete() {
        val built = AlertDialog.Builder(this)
            .setTitle(R.string.environment_delete_title)
            .setMessage(R.string.environment_delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (Stores.get(this).environments.delete(environmentId)) {
                    deleted = true
                    finish()
                } else {
                    Actions.toast(this, R.string.environment_save_failed)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog = built
        Actions.secureWindow(built.window)
        built.show()
        Actions.hardenButtons(built)
    }

    companion object {
        const val EXTRA_ID = "environment"
    }
}
