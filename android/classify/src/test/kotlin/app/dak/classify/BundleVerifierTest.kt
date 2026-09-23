package app.dak.classify

import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundleVerifierTest {

    private fun rawEd25519PublicKeyBytes(publicKey: java.security.PublicKey): ByteArray {
        // X.509 SubjectPublicKeyInfo encoding for Ed25519 is a fixed 12-byte header + 32 raw bytes.
        val encoded = publicKey.encoded
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    @Test
    fun `verifies a genuine Ed25519 signature`() {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = kpg.generateKeyPair()
        val payload = "{\"version\":1}".toByteArray(Charsets.UTF_8)

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(payload)
        val signature = signer.sign()

        val verifier = Ed25519BundleVerifier(rawEd25519PublicKeyBytes(keyPair.public))
        assertTrue(verifier.verify(payload, signature))
    }

    @Test
    fun `rejects a tampered payload`() {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = kpg.generateKeyPair()
        val payload = "{\"version\":1}".toByteArray(Charsets.UTF_8)

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(payload)
        val signature = signer.sign()

        val verifier = Ed25519BundleVerifier(rawEd25519PublicKeyBytes(keyPair.public))
        val tampered = "{\"version\":2}".toByteArray(Charsets.UTF_8)
        assertFalse(verifier.verify(tampered, signature))
    }

    @Test
    fun `rejects a signature from a different key`() {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val signerKeyPair = kpg.generateKeyPair()
        val otherKeyPair = kpg.generateKeyPair()
        val payload = "hello".toByteArray(Charsets.UTF_8)

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(signerKeyPair.private)
        signer.update(payload)
        val signature = signer.sign()

        val verifier = Ed25519BundleVerifier(rawEd25519PublicKeyBytes(otherKeyPair.public))
        assertFalse(verifier.verify(payload, signature))
    }

    @Test
    fun `RejectAllVerifier always fails`() {
        assertFalse(RejectAllVerifier.verify(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun `bad key length is rejected at construction`() {
        var threw = false
        try {
            Ed25519BundleVerifier(ByteArray(10))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
