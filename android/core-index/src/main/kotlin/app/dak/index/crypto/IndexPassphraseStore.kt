package app.dak.index.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the index database passphrase: 32 random bytes generated once, wrapped with an AES-256-GCM key that lives
 * in AndroidKeyStore (alias [KEY_ALIAS]) and stored only in wrapped form under `noBackupFilesDir` (never part of a
 * device backup or transfer, so a restored device cannot open a copied database).
 */
class IndexPassphraseStore(private val context: Context) {

    /** Outcome of [loadOrCreate]. */
    sealed class Result {
        abstract val passphrase: ByteArray

        /** The existing passphrase was unwrapped. */
        class Existing(override val passphrase: ByteArray) : Result()

        /**
         * A new passphrase was generated, because none existed or the old one could not be unwrapped
         * ([previousLost] = true, e.g. the Keystore was wiped). Any existing database is unreadable and must be
         * deleted and rebuilt from the provider.
         */
        class Created(override val passphrase: ByteArray, val previousLost: Boolean) : Result()
    }

    private val blobFile: File get() = File(context.noBackupFilesDir, BLOB_FILE)

    @Synchronized
    fun loadOrCreate(): Result {
        val file = AtomicFile(blobFile)
        if (blobFile.exists()) {
            val unwrapped = runCatching { unwrap(file.readFully()) }.getOrNull()
            if (unwrapped != null) return Result.Existing(unwrapped)
            file.delete()
            deleteKey()
            return Result.Created(createAndStore(file), previousLost = true)
        }
        return Result.Created(createAndStore(file), previousLost = false)
    }

    /** Forgets the passphrase and its Keystore key (used when the database is deliberately destroyed). */
    @Synchronized
    fun reset() {
        AtomicFile(blobFile).delete()
        deleteKey()
    }

    private fun createAndStore(file: AtomicFile): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val blob = wrap(passphrase)
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(blob)
            file.finishWrite(out)
        } catch (e: Exception) {
            if (out != null) file.failWrite(out)
            throw e
        }
        return passphrase
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        // Layout: [version][ivLength][iv][ciphertext+tag]
        return byteArrayOf(BLOB_VERSION, iv.size.toByte()) + iv + sealed
    }

    private fun unwrap(blob: ByteArray): ByteArray? {
        if (blob.size < 2 || blob[0] != BLOB_VERSION) return null
        val ivLength = blob[1].toInt() and 0xFF
        if (blob.size <= 2 + ivLength) return null
        val key = existingKey() ?: return null
        val iv = blob.copyOfRange(2, 2 + ivLength)
        val sealed = blob.copyOfRange(2 + ivLength, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(sealed).takeIf { it.size == PASSPHRASE_BYTES }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = runCatching { keyStore().getKey(KEY_ALIAS, null) as? SecretKey }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deleteKey() {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    companion object {
        const val KEY_ALIAS = "dak_index_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PASSPHRASE_BYTES = 32
        private const val BLOB_VERSION: Byte = 1
        private const val BLOB_FILE = "dak_index_key.bin"

        /**
         * SQLCipher raw-key form (`x'<64 hex>'`): the random bytes are used directly as the key, skipping PBKDF2,
         * so opening the database costs no key derivation.
         */
        fun toSqlCipherKey(passphrase: ByteArray): ByteArray {
            val hex = StringBuilder(passphrase.size * 2 + 3)
            hex.append("x'")
            for (b in passphrase) {
                val v = b.toInt() and 0xFF
                hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            hex.append('\'')
            return hex.toString().toByteArray(Charsets.US_ASCII)
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
