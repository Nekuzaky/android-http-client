package dev.nekuz.reqlab

import android.content.Context
import android.os.Handler
import android.os.Looper

object SendStore {
    sealed class State {
        object Idle : State()

        data class Running(val method: String, val url: String) : State()

        data class Finished(val view: ResponseView) : State()

        data class Failed(val message: String) : State()

        object Cancelled : State()

        object DeadlineExceeded : State()
    }

    private class Completion(
        val state: State,
        val status: Int,
        val result: String,
        val elapsedMs: Long,
        val sizeBytes: Long,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var generation = 0L
    private var activeToken: CancelToken? = null
    private var activeEntry: HistoryEntry? = null
    private var activeStores: StoreSet? = null

    var state: State = State.Idle
        private set

    val isRunning: Boolean
        get() = state is State.Running

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun start(context: Context, plan: RequestPlan): Boolean {
        if (isRunning) return false
        val startedAt = System.currentTimeMillis()
        val entry = HistoryEntries.from(plan, startedAt, 0, "", 0, 0)
        val token = CancelToken()
        generation++
        val ticket = generation
        activeToken = token
        activeEntry = entry
        activeStores = Stores.get(context)
        state = State.Running(plan.method.name, entry.url)
        notifyListeners()
        val worker = Thread({
            val completion = try {
                perform(plan, token)
            } catch (e: Throwable) {
                Completion(State.Failed(FAILED_MESSAGE), 0, FAILED_MESSAGE, 0, 0)
            }
            handler.post { complete(ticket, completion) }
        }, "reqlab-send")
        worker.isDaemon = true
        worker.start()
        return true
    }

    fun cancel() {
        val token = activeToken ?: return
        if (!isRunning) return
        generation++
        record(Completion(State.Cancelled, 0, "Cancelled", 0, 0))
        val closer = Thread({ token.cancel() }, "reqlab-cancel")
        closer.isDaemon = true
        closer.start()
    }

    private fun perform(plan: RequestPlan, token: CancelToken): Completion =
        when (val outcome = HttpEngine.execute(plan, token)) {
            is Outcome.Done -> {
                val view = ResponseViews.build(outcome.response, plan::maskUrl)
                Completion(State.Finished(view), outcome.response.status, view.statusLine, outcome.response.elapsedMs, view.sizeBytes)
            }
            is Outcome.Failed -> Completion(State.Failed(outcome.message), 0, "Failed", 0, 0)
            Outcome.Cancelled -> Completion(State.Cancelled, 0, "Cancelled", 0, 0)
            Outcome.DeadlineExceeded -> Completion(State.DeadlineExceeded, 0, "Timed out", 0, 0)
        }

    private fun complete(ticket: Long, completion: Completion) {
        if (ticket != generation) return
        record(completion)
    }

    private fun record(completion: Completion) {
        val entry = activeEntry
        val stores = activeStores
        if (entry != null && stores != null) {
            stores.history.add(
                entry.copy(
                    status = completion.status,
                    result = ForeignText.clean(completion.result, 120),
                    elapsedMs = completion.elapsedMs,
                    sizeBytes = completion.sizeBytes,
                ),
            )
        }
        activeToken = null
        activeEntry = null
        activeStores = null
        state = completion.state
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    private const val FAILED_MESSAGE = "The request failed."
}
