package app.dak.backup.engine

import java.io.InputStream
import java.io.OutputStream

/**
 * A place to store named backup blobs: a manifest, a snapshot archive, and so on. Implementations
 * for the user's own Google Drive, Dropbox or SAF-selected folder live in `:app`; [LocalDirectoryTarget]
 * is the local, file-based implementation used here for tests.
 */
interface BackupTarget {
    /** Names of every blob currently stored, in no particular order. */
    suspend fun list(): List<String>

    /** Opens [name] for reading, or returns null if it does not exist. Caller closes the stream. */
    suspend fun openRead(name: String): InputStream?

    /** Opens [name] for writing (overwriting any existing blob of the same name). Caller closes the stream. */
    suspend fun openWrite(name: String): OutputStream

    /** Deletes [name] if present; a no-op if it does not exist. */
    suspend fun delete(name: String)
}
