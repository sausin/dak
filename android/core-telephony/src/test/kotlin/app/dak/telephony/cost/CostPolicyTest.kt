package app.dak.telephony.cost

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CostPolicyTest {

    @Test
    fun approvalKeysArePerSimAndSpellingInsensitive() {
        assertEquals("1|56161", CostPolicy.approvalKey(" 56161 ", 1))
        assertEquals("1|+919812345678", CostPolicy.approvalKey("+91 98123-45678", 1))
        assertEquals("2|VMHDFCBK", CostPolicy.approvalKey("vm-hdfcbk", 2))
        assertTrue(CostPolicy.approvalKey("56161", 1) != CostPolicy.approvalKey("56161", 2))
    }

    @Test
    fun toConfirmSkipsQuietAndApprovedAndSortsLoudestFirst() {
        val verdicts = listOf(
            CostVerdict("+971501234567", CostKind.INTERNATIONAL, "AE"),
            CostVerdict("9812345678", CostKind.NORMAL),
            CostVerdict("56161", CostKind.PREMIUM_RATE),
            CostVerdict("1909", CostKind.TOLL_FREE),
            CostVerdict("51969", CostKind.UNKNOWN_SHORT_CODE),
        )
        val result = CostPolicy.toConfirm(verdicts, subId = 3, approvedKeys = setOf("3|51969"))
        assertEquals(listOf(CostKind.PREMIUM_RATE, CostKind.INTERNATIONAL), result.map { it.kind })
    }

    @Test
    fun roamingCanBeExcluded() {
        val verdicts = listOf(CostVerdict("9812345678", CostKind.ROAMING, roaming = true))
        assertEquals(1, CostPolicy.toConfirm(verdicts, 1, emptySet()).size)
        assertTrue(CostPolicy.toConfirm(verdicts, 1, emptySet(), warnRoaming = false).isEmpty())
    }

    @Test
    fun unattendedSendsRefusePremiumUnlessApproved() {
        val premium = CostVerdict("56161", CostKind.PREMIUM_RATE)
        assertFalse(CostPolicy.allowUnattended(premium, 1, emptySet()))
        assertFalse(CostPolicy.allowUnattended(premium, 1, setOf("2|56161")))
        assertTrue(CostPolicy.allowUnattended(premium, 1, setOf("1|56161")))
        assertTrue(CostPolicy.allowUnattended(CostVerdict("+971501234567", CostKind.INTERNATIONAL), 1, emptySet()))
    }
}
