package app.dak.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * [PersistenceAdapter] over a Preferences [DataStore]: every setting is stored as a string under its registry key.
 *
 * The registry's contract is synchronous, so values are served from an in-memory snapshot that is loaded from disk
 * once and updated immediately on every write; the write itself is persisted to DataStore asynchronously, in order.
 *
 * The load starts on [Dispatchers.IO] as soon as the adapter is created (in `Application.onCreate`, via the DI
 * graph), so by the time the first activity reads a value it is normally already in memory. A synchronous read
 * that races the load waits for it (a small file read, never a second disk read); [observe] and [observeAll]
 * suspend instead of blocking.
 */
class DataStorePersistenceAdapter(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : PersistenceAdapter {

    private val snapshot = MutableStateFlow<Map<String, String>>(emptyMap())
    @Volatile private var loaded = false
    private val writes = Channel<(MutablePreferences) -> Unit>(Channel.UNLIMITED)

    /** The first disk read, started eagerly off the main thread. A failed read yields defaults. */
    private val initial: Deferred<Map<String, String>> = scope.async(Dispatchers.IO) {
        runCatching { dataStore.data.first() }
            .map { prefs -> prefs.asMap().entries.mapNotNull { (k, v) -> (v as? String)?.let { k.name to it } }.toMap() }
            .getOrElse { emptyMap() }
    }

    init {
        // Single consumer keeps writes in the order they were made.
        scope.launch(Dispatchers.IO) {
            for (write in writes) dataStore.edit { write(it) }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        // Normally already complete (preloaded at startup), so this returns at once instead of waiting.
        applyLoaded(runBlocking { initial.await() })
    }

    private suspend fun awaitLoaded() {
        if (loaded) return
        applyLoaded(initial.await())
    }

    private fun applyLoaded(values: Map<String, String>) {
        synchronized(this) {
            if (loaded) return
            snapshot.value = values
            loaded = true
        }
    }

    override fun get(key: String): String? {
        ensureLoaded()
        return snapshot.value[key]
    }

    override fun set(key: String, value: String) {
        ensureLoaded()
        synchronized(this) { snapshot.value = snapshot.value + (key to value) }
        persist { it[stringPreferencesKey(key)] = value }
    }

    override fun remove(key: String) {
        ensureLoaded()
        synchronized(this) { snapshot.value = snapshot.value - key }
        persist { it.remove(stringPreferencesKey(key)) }
    }

    override fun observe(key: String): Flow<String?> =
        snapshot.onStart { awaitLoaded() }.map { it[key] }.distinctUntilChanged()

    override fun keys(): Set<String> {
        ensureLoaded()
        return snapshot.value.keys
    }

    /** Emits the whole raw snapshot on every change (used for "changed from default" ranking). */
    fun observeAll(): Flow<Map<String, String>> = snapshot.onStart { awaitLoaded() }

    private fun persist(block: (MutablePreferences) -> Unit) {
        writes.trySend(block)
    }
}
