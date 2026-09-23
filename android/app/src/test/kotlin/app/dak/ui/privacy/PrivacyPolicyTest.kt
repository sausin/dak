package app.dak.ui.privacy

import app.dak.classify.CloudClassifier
import app.dak.classify.CloudVerdict
import app.dak.core.model.Category
import app.dak.premium.consent.ConsentLedger
import app.dak.premium.consent.DataFlow
import app.dak.premium.consent.InMemoryConsentStorage
import app.dak.settings.DakSettings
import app.dak.settings.InMemorySettingsStore
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivacyPolicyTest {

    private fun policyAsset(): File = listOf(File("src/main/assets/privacy-policy.md"), File("app/src/main/assets/privacy-policy.md"))
        .first { it.exists() }

    private fun policyDoc(): File? = listOf(File("../../docs/privacy-policy.md"), File("../docs/privacy-policy.md"))
        .firstOrNull { it.exists() }

    @Test
    fun `the bundled policy is identical to docs-privacy-policy`() {
        val doc = policyDoc() ?: return // docs/ not present in this checkout layout
        assertEquals(doc.readText(), policyAsset().readText(), "copy docs/privacy-policy.md to app/src/main/assets/")
    }

    @Test
    fun `the bundled policy parses into headings, bullets and paragraphs and links the hosted copy`() {
        val blocks = PolicyMarkdown.parse(policyAsset().readText())
        val headings = blocks.filterIsInstance<PolicyMarkdown.Block.Heading>()
        assertEquals(1, headings.first().level)
        val titles = headings.map { it.text }
        for (required in listOf("What leaves your phone", "Permissions and why Dak asks for them", "How long data is kept", "Your rights and choices", "Children")) {
            assertTrue(titles.any { it.startsWith(required) }, "missing section $required in $titles")
        }
        assertTrue(blocks.any { it is PolicyMarkdown.Block.Bullet })
        val links = blocks.flatMap { b ->
            val text = when (b) {
                is PolicyMarkdown.Block.Heading -> b.text
                is PolicyMarkdown.Block.Paragraph -> b.text
                is PolicyMarkdown.Block.Bullet -> b.text
            }
            PolicyMarkdown.spans(text).mapNotNull { it.url }
        }
        assertTrue(HOSTED_PRIVACY_POLICY_URL in links, "policy should link $HOSTED_PRIVACY_POLICY_URL, found $links")
    }

    @Test
    fun `markdown subset - headings, bullets with continuation, paragraphs, comments`() {
        val md = """
            <!-- note -->
            # Title

            First line
            continues here.

            - one
              more
            - **two**
            ### Small
        """.trimIndent()
        val blocks = PolicyMarkdown.parse(md)
        assertEquals(
            listOf(
                PolicyMarkdown.Block.Heading(1, "Title"),
                PolicyMarkdown.Block.Paragraph("First line continues here."),
                PolicyMarkdown.Block.Bullet("one more"),
                PolicyMarkdown.Block.Bullet("**two**"),
                PolicyMarkdown.Block.Heading(3, "Small"),
            ),
            blocks,
        )
    }

    @Test
    fun `inline spans - bold, https links only, unclosed markers stay literal`() {
        assertEquals(
            listOf(PolicyMarkdown.Span("a "), PolicyMarkdown.Span("b", bold = true), PolicyMarkdown.Span(" c")),
            PolicyMarkdown.spans("a **b** c"),
        )
        assertEquals(listOf(PolicyMarkdown.Span("x", url = "https://e.example/p")), PolicyMarkdown.spans("[x](https://e.example/p)"))
        // Non-https schemes are not turned into links.
        assertEquals(listOf(PolicyMarkdown.Span("[x](javascript:alert)")), PolicyMarkdown.spans("[x](javascript:alert)"))
        assertEquals(listOf(PolicyMarkdown.Span("a **b")), PolicyMarkdown.spans("a **b"))
        assertEquals(listOf(PolicyMarkdown.Span("code")), PolicyMarkdown.spans("`code`"))
    }

    @Test
    fun `cloud classifier runs only with both the Jev switch and a current consent`() = runTest {
        var calls = 0
        val real = object : CloudClassifier {
            override suspend fun classify(senderHeader: String?, maskedBody: String): CloudVerdict {
                calls++
                return CloudVerdict(Category.SPAM, 0.9f)
            }
        }
        val settings = InMemorySettingsStore()
        val ledger = ConsentLedger(InMemoryConsentStorage())
        val gated = ConsentGatedCloudClassifier(real, ledger, settings)

        assertNull(gated.classify("VM-TEST", "<NUM>"))
        settings.set(DakSettings.jevOptIn, true) // e.g. restored from a settings file: still no consent
        assertNull(gated.classify("VM-TEST", "<NUM>"))
        ledger.grant(DataFlow.CLOUD_CLASSIFICATION, "test")
        assertEquals(Category.SPAM, gated.classify("VM-TEST", "<NUM>")?.category)
        ledger.withdraw(DataFlow.CLOUD_CLASSIFICATION, "test")
        assertNull(gated.classify("VM-TEST", "<NUM>"))
        assertEquals(1, calls)
    }
}
