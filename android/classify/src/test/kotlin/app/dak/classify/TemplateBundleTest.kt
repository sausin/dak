package app.dak.classify

import app.dak.core.model.Category
import java.security.KeyPairGenerator
import java.security.Signature
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateBundleTest {

    @Test
    fun `loads the bundled default template resource`() {
        val bundle = TemplateBundle.loadDefault()
        assertTrue(bundle.rules.isNotEmpty())
        assertEquals("HDFC Bank", bundle.sender("HDFCBK")?.brand)
        assertEquals("Amazon", bundle.sender("AMAZON")?.brand)
        assertEquals("Swiggy", bundle.sender("SWIGGY")?.brand)
    }

    @Test
    fun `default rules are sorted by descending priority`() {
        val bundle = TemplateBundle.loadDefault()
        val priorities = bundle.rules.map { it.priority }
        assertEquals(priorities.sortedDescending(), priorities)
    }

    @Test
    fun `rulesFor filters by sender header when the rule declares one`() {
        val payload = """
            {"version":1,"issuedAt":0,"senders":[],"rules":[
              {"id":"generic","category":"SPAM","pattern":"x","priority":1},
              {"id":"scoped","category":"OTP","pattern":"y","priority":1,"senderHeaders":["HDFCBK"]}
            ]}
        """.trimIndent()
        val bundle = TemplateBundle.parseUnsigned(payload)
        assertEquals(listOf("generic", "scoped"), bundle.rulesFor("HDFCBK").map { it.id }.sorted())
        assertEquals(listOf("generic"), bundle.rulesFor("ICICIB").map { it.id })
        assertEquals(listOf("generic"), bundle.rulesFor(null).map { it.id })
    }

    @Test
    fun `parse verifies signature and rejects a bad one`() {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = kpg.generateKeyPair()
        val rawPublicKey = keyPair.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }
        val verifier = Ed25519BundleVerifier(rawPublicKey)

        val payload = app.dak.classify.TemplatePayload(
            version = 2,
            issuedAt = 123L,
            senders = listOf(SenderEntry("FOO", "Foo Inc", Category.TRANSACTION, 70)),
            rules = listOf(TemplateRule("r1", Category.SPAM, "foo", priority = 5)),
        )
        val canonicalJson = Json.encodeToString(app.dak.classify.TemplatePayload.serializer(), payload)

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(canonicalJson.toByteArray(Charsets.UTF_8))
        val signature = BundleCodec.encode(signer.sign())

        val signedJson = Json.encodeToString(
            app.dak.classify.SignedTemplateBundle.serializer(),
            app.dak.classify.SignedTemplateBundle(payload, signature),
        )

        val bundle = TemplateBundle.parse(signedJson, verifier)
        assertNotNull(bundle)
        assertEquals("Foo Inc", bundle.sender("FOO")?.brand)

        val tamperedJson = signedJson.replace("Foo Inc", "Evil Inc")
        assertNull(TemplateBundle.parse(tamperedJson, verifier))
        assertNull(TemplateBundle.parse(signedJson, RejectAllVerifier))
    }

    @Test
    fun `parse rejects a bundle with no signature`() {
        val json = """{"payload":{"version":1,"issuedAt":0,"senders":[],"rules":[]}}"""
        assertNull(TemplateBundle.parse(json, RejectAllVerifier))
    }
}
