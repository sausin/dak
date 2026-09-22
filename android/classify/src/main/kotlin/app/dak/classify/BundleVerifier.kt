package app.dak.classify

import java.security.KeyFactory
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies the signature on an OTA template bundle before it is trusted. The bundle that ships in
 * the APK ([TemplateBundle.loadDefault]) is unsigned-trusted and never goes through this; any
 * bundle fetched over the network must verify.
 */
public fun interface BundleVerifier {
    /**
     * Returns true if [signature] is a valid signature over [payload] (the exact bytes of the
     * bundle's canonical, unsigned JSON payload).
     */
    public fun verify(payload: ByteArray, signature: ByteArray): Boolean
}

/** Always rejects. Useful as a safe default when no verifier is configured. */
public object RejectAllVerifier : BundleVerifier {
    override fun verify(payload: ByteArray, signature: ByteArray): Boolean = false
}

/**
 * Ed25519 signature verification using `java.security` (available on the JDK since 15, and on
 * Android from API 33). If the running JVM/Android runtime lacks the `Ed25519` algorithm, this
 * verifier fails closed (returns false for everything) rather than throwing, so callers can treat
 * "cannot verify" the same as "verification failed".
 *
 * [publicKeyBytes] is the raw 32-byte Ed25519 public key (not PEM/DER wrapped); it is wrapped in an
 * X.509 `SubjectPublicKeyInfo` here since that's what `java.security` requires.
 */
public class Ed25519BundleVerifier(private val publicKeyBytes: ByteArray) : BundleVerifier {

    init {
        require(publicKeyBytes.size == 32) { "Ed25519 public key must be 32 bytes, was ${publicKeyBytes.size}" }
    }

    override fun verify(payload: ByteArray, signature: ByteArray): Boolean {
        val key = publicKey() ?: return false
        return try {
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(key)
            sig.update(payload)
            sig.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun publicKey(): PublicKey? {
        return try {
            val spki = ed25519Spki(publicKeyBytes)
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        } catch (_: NoSuchAlgorithmException) {
            null // JDK/runtime without Ed25519 support: fail closed.
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        // Fixed 12-byte X.509 SubjectPublicKeyInfo header for a raw Ed25519 (OID 1.3.101.112) key,
        // followed by the 32 raw key bytes. This is the standard, algorithm-parameter-free encoding.
        val SPKI_PREFIX: ByteArray = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )

        fun ed25519Spki(rawKey: ByteArray): ByteArray = SPKI_PREFIX + rawKey
    }
}

/** Base64 helpers shared by bundle signing/verification code. */
public object BundleCodec {
    public fun decode(base64: String): ByteArray = Base64.getDecoder().decode(base64)
    public fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
