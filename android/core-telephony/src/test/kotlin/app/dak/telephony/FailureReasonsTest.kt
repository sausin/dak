package app.dak.telephony

import app.dak.telephony.mms.MmsResultCodes
import app.dak.telephony.sms.SmsResultCodes
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Failure reasons are stored as language-neutral codes and shown via string resources (docs/i18n.md). English must
 * read exactly as before the change, and every code must have a resource with that English text.
 */
class FailureReasonsTest {

    @Test
    fun `english text is unchanged from the pre-i18n strings`() {
        // Literal copies of what SmsResultCodes / MmsResultCodes / the senders returned before reasons became codes.
        assertEquals("Sent", SmsResultCodes.describe(SmsResultCodes.RESULT_OK))
        assertEquals("Mobile radio is off (airplane mode or the other SIM is busy)", SmsResultCodes.describe(SmsResultCodes.RADIO_OFF))
        assertEquals("Temporary phone error", SmsResultCodes.describe(SmsResultCodes.MODEM_ERROR))
        assertEquals("Sending failed (code 999)", SmsResultCodes.describe(999))
        assertEquals(
            "Only 2 of 3 parts were sent. Retrying sends the whole message again, so the recipient may see some of it twice",
            SmsResultCodes.describePartial(2, 3),
        )
        assertEquals("MMS failed", MmsResultCodes.describe(MmsResultCodes.UNSPECIFIED))
        assertEquals("MMS failed (code 77)", MmsResultCodes.describe(77))
        assertEquals("The carrier's MMS server returned an error (HTTP 404)", MmsResultCodes.describe(MmsResultCodes.HTTP_FAILURE, 404))
        assertEquals("No mobile data connection for MMS", MmsResultCodes.describe(MmsResultCodes.NO_DATA_NETWORK, 0))
        assertEquals("Too large for this carrier: 512 KB (limit 300 KB)", FailureReasons.english(FailureReasons.encode(Failure.MMS_TOO_LARGE_FOR_CARRIER, 512, 300)))
        assertEquals("Could not save the message; is Dak the default SMS app?", Failure.SAVE_FAILED_NOT_DEFAULT.english)
    }

    @Test
    fun `english digits stay ascii whatever the default locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("Sending failed (code 999)", SmsResultCodes.describe(999))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `codes round trip, legacy text is left alone`() {
        val cases = listOf(
            FailureReason(Failure.MESSAGE_EMPTY),
            SmsResultCodes.failureOf(SmsResultCodes.NO_SERVICE),
            SmsResultCodes.failureOf(4242),
            SmsResultCodes.partial(1, 4),
            MmsResultCodes.failureOf(MmsResultCodes.HTTP_FAILURE, 503),
            MmsResultCodes.failureOf(99, null),
            FailureReason(Failure.MMS_UNREADABLE, listOf("bad header | at 12")),
        )
        for (c in cases) assertEquals(c, FailureReasons.decode(c.encode()), c.encode())
        assertNull(FailureReasons.decode("No mobile service"))
        assertNull(FailureReasons.decode(null))
        assertNull(FailureReasons.decode("dak-fail:NOT_A_FAILURE"))
        assertNull(FailureReasons.decode("dak-fail:SMS_PARTIAL|1"))
        assertEquals("Sending was interrupted; tap to retry", FailureReasons.english("Sending was interrupted; tap to retry"))
        assertEquals("dak-fail:SMS_NO_SERVICE", SmsResultCodes.failureOf(SmsResultCodes.NO_SERVICE).encode())
    }

    @Test
    fun `every failure has a resource with its english text`() {
        val xml = readStrings(File("src/main/res/values/strings_failures.xml"))
        Failure.entries.forEach { f ->
            assertEquals(f.english, xml[f.resourceName], f.name)
            val field = checkNotNull(runCatching { R.string::class.java.getField(f.resourceName) }.getOrNull()) { "no R.string.${f.resourceName}" }
            assertEquals(field.getInt(null), FailureReasonText.stringRes(f), f.name)
        }
        assertTrue(xml.keys.filter { it.startsWith("dak_telephony_fail_") }.all { name -> Failure.entries.any { it.resourceName == name } })
    }

    private fun readStrings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
    }
}
