package dev.nekuz.reqlab

import android.content.Context
import java.io.File

class StoreSet(directory: File) {
    private val sealer = KeystoreSealer()
    val saved = SavedStore(File(directory, "saved.json"), sealer)
    val environments = EnvironmentStore(File(directory, "environments.json"), sealer)
    val draft = DraftStore(File(directory, "draft.json"), sealer)
    val history = HistoryStore(File(directory, "history.json"), sealer)
}

data class EnvironmentDraft(val id: String, val name: String, val rows: List<Kv>, val dirty: Boolean)

object Stores {
    private var set: StoreSet? = null

    var liveDraft: RequestSpec? = null
    var liveEnvironment: EnvironmentDraft? = null

    @Synchronized
    fun get(context: Context): StoreSet {
        set?.let { return it }
        val created = StoreSet(File(context.applicationContext.filesDir, "reqlab"))
        set = created
        return created
    }
}
