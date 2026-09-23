package app.dak.safety

import app.dak.classify.BundleCodec
import app.dak.classify.Ed25519BundleVerifier
import app.dak.classify.RejectAllVerifier
import app.dak.safety.helplines.HelplineAction
import app.dak.safety.helplines.HelplineBundle
import app.dak.safety.helplines.HelplinePayload
import app.dak.safety.helplines.SignedHelplineBundle
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelplineBundleTest {
    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "shared/formats/helplines-v1.json").exists() }
    private val shared = File(repoRoot, "shared/formats/helplines-v1.json").readText()
    private val asset = File(repoRoot, "android/app/src/main/assets/helplines-v1.json").readText()

    @Test
    fun assetIsIdenticalToTheSharedFormatCopy() {
        assertEquals(shared, asset, "app/src/main/assets/helplines-v1.json must match shared/formats/helplines-v1.json")
    }

    @Test
    fun bundledCopyParsesAndIsWellFormed() {
        val payload = assertNotNull(HelplineBundle.parseBundled(asset))
        assertEquals(HelplineBundle.FORMAT, payload.format)
        assertTrue(payload.helplines.isNotEmpty())
        assertEquals(payload.helplines.size, payload.helplines.map { it.id }.toSet().size, "duplicate ids")
        assertTrue(payload.bankCardBlock.isEmpty(), "bank numbers must not ship until verified on the bank's own site")
        for (h in payload.helplines) {
            assertEquals("IN", h.country)
            val source = URI(h.sourceUrl)
            assertEquals("https", source.scheme, h.id)
            assertTrue(source.host.endsWith(".gov.in") || source.host.endsWith(".rbi.org.in") || source.host == "trai.gov.in", "${h.id}: ${h.sourceUrl}")
            when (h.action) {
                HelplineAction.URL -> assertEquals("https", URI(h.target).scheme, h.id)
                HelplineAction.CALL, HelplineAction.SMS -> assertTrue(h.target.all { it.isDigit() }, h.id)
            }
            assertTrue(h.needsVerification || h.lastVerified != null, "${h.id}: verified entries need a date")
            if (h.needsVerification) assertNotNull(h.verificationNote, "${h.id}: say what to verify")
        }
        val trai = payload.helplines.first { it.action == HelplineAction.SMS && it.target == "1909" }
        assertNotNull(trai.smsFormat)
        assertTrue(payload.helplines.any { it.target == "1930" && it.action == HelplineAction.CALL })
    }

    @Test
    fun otaCopiesMustBeSigned() {
        assertNull(HelplineBundle.parseSigned(asset, RejectAllVerifier), "unsigned bundled copy is not a valid OTA update")
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val rawPublic = keys.public.encoded.takeLast(32).toByteArray()
        val verifier = Ed25519BundleVerifier(rawPublic)
        val payload = HelplineBundle.parseBundled(asset)!!.copy(revision = 2)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(HelplineBundle.canonicalPayload(payload).toByteArray(Charsets.UTF_8))
            sign()
        }
        val json = Json { encodeDefaults = true }
        val signed = json.encodeToString(SignedHelplineBundle.serializer(), SignedHelplineBundle(payload, BundleCodec.encode(signature)))
        assertEquals(2, HelplineBundle.parseSigned(signed, verifier)?.revision)

        val tampered = json.encodeToString(
            SignedHelplineBundle.serializer(),
            SignedHelplineBundle(payload.copy(helplines = payload.helplines.map { it.copy(target = it.target.replace("1930", "1931")) }), BundleCodec.encode(signature)),
        )
        assertNull(HelplineBundle.parseSigned(tampered, verifier))
        assertNull(HelplineBundle.parseSigned(signed, RejectAllVerifier))
    }

    @Test
    fun otherFormatsAreRejected() {
        val other = HelplinePayload(format = "something-else", version = 1, revision = 1, issuedAt = "2026-01-01")
        val json = Json.encodeToString(SignedHelplineBundle.serializer(), SignedHelplineBundle(other))
        assertNull(HelplineBundle.parseBundled(json))
        assertNull(HelplineBundle.parseBundled("not json"))
    }
}
