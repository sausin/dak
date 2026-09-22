package app.dak.index.crypto

import android.content.Context
import android.util.Log
import androidx.room.Room
import app.dak.index.db.DakIndexDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the SQLCipher-encrypted index. If the passphrase cannot be recovered (Keystore wiped, key blob lost) or
 * the file cannot be opened with it, the database is deleted and recreated empty; [OpenedIndex.recreated] then
 * tells the sync layer to rebuild from the provider. Nothing is lost: the provider is canonical.
 *
 * This runs Keystore and file I/O; it is invoked once, from the Hilt singleton provider.
 */
object IndexDatabaseFactory {

    private const val TAG = "DakIndex"

    /** The open database and whether it had to be created from scratch in this process. */
    class OpenedIndex(val database: DakIndexDatabase, val recreated: Boolean)

    fun open(context: Context, passphrases: IndexPassphraseStore = IndexPassphraseStore(context)): OpenedIndex {
        System.loadLibrary("sqlcipher")
        val appContext = context.applicationContext
        val dbFile = appContext.getDatabasePath(DakIndexDatabase.NAME)
        val existedBefore = dbFile.exists()

        var recreated = !existedBefore
        val passphrase = when (val result = passphrases.loadOrCreate()) {
            is IndexPassphraseStore.Result.Existing -> result.passphrase
            is IndexPassphraseStore.Result.Created -> {
                if (existedBefore) {
                    Log.w(TAG, "Index passphrase unavailable (lost=${result.previousLost}); deleting index for rebuild")
                    appContext.deleteDatabase(DakIndexDatabase.NAME)
                    recreated = true
                }
                result.passphrase
            }
        }

        val first = build(appContext, passphrase)
        if (!existedBefore || recreated) return OpenedIndex(first, recreated = true)

        // Verify the existing file really opens with this key; a mismatch surfaces here rather than on first query.
        return try {
            first.openHelper.writableDatabase
            OpenedIndex(first, recreated = false)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Index could not be opened; deleting it for rebuild", e)
            runCatching { first.close() }
            appContext.deleteDatabase(DakIndexDatabase.NAME)
            OpenedIndex(build(appContext, passphrase), recreated = true)
        }
    }

    private fun build(context: Context, passphrase: ByteArray): DakIndexDatabase =
        Room.databaseBuilder(context, DakIndexDatabase::class.java, DakIndexDatabase.NAME)
            .openHelperFactory(SupportOpenHelperFactory(IndexPassphraseStore.toSqlCipherKey(passphrase)))
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
}
