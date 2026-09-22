package app.dak.classify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SenderIdTest {

    @Test
    fun `parses plain DLT header`() {
        val h = SenderId.parseDltHeader("VM-HDFCBK")
        assertEquals(DltHeader("VM", "HDFCBK", null), h)
    }

    @Test
    fun `parses DLT header with traffic type suffix`() {
        assertEquals(DltHeader("AX", "HDFCBK", TrafficType.SERVICE_IMPLICIT), SenderId.parseDltHeader("AX-HDFCBK-S"))
        assertEquals(DltHeader("VK", "AMAZON", TrafficType.PROMOTIONAL), SenderId.parseDltHeader("VK-AMAZON-P"))
        assertEquals(DltHeader("BZ", "SWIGGY", TrafficType.TRANSACTIONAL), SenderId.parseDltHeader("BZ-SWIGGY-T"))
    }

    @Test
    fun `lowercase input still parses`() {
        assertEquals(DltHeader("JD", "HDFCBK", null), SenderId.parseDltHeader("jd-hdfcbk"))
    }

    @Test
    fun `rejects non-header strings`() {
        assertNull(SenderId.parseDltHeader("9876543210"))
        assertNull(SenderId.parseDltHeader("HDFCBK"))
        assertNull(SenderId.parseDltHeader("A-B-C-D"))
        assertNull(SenderId.parseDltHeader("VM-12345"))
    }

    @Test
    fun `classify recognises kinds`() {
        assertEquals(SenderKind.DLT_HEADER, SenderId.classify("VM-HDFCBK"))
        assertEquals(SenderKind.SHORT_CODE, SenderId.classify("56070"))
        assertEquals(SenderKind.PHONE_NUMBER, SenderId.classify("+919876543210"))
        assertEquals(SenderKind.PHONE_NUMBER, SenderId.classify("9876543210"))
        assertEquals(SenderKind.ALPHANUMERIC, SenderId.classify("MyFriend"))
    }

    @Test
    fun `mergeKey collapses DLT prefixes for the same entity`() {
        val key = "HDFCBK"
        assertEquals(key, SenderId.mergeKey("VM-HDFCBK"))
        assertEquals(key, SenderId.mergeKey("JD-HDFCBK"))
        assertEquals(key, SenderId.mergeKey("AX-HDFCBK"))
        assertEquals(key, SenderId.mergeKey("AX-HDFCBK-S"))
    }

    @Test
    fun `mergeKey collapses numeric senders on last 10 digits`() {
        assertEquals("9876543210", SenderId.mergeKey("9876543210"))
        assertEquals("9876543210", SenderId.mergeKey("+919876543210"))
        assertEquals("9876543210", SenderId.mergeKey("09876543210"))
    }

    @Test
    fun `mergeKey falls back to trimmed uppercase for anything else`() {
        assertEquals("MYFRIEND", SenderId.mergeKey("MyFriend"))
    }

    @Test
    fun `isIndianMobile detects 10 digit numbers with and without prefixes`() {
        assert(SenderId.isIndianMobile("9876543210"))
        assert(SenderId.isIndianMobile("+919876543210"))
        assert(SenderId.isIndianMobile("09876543210"))
        assert(!SenderId.isIndianMobile("56070"))
        assert(!SenderId.isIndianMobile("VM-HDFCBK"))
        assert(!SenderId.isIndianMobile("1234567890")) // does not start 6-9
    }
}
