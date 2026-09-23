package app.dak.safety

import app.dak.safety.helplines.HelplineBundle
import app.dak.safety.helplines.HelplineCategory
import app.dak.safety.helplines.RegionalHelplines
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Helplines follow the region profile: India's verified set in India, none invented anywhere else. */
class RegionalHelplinesTest {
    private val repoRoot: File = generateSequence(File(System.getProperty("dak.repoRoot") ?: System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "shared/formats/helplines-v1.json").exists() }
    private val payload = assertNotNull(HelplineBundle.parseBundled(File(repoRoot, "android/app/src/main/assets/helplines-v1.json").readText()))

    @Test
    fun indiaGetsItsVerifiedSetIncludingTraiAndEmergency() {
        val india = RegionalHelplines.forCountry(payload, "in")
        assertTrue(india.isNotEmpty())
        assertTrue(india.all { it.country == "IN" })
        assertTrue(india.any { it.category == HelplineCategory.SPAM && it.target == "1909" })
        assertTrue(RegionalHelplines.hasEmergency(india))
    }

    @Test
    fun otherAndUnknownRegionsGetNoOtherCountrysLines() {
        for (country in listOf("US", "GB", "DE", "AE", "SG")) {
            assertEquals(emptyList(), RegionalHelplines.forCountry(payload, country), country)
        }
        assertEquals(emptyList(), RegionalHelplines.forCountry(payload, null))
        assertEquals(emptyList(), RegionalHelplines.forCountry(payload, ""))
    }
}
