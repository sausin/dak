package app.dak.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.dak.backup.engine.BackupTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [BackupTarget] over a folder the user picked with `ACTION_OPEN_DOCUMENT_TREE`. Any document provider works:
 * local storage, an SD card, or the Google Drive / Dropbox apps' providers, so backups land in the user's own
 * cloud account without Dak ever holding their credentials. Blobs are plain documents named as the engine asks.
 */
class SafBackupTarget(context: Context, private val treeUri: Uri) : BackupTarget {
    private val resolver: ContentResolver = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootUri: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) { children().keys.toList() }

    override suspend fun openRead(name: String): InputStream? = withContext(Dispatchers.IO) {
        val uri = children()[name] ?: return@withContext null
        try {
            resolver.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            null
        }
    }

    override suspend fun openWrite(name: String): OutputStream = withContext(Dispatchers.IO) {
        val existing = children()[name]
        if (existing != null) {
            // "wt" truncates; some providers reject it, in which case replace the document instead.
            val truncated = runCatching { resolver.openOutputStream(existing, "wt") }.getOrNull()
            if (truncated != null) return@withContext truncated
            runCatching { DocumentsContract.deleteDocument(resolver, existing) }
        }
        val created = DocumentsContract.createDocument(resolver, rootUri, mimeFor(name), name)
            ?: throw IOException("Could not create $name in the backup folder")
        resolver.openOutputStream(created, "w") ?: throw IOException("Could not open $name for writing")
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) {
            val uri = children()[name] ?: return@withContext
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
    }

    /** True when the folder is still reachable (permission kept, provider present). */
    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.count >= 0 } ?: false
        }.getOrDefault(false)
    }

    private fun children(): Map<String, Uri> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val out = LinkedHashMap<String, Uri>()
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out.putIfAbsent(name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
            }
        }
        return out
    }

    private fun mimeFor(name: String): String = if (name.endsWith(".json")) "application/json" else "application/octet-stream"
}
