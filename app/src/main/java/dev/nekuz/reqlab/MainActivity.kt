package dev.nekuz.reqlab

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private enum class Tab { REQUEST, RESPONSE, SAVED, HISTORY, ENV }

    private lateinit var stores: StoreSet
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var bar: ProgressBar
    private lateinit var adapter: RowAdapter
    private lateinit var tabs: Map<Tab, Button>
    private lateinit var panelRequest: View
    private lateinit var panelList: View
    private lateinit var actionsResponse: View
    private lateinit var actionsHistory: View
    private lateinit var actionsEnv: View
    private lateinit var methodButton: Button
    private lateinit var urlInput: EditText
    private lateinit var warning: TextView
    private lateinit var environmentButton: Button
    private lateinit var params: KvEditor
    private lateinit var headers: KvEditor
    private lateinit var form: KvEditor
    private lateinit var authButtons: Map<AuthType, Button>
    private lateinit var bodyButtons: Map<BodyType, Button>
    private lateinit var bearerGroup: View
    private lateinit var basicGroup: View
    private lateinit var tokenInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var formGroup: View
    private lateinit var followSwitch: Switch
    private lateinit var sendButton: Button
    private lateinit var cancelButton: Button
    private lateinit var copyBodyButton: Button
    private lateinit var copyHeadersButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val liveCheck = Runnable { updateChecks() }
    private val listener: () -> Unit = { render() }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

    private var tab = Tab.REQUEST
    private var method = HttpMethod.GET
    private var authType = AuthType.NONE
    private var bodyType = BodyType.RAW
    private var vars: Map<String, String> = emptyMap()
    private var binding = false
    private var currentName = ""
    private var currentCollection = ""
    private var headersExpanded = false
    private var dialog: AlertDialog? = null
    private var dialogKind: String? = null
    private var saveNameInput: EditText? = null
    private var saveCollectionInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Actions.secureWindow(window)
        setContentView(R.layout.activity_main)
        stores = Stores.get(this)
        bindViews()
        bindActions()

        tab = savedInstanceState?.getString(STATE_TAB)?.let { name ->
            Tab.entries.firstOrNull { it.name == name }
        } ?: Tab.REQUEST
        currentName = savedInstanceState?.getString(STATE_NAME).orEmpty()
        currentCollection = savedInstanceState?.getString(STATE_COLLECTION).orEmpty()
        headersExpanded = savedInstanceState?.getBoolean(STATE_HEADERS) ?: false

        vars = stores.environments.activeVariables()
        applySpec(Stores.liveDraft ?: stores.draft.load() ?: RequestSpec())
        render()
        updateChecks()
        restoreDialog(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, tab.name)
        outState.putString(STATE_NAME, currentName)
        outState.putString(STATE_COLLECTION, currentCollection)
        outState.putBoolean(STATE_HEADERS, headersExpanded)
        dialogKind?.let { kind ->
            outState.putString(STATE_DIALOG, kind)
            if (kind == DIALOG_SAVE) {
                outState.putString(STATE_DIALOG_NAME, saveNameInput?.text?.toString().orEmpty())
                outState.putString(STATE_DIALOG_COLLECTION, saveCollectionInput?.text?.toString().orEmpty())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        SendStore.addListener(listener)
        vars = stores.environments.activeVariables()
        render()
        scheduleCheck()
    }

    override fun onPause() {
        super.onPause()
        persistDraft()
    }

    override fun onStop() {
        super.onStop()
        SendStore.removeListener(listener)
    }

    override fun onDestroy() {
        handler.removeCallbacks(liveCheck)
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun bindViews() {
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        bar = findViewById(R.id.bar)
        panelRequest = findViewById(R.id.panelRequest)
        panelList = findViewById(R.id.panelList)
        actionsResponse = findViewById(R.id.actionsResponse)
        actionsHistory = findViewById(R.id.actionsHistory)
        actionsEnv = findViewById(R.id.actionsEnv)
        methodButton = findViewById(R.id.methodButton)
        urlInput = findViewById(R.id.urlInput)
        warning = findViewById(R.id.warning)
        environmentButton = findViewById(R.id.environmentButton)
        bearerGroup = findViewById(R.id.bearerGroup)
        basicGroup = findViewById(R.id.basicGroup)
        tokenInput = findViewById(R.id.tokenInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        bodyInput = findViewById(R.id.bodyInput)
        formGroup = findViewById(R.id.formGroup)
        followSwitch = findViewById(R.id.followSwitch)
        sendButton = findViewById(R.id.sendButton)
        cancelButton = findViewById(R.id.cancelButton)
        copyBodyButton = findViewById(R.id.copyBodyButton)
        copyHeadersButton = findViewById(R.id.copyHeadersButton)
        tabs = mapOf(
            Tab.REQUEST to findViewById(R.id.tabRequest),
            Tab.RESPONSE to findViewById(R.id.tabResponse),
            Tab.SAVED to findViewById(R.id.tabSaved),
            Tab.HISTORY to findViewById(R.id.tabHistory),
            Tab.ENV to findViewById(R.id.tabEnv),
        )
        authButtons = mapOf(
            AuthType.NONE to findViewById(R.id.authNone),
            AuthType.BEARER to findViewById(R.id.authBearer),
            AuthType.BASIC to findViewById(R.id.authBasic),
        )
        bodyButtons = mapOf(
            BodyType.RAW to findViewById(R.id.bodyRaw),
            BodyType.JSON to findViewById(R.id.bodyJson),
            BodyType.FORM to findViewById(R.id.bodyForm),
        )
        params = KvEditor(findViewById<LinearLayout>(R.id.paramsBox), R.string.param_name, R.string.param_value, Limits.MAX_ROWS, ::scheduleCheck)
        headers = KvEditor(findViewById<LinearLayout>(R.id.headersBox), R.string.header_name, R.string.header_value, Limits.MAX_ROWS, ::scheduleCheck)
        form = KvEditor(findViewById<LinearLayout>(R.id.formBox), R.string.field_name, R.string.field_value, Limits.MAX_ROWS, ::scheduleCheck)
        adapter = RowAdapter(this)
        list.adapter = adapter
        listOf(urlInput, tokenInput, usernameInput, passwordInput, bodyInput).forEach {
            Actions.refuseOverLimit(it, Limits.MAX_STORED_VALUE)
        }
    }

    private fun bindActions() {
        tabs.forEach { (target, button) ->
            button.setOnClickListener {
                tab = target
                hideKeyboard()
                render()
                list.setSelection(0)
            }
        }
        methodButton.setOnClickListener { chooseMethod() }
        environmentButton.setOnClickListener {
            tab = Tab.ENV
            hideKeyboard()
            render()
        }
        authButtons.forEach { (type, button) ->
            button.setOnClickListener {
                authType = type
                renderEditorChoices()
                scheduleCheck()
            }
        }
        bodyButtons.forEach { (type, button) ->
            button.setOnClickListener {
                bodyType = type
                renderEditorChoices()
            }
        }
        findViewById<View>(R.id.addParam).setOnClickListener { addRow(params) }
        findViewById<View>(R.id.addHeader).setOnClickListener { addRow(headers) }
        findViewById<View>(R.id.addField).setOnClickListener { addRow(form) }
        findViewById<View>(R.id.previewButton).setOnClickListener { showPreview() }
        findViewById<View>(R.id.saveButton).setOnClickListener { showSave(currentName, currentCollection) }
        findViewById<View>(R.id.curlButton).setOnClickListener { showCurl() }
        findViewById<View>(R.id.newButton).setOnClickListener { confirmNew() }
        sendButton.setOnClickListener { onSend() }
        cancelButton.setOnClickListener { SendStore.cancel() }
        copyBodyButton.setOnClickListener { copyBody() }
        copyHeadersButton.setOnClickListener { copyHeaders() }
        findViewById<View>(R.id.clearHistory).setOnClickListener { confirmClearHistory() }
        findViewById<View>(R.id.newEnvironment).setOnClickListener { newEnvironment() }
        findViewById<View>(R.id.infoButton).setOnClickListener {
            present(AlertDialog.Builder(this).setTitle(R.string.info).setMessage(R.string.info_message).setPositiveButton(R.string.close, null))
        }
        list.setOnItemClickListener { _, _, position, _ -> onRowClick(adapter.rows[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            onRowLongClick(adapter.rows[position])
            true
        }
        bodyInput.setOnTouchListener { view, event ->
            view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
            false
        }
        listOf(urlInput, tokenInput, usernameInput, passwordInput).forEach { input ->
            Actions.onTextChange(input) { scheduleCheck() }
        }
    }

    private fun addRow(editor: KvEditor) {
        if (!editor.addEmpty()) Actions.toast(this, R.string.too_many_rows)
    }

    private fun collectSpec() = RequestSpec(
        method = method,
        url = urlInput.text.toString(),
        params = params.rows(),
        headers = headers.rows(),
        auth = Auth(authType, tokenInput.text.toString(), usernameInput.text.toString(), passwordInput.text.toString()),
        bodyType = bodyType,
        bodyText = bodyInput.text.toString(),
        formFields = form.rows(),
        followRedirects = followSwitch.isChecked,
    )

    private fun applySpec(spec: RequestSpec) {
        binding = true
        method = spec.method
        urlInput.setText(spec.url)
        params.set(spec.params)
        headers.set(spec.headers)
        authType = spec.auth.type
        tokenInput.setText(spec.auth.token)
        usernameInput.setText(spec.auth.username)
        passwordInput.setText(spec.auth.password)
        bodyType = spec.bodyType
        bodyInput.setText(spec.bodyText)
        form.set(spec.formFields)
        followSwitch.isChecked = spec.followRedirects
        binding = false
        renderEditorChoices()
    }

    private fun persistDraft() {
        val spec = collectSpec()
        Stores.liveDraft = spec
        stores.draft.save(spec)
    }

    private fun scheduleCheck() {
        if (binding) return
        handler.removeCallbacks(liveCheck)
        handler.postDelayed(liveCheck, CHECK_DELAY_MS)
    }

    private fun updateChecks() {
        val spec = collectSpec()
        val messages = if (spec.url.isBlank()) {
            emptyList()
        } else {
            when (val result = RequestBuilder.build(spec, vars, includeBody = false)) {
                is BuildResult.Ready -> result.plan.warnings
                is BuildResult.Rejected -> result.warnings + result.problems
            }
        }
        warning.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
        warning.text = ForeignText.block(messages.joinToString("\n"), MAX_INLINE_MESSAGE)
    }

    private fun onSend() {
        if (SendStore.isRunning) {
            SendStore.cancel()
            return
        }
        when (val result = RequestBuilder.build(collectSpec(), vars)) {
            is BuildResult.Rejected -> showProblems(result)
            is BuildResult.Ready -> {
                if (result.plan.usedVariables.isNotEmpty() || result.plan.warnings.isNotEmpty()) {
                    showPlanPreview(result.plan)
                } else {
                    send(result.plan)
                }
            }
        }
    }

    private fun send(plan: RequestPlan) {
        hideKeyboard()
        persistDraft()
        if (SendStore.start(this, plan)) {
            tab = Tab.RESPONSE
            render()
            list.setSelection(0)
        } else {
            Actions.toast(this, R.string.already_sending)
        }
    }

    private fun showProblems(result: BuildResult.Rejected) {
        val text = (result.problems + result.warnings).joinToString("\n\n")
        present(
            AlertDialog.Builder(this)
                .setTitle(R.string.cannot_send)
                .setMessage(ForeignText.block(text, MAX_DIALOG_MESSAGE))
                .setPositiveButton(R.string.close, null),
        )
    }

    private fun showPreview() {
        when (val result = RequestBuilder.build(collectSpec(), vars)) {
            is BuildResult.Rejected -> showProblems(result)
            is BuildResult.Ready -> showPlanPreview(result.plan)
        }
    }

    private fun showPlanPreview(plan: RequestPlan) {
        present(
            AlertDialog.Builder(this)
                .setTitle(R.string.preview_title)
                .setMessage(PreviewText.of(plan))
                .setPositiveButton(R.string.send) { _, _ -> send(plan) }
                .setNegativeButton(R.string.close, null),
            DIALOG_PREVIEW,
        )
    }

    private fun showCurl() {
        when (val result = RequestBuilder.build(collectSpec(), vars)) {
            is BuildResult.Rejected -> showProblems(result)
            is BuildResult.Ready -> showCurlFor(result.plan)
        }
    }

    private fun showCurlFor(plan: RequestPlan) {
        val full = CurlExport.command(plan, maskSecrets = false)
        val masked = CurlExport.command(plan, maskSecrets = true)
        if (full == null || masked == null) {
            present(AlertDialog.Builder(this).setTitle(R.string.curl_title).setMessage(R.string.curl_unavailable).setPositiveButton(R.string.close, null))
            return
        }
        val note = if (CurlExport.omitsLocation(plan)) getString(R.string.curl_no_location) + "\n\n" else ""
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.curl_title)
            .setMessage(note + ForeignText.block(masked, MAX_DIALOG_MESSAGE))
            .setNegativeButton(R.string.close, null)
        if (full == masked) {
            builder.setPositiveButton(R.string.copy) { _, _ -> copyLimited(full, false) }
        } else {
            builder.setPositiveButton(R.string.copy_with_secrets) { _, _ -> copyLimited(full, true) }
            builder.setNeutralButton(R.string.copy_masked) { _, _ -> copyLimited(masked, false) }
        }
        present(builder, DIALOG_CURL)
    }

    private fun copyLimited(text: String, sensitive: Boolean) {
        if (text.length > Limits.MAX_COPY_CHARS) {
            Actions.toast(this, R.string.copy_too_large)
        } else {
            Actions.copy(this, text, sensitive)
        }
    }

    private fun showSave(name: String, collection: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_save, null)
        val nameInput = view.findViewById<EditText>(R.id.saveName)
        val collectionInput = view.findViewById<EditText>(R.id.saveCollection)
        nameInput.setText(name)
        collectionInput.setText(collection)
        saveNameInput = nameInput
        saveCollectionInput = collectionInput
        present(
            AlertDialog.Builder(this)
                .setTitle(R.string.save_title)
                .setView(view)
                .setPositiveButton(R.string.save) { _, _ ->
                    saveCurrent(nameInput.text.toString(), collectionInput.text.toString())
                }
                .setNegativeButton(R.string.cancel, null),
            DIALOG_SAVE,
        )
    }

    private fun saveCurrent(name: String, collection: String) {
        val message = when (stores.saved.save(name, collection, collectSpec(), System.currentTimeMillis())) {
            SaveResult.SAVED -> {
                currentName = ForeignText.clean(name, Limits.MAX_NAME)
                currentCollection = ForeignText.clean(collection, Limits.MAX_NAME)
                render()
                R.string.saved
            }
            SaveResult.BAD_NAME -> R.string.save_bad_name
            SaveResult.SEAL_FAILED -> R.string.save_seal_failed
            SaveResult.FULL -> R.string.save_full
            SaveResult.WRITE_FAILED -> R.string.save_write_failed
        }
        Actions.toast(this, message)
    }

    private fun confirmNew() {
        present(
            AlertDialog.Builder(this)
                .setTitle(R.string.new_title)
                .setMessage(R.string.new_message)
                .setPositiveButton(R.string.new_request) { _, _ ->
                    currentName = ""
                    currentCollection = ""
                    applySpec(RequestSpec())
                    persistDraft()
                    updateChecks()
                }
                .setNegativeButton(R.string.cancel, null),
        )
    }

    private fun chooseMethod() {
        val names = HttpMethod.entries.map { it.name }.toTypedArray()
        present(
            AlertDialog.Builder(this)
                .setTitle(R.string.method)
                .setItems(names) { _, which ->
                    method = HttpMethod.entries[which]
                    renderEditorChoices()
                    scheduleCheck()
                },
        )
    }

    private fun confirmClearHistory() {
        present(
            AlertDialog.Builder(this)
                .setTitle(R.string.history_clear_title)
                .setMessage(R.string.history_clear_message)
                .setPositiveButton(R.string.clear) { _, _ ->
                    if (!stores.history.clear()) Actions.toast(this, R.string.history_clear_failed)
                    render()
                }
                .setNegativeButton(R.string.cancel, null),
        )
    }

    private fun newEnvironment() {
        val id = stores.environments.create(getString(R.string.environment_default_name))
        if (id == null) {
            Actions.toast(this, R.string.environment_save_failed)
            return
        }
        openEnvironment(id)
    }

    private fun openEnvironment(id: String) {
        Stores.liveEnvironment = null
        startActivity(Intent(this, EnvironmentActivity::class.java).putExtra(EnvironmentActivity.EXTRA_ID, id))
    }

    private fun onRowClick(row: Row) {
        if (tab == Tab.RESPONSE && row is Row.Header && row.payload == PAYLOAD_HEADERS) {
            headersExpanded = !headersExpanded
            render()
            return
        }
        val payload = (row as? Row.Value)?.payload ?: return
        when (tab) {
            Tab.SAVED -> loadSaved(payload)
            Tab.HISTORY -> loadHistory(payload)
            Tab.ENV -> {
                if (!stores.environments.setActive(payload.ifEmpty { null })) Actions.toast(this, R.string.environment_save_failed)
                vars = stores.environments.activeVariables()
                render()
                scheduleCheck()
            }
            else -> Unit
        }
    }

    private fun onRowLongClick(row: Row) {
        when (tab) {
            Tab.RESPONSE -> when (row) {
                is Row.Value -> copyLimited(row.value, true)
                is Row.Body -> copyLimited(row.text, true)
                else -> Unit
            }
            Tab.SAVED -> when (row) {
                is Row.Value -> row.payload?.let(::confirmDeleteSaved)
                is Row.Header -> row.payload?.let(::confirmDeleteCollection)
                else -> Unit
            }
            Tab.HISTORY -> if (row is Row.Value) copyLimited(row.value, true)
            Tab.ENV -> if (row is Row.Value) row.payload?.takeIf { it.isNotEmpty() }?.let(::openEnvironment)
            Tab.REQUEST -> Unit
        }
    }

    private fun loadSaved(id: String) {
        val item = stores.saved.list().firstOrNull { it.id == id } ?: return
        val spec = stores.saved.open(id)
        if (spec == null) {
            Actions.toast(this, R.string.saved_unreadable)
            return
        }
        currentName = item.name
        currentCollection = item.collection
        applySpec(spec)
        persistDraft()
        tab = Tab.REQUEST
        render()
        updateChecks()
    }

    private fun loadHistory(position: String) {
        val entry = position.toIntOrNull()?.let { stores.history.list().getOrNull(it) } ?: return
        currentName = ""
        currentCollection = ""
        applySpec(HistoryEntries.toSpec(entry))
        persistDraft()
        tab = Tab.REQUEST
        render()
        updateChecks()
        Actions.toast(this, R.string.history_loaded)
    }

    private fun confirmDeleteSaved(id: String) {
        val item = stores.saved.list().firstOrNull { it.id == id } ?: return
        present(
            AlertDialog.Builder(this)
                .setTitle(R.string.saved_delete_title)
                .setMessage(item.name)
                .setPositiveButton(R.string.delete) { _, _ ->
                    if (!stores.saved.delete(id)) Actions.toast(this, R.string.save_write_failed)
                    render()
                }
                .setNegativeButton(R.string.cancel, null),
        )
    }

    private fun confirmDeleteCollection(collection: String) {
        present(
            AlertDialog.Builder(this)
                .setTitle(R.string.collection_delete_title)
                .setMessage(getString(R.string.collection_delete_message, collection))
                .setPositiveButton(R.string.delete) { _, _ ->
                    if (!stores.saved.deleteCollection(collection)) Actions.toast(this, R.string.save_write_failed)
                    render()
                }
                .setNegativeButton(R.string.cancel, null),
        )
    }

    private fun copyBody() {
        val view = (SendStore.state as? SendStore.State.Finished)?.view ?: return
        if (view.copyText.isEmpty()) {
            Actions.toast(this, R.string.nothing_to_copy)
            return
        }
        Actions.copy(this, view.copyText, sensitive = true)
        if (view.copyCut) Actions.toast(this, R.string.copy_cut)
    }

    private fun copyHeaders() {
        val view = (SendStore.state as? SendStore.State.Finished)?.view ?: return
        copyLimited(view.headers.joinToString("\n") { "${it.key}: ${it.value}" }, true)
    }

    private fun present(builder: AlertDialog.Builder, kind: String? = null) {
        dialog?.dismiss()
        val created = builder.create()
        created.setOnDismissListener {
            if (dialog === created) {
                dialog = null
                dialogKind = null
            }
        }
        dialog = created
        dialogKind = kind
        Actions.secureWindow(created.window)
        created.show()
        Actions.hardenButtons(created)
    }

    private fun restoreDialog(state: Bundle?) {
        when (state?.getString(STATE_DIALOG)) {
            DIALOG_PREVIEW -> showPreview()
            DIALOG_CURL -> showCurl()
            DIALOG_SAVE -> showSave(state?.getString(STATE_DIALOG_NAME).orEmpty(), state?.getString(STATE_DIALOG_COLLECTION).orEmpty())
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun select(button: Button, selected: Boolean) {
        button.setBackgroundResource(if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
        button.setTextColor(getColor(if (selected) R.color.on_brand else R.color.text_primary))
    }

    private fun renderEditorChoices() {
        methodButton.text = method.name
        authButtons.forEach { (type, button) -> select(button, type == authType) }
        bodyButtons.forEach { (type, button) -> select(button, type == bodyType) }
        bearerGroup.visibility = if (authType == AuthType.BEARER) View.VISIBLE else View.GONE
        basicGroup.visibility = if (authType == AuthType.BASIC) View.VISIBLE else View.GONE
        bodyInput.visibility = if (bodyType == BodyType.FORM) View.GONE else View.VISIBLE
        formGroup.visibility = if (bodyType == BodyType.FORM) View.VISIBLE else View.GONE
        bodyInput.setHint(if (bodyType == BodyType.JSON) R.string.body_json_hint else R.string.body_raw_hint)
    }

    private fun render() {
        tabs.forEach { (target, button) -> select(button, target == tab) }
        panelRequest.visibility = if (tab == Tab.REQUEST) View.VISIBLE else View.GONE
        panelList.visibility = if (tab == Tab.REQUEST) View.GONE else View.VISIBLE
        actionsResponse.visibility = if (tab == Tab.RESPONSE) View.VISIBLE else View.GONE
        actionsHistory.visibility = if (tab == Tab.HISTORY) View.VISIBLE else View.GONE
        actionsEnv.visibility = if (tab == Tab.ENV) View.VISIBLE else View.GONE
        val running = SendStore.isRunning
        sendButton.setText(if (running) R.string.cancel_request else R.string.send)
        bar.visibility = if (running && tab == Tab.RESPONSE) View.VISIBLE else View.GONE
        renderEnvironmentButton()
        when (tab) {
            Tab.REQUEST -> status.setText(if (running) R.string.status_running else R.string.status_request)
            Tab.RESPONSE -> renderResponse()
            Tab.SAVED -> renderSaved()
            Tab.HISTORY -> renderHistory()
            Tab.ENV -> renderEnvironments()
        }
    }

    private fun renderEnvironmentButton() {
        val active = stores.environments.activeId()?.let { stores.environments.find(it) }
        environmentButton.text = if (active == null) {
            getString(R.string.environment_none_button)
        } else {
            getString(R.string.environment_button, active.name)
        }
    }

    private fun showEmpty(text: Int?) {
        empty.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) empty.setText(text)
    }

    private fun setEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    private fun renderResponse() {
        val state = SendStore.state
        setEnabled(cancelButton, state is SendStore.State.Running)
        setEnabled(copyBodyButton, state is SendStore.State.Finished)
        setEnabled(copyHeadersButton, state is SendStore.State.Finished)
        val rows = ArrayList<Row>()
        when (state) {
            SendStore.State.Idle -> status.setText(R.string.status_response_idle)
            is SendStore.State.Running -> {
                status.text = getString(R.string.status_sending, state.method, ForeignText.clean(state.url, ForeignText.MAX_LABEL))
            }
            is SendStore.State.Failed -> {
                status.setText(R.string.status_failed)
                rows.add(Row.Value(getString(R.string.status_failed), ForeignText.clean(state.message), highlighted = true))
            }
            SendStore.State.Cancelled -> {
                status.setText(R.string.status_cancelled)
                rows.add(Row.Note(getString(R.string.status_cancelled)))
            }
            SendStore.State.DeadlineExceeded -> {
                status.setText(R.string.status_deadline)
                rows.add(Row.Note(getString(R.string.status_deadline)))
            }
            is SendStore.State.Finished -> {
                status.text = state.view.statusLine
                rows.addAll(responseRows(state.view))
            }
        }
        adapter.rows = rows
        showEmpty(if (state == SendStore.State.Idle) R.string.response_empty else null)
    }

    private fun responseRows(view: ResponseView): List<Row> {
        val rows = ArrayList<Row>()
        rows.add(
            Row.Value(
                getString(R.string.label_status),
                view.statusLine,
                tag = statusTag(view.statusClass),
                tagColor = StatusColors.of(view.statusClass),
                highlighted = view.statusClass == StatusClass.SERVER_ERROR,
            ),
        )
        val size = Text.size(view.sizeBytes)
        rows.add(
            Row.Value(
                getString(R.string.label_time_size),
                view.time + " · " + if (view.truncated) getString(R.string.size_cut, size) else size,
                tag = if (view.truncated) getString(R.string.tag_cut) else null,
                tagColor = R.color.level_review,
                highlighted = view.truncated,
            ),
        )
        rows.add(Row.Value(getString(R.string.label_url), view.finalUrl))
        view.notes.forEach { rows.add(Row.Note(it)) }
        if (view.hops.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.section_redirects)))
            view.hops.forEach { rows.add(Row.Value("", it)) }
        }
        val headerTitle = if (headersExpanded) R.string.section_headers_open else R.string.section_headers_closed
        rows.add(Row.Header(getString(headerTitle, view.headers.size), PAYLOAD_HEADERS))
        if (headersExpanded) view.headers.forEach { rows.add(Row.Value(it.key, it.value)) }
        rows.add(Row.Header(getString(R.string.section_body)))
        when (view.bodyKind) {
            BodyKind.EMPTY -> rows.add(Row.Note(getString(R.string.body_empty)))
            BodyKind.BINARY -> rows.add(Row.Note(getString(R.string.body_binary)))
            BodyKind.JSON -> rows.add(Row.Note(getString(R.string.body_json)))
            BodyKind.TEXT -> Unit
        }
        if (view.truncated) rows.add(Row.Note(getString(R.string.body_cut)))
        view.bodyChunks.forEach { rows.add(Row.Body(it)) }
        return rows
    }

    private fun statusTag(status: StatusClass) = when (status) {
        StatusClass.SUCCESS -> getString(R.string.tag_success)
        StatusClass.REDIRECT -> getString(R.string.tag_redirect)
        StatusClass.CLIENT_ERROR -> getString(R.string.tag_client)
        StatusClass.SERVER_ERROR -> getString(R.string.tag_server)
        StatusClass.INFORMATIONAL -> getString(R.string.tag_info)
        StatusClass.UNKNOWN -> getString(R.string.tag_unknown)
    }

    private fun renderSaved() {
        val items = stores.saved.list()
        status.text = resources.getQuantityString(R.plurals.saved_count, items.size, items.size)
        val rows = ArrayList<Row>()
        items.groupBy { it.collection }.toSortedMap().forEach { (collection, members) ->
            rows.add(Row.Header(collection, collection))
            members.sortedBy { it.name }.forEach { rows.add(Row.Value(it.method.name, it.name, payload = it.id)) }
        }
        adapter.rows = rows
        showEmpty(if (rows.isEmpty()) R.string.saved_empty else null)
    }

    private fun renderHistory() {
        val items = stores.history.list()
        status.text = resources.getQuantityString(R.plurals.history_count, items.size, items.size)
        adapter.rows = items.mapIndexed { index, entry ->
            Row.Value(
                label = dateFormat.format(Date(entry.time)) + " · " + entry.method.name,
                value = ForeignText.clean(entry.url, MAX_HISTORY_URL),
                tag = entry.result.ifEmpty { null },
                tagColor = if (entry.status > 0) StatusColors.of(StatusClass.of(entry.status)) else R.color.text_secondary,
                payload = index.toString(),
            )
        }
        showEmpty(if (items.isEmpty()) R.string.history_empty else null)
    }

    private fun renderEnvironments() {
        val items = stores.environments.list()
        val active = stores.environments.activeId()
        status.text = resources.getQuantityString(R.plurals.environment_count, items.size, items.size)
        val rows = ArrayList<Row>()
        rows.add(Row.Note(getString(R.string.environment_note)))
        rows.add(
            Row.Value(
                "",
                getString(R.string.environment_none),
                tag = if (active == null) getString(R.string.tag_active) else null,
                tagColor = R.color.level_ok,
                payload = "",
            ),
        )
        items.forEach {
            rows.add(
                Row.Value(
                    "",
                    it.name,
                    tag = if (it.id == active) getString(R.string.tag_active) else null,
                    tagColor = R.color.level_ok,
                    payload = it.id,
                ),
            )
        }
        adapter.rows = rows
        showEmpty(null)
    }

    private companion object {
        const val STATE_TAB = "tab"
        const val STATE_NAME = "name"
        const val STATE_COLLECTION = "collection"
        const val STATE_HEADERS = "headers"
        const val PAYLOAD_HEADERS = "headers"
        const val STATE_DIALOG = "dialog"
        const val STATE_DIALOG_NAME = "dialogName"
        const val STATE_DIALOG_COLLECTION = "dialogCollection"
        const val DIALOG_PREVIEW = "preview"
        const val DIALOG_CURL = "curl"
        const val DIALOG_SAVE = "save"
        const val CHECK_DELAY_MS = 250L
        const val MAX_INLINE_MESSAGE = 2_000
        const val MAX_DIALOG_MESSAGE = 16_384
        const val MAX_HISTORY_URL = 512
    }
}
