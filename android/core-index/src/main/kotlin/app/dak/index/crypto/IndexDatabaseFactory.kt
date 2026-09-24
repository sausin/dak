package app.dak.index.crypto

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.IndexMigrations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Builds the SQLCipher-encrypted index **without opening it**. The Keystore unwrap, the native library load and
 * the SQLCipher open all happen lazily, on the first real database access, which Room always performs on its
 * query executor (suspend DAOs, Flows, paging) - never on the thread that injected the database. Injecting a
 * repository into a ViewModel on the main thread is therefore free.
 *
 * If the passphrase cannot be recovered (Keystore wiped, key blob lost) or the file cannot be opened with it, the
 * database is deleted and recreated empty at that first access; [OpenedIndex.recreated] then tells the sync layer
 * to rebuild from the provider. Nothing is lost: the provider is canonical.
 */
object IndexDatabaseFactory {

    private const val TAG = "DakIndex"

    /**
     * The (lazily opened) database. [recreated] forces the open (Keystore + file I/O): read it off the main
     * thread only, or call [ensureOpen] first.
     */
    class OpenedIndex internal constructor(val database: DakIndexDatabase, private val opener: DeferredIndexOpen) {

        /** Whether the database had to be created from scratch in this process. Opens the database if needed. */
        val recreated: Boolean
            get() {
                database.openHelper.writableDatabase
                return opener.recreated
            }

        /** Opens the database on [Dispatchers.IO] if it is not open yet (the suspend opener). */
        suspend fun ensureOpen() {
            withContext(Dispatchers.IO) { database.openHelper.writableDatabase }
        }
    }

    /** Cheap: builds the Room instance around a deferred open helper. Safe on any thread. */
    fun open(context: Context, passphrases: IndexPassphraseStore = IndexPassphraseStore(context)): OpenedIndex {
        val appContext = context.applicationContext
        val opener = DeferredIndexOpen(appContext, passphrases)
        val database = Room.databaseBuilder(appContext, DakIndexDatabase::class.java, DakIndexDatabase.NAME)
            .openHelperFactory(DeferredOpenHelperFactory(opener))
            .addMigrations(*IndexMigrations.ALL)
            // The index is rebuilt from the Telephony provider, so a downgrade may drop everything.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        return OpenedIndex(database, opener)
    }

    /**
     * Does the expensive part exactly once, when Room first asks for a connection: native library, passphrase
     * unwrap (or creation), SQLCipher helper, and for an existing file a verification open that deletes and
     * recreates the database if the key no longer fits.
     */
    internal class DeferredIndexOpen(private val context: Context, private val passphrases: IndexPassphraseStore) {

        @Volatile
        var recreated: Boolean = false
            private set

        fun openVerified(
            configuration: SupportSQLiteOpenHelper.Configuration,
            configure: (SupportSQLiteOpenHelper) -> Unit,
        ): SupportSQLiteOpenHelper {
            System.loadLibrary("sqlcipher")
            val dbFile = context.getDatabasePath(DakIndexDatabase.NAME)
            val existedBefore = dbFile.exists()
            var fresh = !existedBefore
            val passphrase = when (val result = passphrases.loadOrCreate()) {
                is IndexPassphraseStore.Result.Existing -> result.passphrase
                is IndexPassphraseStore.Result.Created -> {
                    if (existedBefore) {
                        Log.w(TAG, "Index passphrase unavailable (lost=${result.previousLost}); deleting index for rebuild")
                        context.deleteDatabase(DakIndexDatabase.NAME)
                        fresh = true
                    }
                    result.passphrase
                }
            }
            val factory = SupportOpenHelperFactory(IndexPassphraseStore.toSqlCipherKey(passphrase))
            var helper = factory.create(configuration).also(configure)
            if (!fresh) {
                // Verify the existing file really opens with this key; a mismatch surfaces here, not on a query.
                try {
                    helper.writableDatabase
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Index could not be opened; deleting it for rebuild", e)
                    runCatching { helper.close() }
                    context.deleteDatabase(DakIndexDatabase.NAME)
                    helper = factory.create(configuration).also(configure)
                    fresh = true
                }
            }
            recreated = fresh
            return helper
        }
    }

    private class DeferredOpenHelperFactory(private val opener: DeferredIndexOpen) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
            DeferredOpenHelper(configuration, opener)
    }

    /** Delegates to the real SQLCipher helper, which is created on the first [writableDatabase]/[readableDatabase]. */
    private class DeferredOpenHelper(
        private val configuration: SupportSQLiteOpenHelper.Configuration,
        private val opener: DeferredIndexOpen,
    ) : SupportSQLiteOpenHelper {

        private val lock = Any()

        @Volatile
        private var delegate: SupportSQLiteOpenHelper? = null
        private var writeAheadLogging: Boolean? = null

        override val databaseName: String?
            get() = configuration.name

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
            synchronized(lock) {
                writeAheadLogging = enabled
                delegate?.setWriteAheadLoggingEnabled(enabled)
            }
        }

        override val writableDatabase: SupportSQLiteDatabase
            get() = helper().writableDatabase

        override val readableDatabase: SupportSQLiteDatabase
            get() = helper().readableDatabase

        override fun close() {
            synchronized(lock) { delegate?.close() }
        }

        private fun helper(): SupportSQLiteOpenHelper {
            delegate?.let { return it }
            synchronized(lock) {
                delegate?.let { return it }
                val created = opener.openVerified(configuration) { h -> writeAheadLogging?.let { h.setWriteAheadLoggingEnabled(it) } }
                delegate = created
                return created
            }
        }
    }
}
