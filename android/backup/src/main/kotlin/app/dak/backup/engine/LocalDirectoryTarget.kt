package app.dak.backup.engine

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [BackupTarget] backed by a plain directory on disk. Used by tests and by any future desktop
 * target; the real Drive/Dropbox/SAF targets live in `:app` since they need Android/Google APIs.
 */
class LocalDirectoryTarget(private val dir: File) : BackupTarget {
    init {
        require(!dir.exists() || dir.isDirectory) { "$dir exists and is not a directory" }
        dir.mkdirs()
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
    }

    override suspend fun openRead(name: String): InputStream? = withContext(Dispatchers.IO) {
        val file = resolve(name)
        if (file.isFile) FileInputStream(file) else null
    }

    override suspend fun openWrite(name: String): OutputStream = withContext(Dispatchers.IO) {
        val file = resolve(name)
        file.parentFile?.mkdirs()
        FileOutputStream(file)
    }

    override suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        resolve(name).delete()
        Unit
    }

    private fun resolve(name: String): File {
        require(!name.contains("..")) { "unsafe blob name: $name" }
        return File(dir, name)
    }
}
