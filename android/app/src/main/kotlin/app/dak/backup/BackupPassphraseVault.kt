package app.dak.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the backup passphrase on this device only, encrypted with a non-exportable Android Keystore key, so the
 * scheduled backup can run unattended. The passphrase never leaves the phone; backups are encrypted before they
 * reach the user's folder or cloud provider.
 */
@Singleton
class BackupPassphraseVault @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPassphrase(): Boolean = prefs.contains(KEY_CIPHERTEXT)

    fun store(passphrase: CharArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = toBytes(passphrase)
        val sealed = cipher.doFinal(plain)
        plain.fill(0)
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .apply()
    }

    /** The stored passphrase, or null when none is set or the key was invalidated. Caller should wipe it. */
    fun load(): CharArray? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val sealed = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val plain = cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP))
            val chars = Charsets.UTF_8.decode(ByteBuffer.wrap(plain))
            val out = CharArray(chars.remaining())
            chars.get(out)
            plain.fill(0)
            out
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun toBytes(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private companion object {
        const val PREFS = "dak_backup_vault"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dak_backup_passphrase"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
