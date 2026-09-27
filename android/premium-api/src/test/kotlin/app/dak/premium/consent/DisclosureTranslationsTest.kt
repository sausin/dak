package app.dak.premium.consent

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** How a disclosure is picked for a language, and when a bundled translation is refused in favour of the English text. */
class DisclosureTranslationsTest {

    @Test
    fun `each shipped language has a current translation of every flow`() {
        for (lang in listOf("hi", "es", "fr")) {
            for (flow in DataFlow.entries) {
                val t = Disclosures.forFlow(flow, lang)
                assertEquals(lang, t.language, "$flow in $lang")
                assertEquals(Disclosures.forFlow(flow).version, t.version, "$flow in $lang")
            }
        }
    }

    @Test
    fun `regional and underscore tags use the language's translation`() {
        assertEquals("fr", Disclosures.forFlow(DataFlow.WEBHOOKS, "fr-CA").language)
        assertEquals("hi", Disclosures.forFlow(DataFlow.WEBHOOKS, "hi_IN").language)
        assertEquals("es", Disclosures.normalizeLanguage(" ES-mx "))
        assertEquals(Disclosures.SOURCE_LANGUAGE, Disclosures.normalizeLanguage(""))
    }

    @Test
    fun `english and languages without a translation show the source text`() {
        assertNull(Disclosures.translation(DataFlow.AI_SEARCH, "en-GB"))
        assertSame(Disclosures.aiSearch, Disclosures.forFlow(DataFlow.AI_SEARCH, "en"))
        assertNull(Disclosures.translation(DataFlow.AI_SEARCH, "de"))
        assertSame(Disclosures.aiSearch, Disclosures.forFlow(DataFlow.AI_SEARCH, "de"))
        assertTrue(Disclosures.translations("de").isEmpty())
    }

    @Test
    fun `an equivalent earlier text only counts for the version it was declared against`() {
        val e = Disclosures.EQUIVALENT_EARLIER.first { it.flow == DataFlow.WEBHOOKS }
        assertTrue(Disclosures.isEquivalentEarlier(e.flow, e.version, e.hash, e.sameAsVersion))
        assertFalse(Disclosures.isEquivalentEarlier(e.flow, e.version, e.hash, e.sameAsVersion + 1))
        assertFalse(Disclosures.isEquivalentEarlier(e.flow, e.version, "0".repeat(64), e.sameAsVersion))
        assertFalse(Disclosures.isEquivalentEarlier(DataFlow.AI_SEARCH, e.version, e.hash, e.sameAsVersion))
    }

    private fun entry(
        flow: String = "webhooks",
        whatIsSent: List<String> = listOf("the message"),
        title: String = "Titre",
    ) = TranslatedDisclosure(
        flow = flow,
        version = 2,
        title = title,
        whatIsSent = whatIsSent,
        sentTo = "à vous",
        why = "parce que",
        whenSent = "maintenant",
        retention = "jamais",
        howToTurnOff = "Paramètres",
    )

    @Test
    fun `an incomplete or unknown translation entry is dropped`() {
        assertEquals("fr", entry().toDisclosure("fr")?.language)
        assertNull(entry(flow = "telepathy").toDisclosure("fr"))
        assertNull(entry(whatIsSent = emptyList()).toDisclosure("fr"))
        assertNull(entry(title = " ").toDisclosure("fr"))
    }

    @Test
    fun `translation files round-trip through their serialized form`() {
        val file = TranslationFile(language = "fr", disclosures = listOf(entry()))
        val json = Json.encodeToString(TranslationFile.serializer(), file)
        assertEquals(file, Json.decodeFromString(TranslationFile.serializer(), json))
    }

    @Test
    fun `the ledger shows the source text for the source language and a matching translation otherwise`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), { 0L }, displayLanguage = { "fr" })
        assertEquals("fr", ledger.disclosure(DataFlow.WEB_RELAY).language)
        assertSame(Disclosures.webRelay, ledger.disclosure(DataFlow.WEB_RELAY, "en"))

        // A translation for the wrong flow or another version is never shown.
        val wrongFlow = ConsentLedger(InMemoryConsentStorage(), { 0L }, translations = { _, _ -> Disclosures.forFlow(DataFlow.AI_SEARCH, "fr") })
        assertSame(Disclosures.webRelay, wrongFlow.disclosure(DataFlow.WEB_RELAY, "fr"))
        val stale = Disclosures.forFlow(DataFlow.WEB_RELAY, "fr").copy(version = 1)
        val oldVersion = ConsentLedger(InMemoryConsentStorage(), { 0L }, translations = { _, _ -> stale })
        assertSame(Disclosures.webRelay, oldVersion.disclosure(DataFlow.WEB_RELAY, "fr"))
    }

    @Test
    fun `a record without a language is an English record`() {
        val r = ConsentRecord(flow = "webhooks", granted = true, atMillis = 1, disclosureVersion = 1, disclosureHash = "h", source = "s")
        assertNull(r.language)
    }
}
