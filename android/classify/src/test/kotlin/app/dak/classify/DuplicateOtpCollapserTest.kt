package app.dak.classify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicateOtpCollapserTest {

    private val collapser = DuplicateOtpCollapser(windowMinutes = 5)

    @Test
    fun `identical body from same merge key within window is a duplicate`() {
        val recent = listOf(RecentMessage("VM-HDFCBK", "123456 is your OTP", timeMillis = 0))
        assertTrue(collapser.isDuplicate("JD-HDFCBK", "123456 is your OTP", nowMillis = 60_000, recent = recent))
    }

    @Test
    fun `different body is not a duplicate`() {
        val recent = listOf(RecentMessage("VM-HDFCBK", "123456 is your OTP", timeMillis = 0))
        assertFalse(collapser.isDuplicate("VM-HDFCBK", "654321 is your OTP", nowMillis = 60_000, recent = recent))
    }

    @Test
    fun `different sender is not a duplicate`() {
        val recent = listOf(RecentMessage("VM-ICICIB", "123456 is your OTP", timeMillis = 0))
        assertFalse(collapser.isDuplicate("VM-HDFCBK", "123456 is your OTP", nowMillis = 60_000, recent = recent))
    }

    @Test
    fun `outside the window is not a duplicate`() {
        val recent = listOf(RecentMessage("VM-HDFCBK", "123456 is your OTP", timeMillis = 0))
        assertFalse(collapser.isDuplicate("VM-HDFCBK", "123456 is your OTP", nowMillis = 6 * 60_000L, recent = recent))
    }

    @Test
    fun `future message is not treated as duplicating a later one`() {
        val recent = listOf(RecentMessage("VM-HDFCBK", "123456 is your OTP", timeMillis = 100_000))
        assertFalse(collapser.isDuplicate("VM-HDFCBK", "123456 is your OTP", nowMillis = 0, recent = recent))
    }
}
