package app.dak.mms.pdu

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuilderAndConstantsTest {

    private fun att(mime: String, name: String) = MmsMessageBuilder.Attachment(mime, name, byteArrayOf(1, 2, 3))

    @Test
    fun `builder layout - smil first, media in order, text last`() {
        val req = MmsMessageBuilder.build(listOf("+15550100"), "hello", listOf(att("image/png", "a.png"), att("video/mp4", "b.mp4")), transactionId = "T1")
        assertEquals(listOf("smil.xml", "a.png", "b.mp4", "text_0.txt"), req.parts.map { it.contentLocation })
        assertEquals(listOf("<smil>", "<a.png>", "<b.mp4>", "<text_0>"), req.parts.map { it.contentId })
        assertEquals(ContentType.MULTIPART_RELATED, req.contentType.mimeType)
        assertEquals("<smil>", req.contentType.start)
        assertEquals(ContentType.SMIL, req.contentType.type)
        assertEquals("hello", req.parts.last().text())
        assertEquals(MmsCharset.UTF_8, req.parts.last().charset)
        assertEquals(false, req.deliveryReport)
        assertEquals(false, req.readReport)
    }

    @Test
    fun `builder drops empty text and blank subject`() {
        val req = MmsMessageBuilder.build(listOf("+1"), "", listOf(att("image/png", "a.png")), subject = "  ", transactionId = "T")
        assertEquals(listOf("smil.xml", "a.png"), req.parts.map { it.contentLocation })
        assertNull(req.subject)
        val textOnly = MmsMessageBuilder.build(listOf("+1"), null, emptyList(), transactionId = "T")
        assertEquals(listOf("smil.xml"), textOnly.parts.map { it.contentLocation })
    }

    @Test
    fun `attachment names are sanitised, case-insensitively unique, and never collide with smil or text`() {
        val req = MmsMessageBuilder.build(
            listOf("+1"), "t",
            listOf(
                att("image/jpeg", "../../etc/passwd"), att("image/jpeg", "C:\\Users\\me\\photo.JPG"), att("image/jpeg", "photo.jpg"),
                att("application/xml", "SMIL.XML"), att("text/plain", "text_0.txt"), att("IMAGE/PNG", "..."),
                att("image/png", "फ़ोटो"), att("application/x-unknown", ""), att("image/gif", "a b<c>|d.gif"),
            ),
            transactionId = "T",
        )
        val names = req.parts.map { it.contentLocation!! }
        assertEquals(
            listOf("smil.xml", "passwd.jpg", "photo.JPG", "photo_1.jpg", "SMIL_1.XML", "text_0_1.txt", "part_5.png", "_____.png", "part_7", "a_b_c__d.gif", "text_0.txt"),
            names,
        )
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size)
        names.forEach { assertTrue(it.all { c -> c.isLetterOrDigit() && c.code < 128 || c in "._-" }, it) }
        assertEquals("image/png", req.parts[6].contentType.mimeType, "mime type normalised to lower case")
    }

    @Test
    fun `long names are capped`() {
        val req = MmsMessageBuilder.build(listOf("+1"), null, listOf(att("image/png", "x".repeat(500) + ".png")), transactionId = "T")
        assertTrue(req.parts[1].contentLocation!!.length <= 68)
    }

    @Test
    fun `transaction ids are printable, prefixed and seed-deterministic`() {
        val a = MmsMessageBuilder.newTransactionId(Random(42), 0x1234)
        assertEquals(a, MmsMessageBuilder.newTransactionId(Random(42), 0x1234))
        assertTrue(a.startsWith("T1234"))
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' || it == 'T' })
    }

    @Test
    fun `response status classification`() {
        assertEquals("OK", ResponseStatus.describe(ResponseStatus.OK))
        for (s in 0xC0..0xDF) assertTrue(ResponseStatus.isTransient(s), "%02X".format(s))
        assertTrue(ResponseStatus.isTransient(ResponseStatus.ERROR_NETWORK_PROBLEM), "legacy 1.0 network problem retries")
        for (s in listOf(ResponseStatus.OK, 0x81, 0x82, 0x83, 0x84, 0x85, 0x87, 0x88, 0xE0, 0xEC, 0xFF)) assertFalse(ResponseStatus.isTransient(s), "%02X".format(s))
        assertEquals("Recipient address not resolved", ResponseStatus.describe(0xE3))
        assertEquals("Recipient address not resolved", ResponseStatus.describe(0xC1))
        assertEquals("Network problem", ResponseStatus.describe(ResponseStatus.ERROR_TRANSIENT_NETWORK_PROBLEM))
        assertEquals("Lack of prepaid credit", ResponseStatus.describe(0xEC))
        assertEquals("Temporary MMSC failure (0xC7)", ResponseStatus.describe(0xC7))
        assertEquals("MMSC error (0xF0)", ResponseStatus.describe(0xF0))
        // Every value has some non-blank description.
        for (s in 0..0xFF) assertTrue(ResponseStatus.describe(s).isNotBlank())
        assertTrue(SendConf(ResponseStatus.OK).isOk)
        assertFalse(SendConf(ResponseStatus.ERROR_PERMANENT_FAILURE).isOk)
    }

    @Test
    fun `retrieve ok only without an error status`() {
        val base = RetrieveConf(ContentType(ContentType.MULTIPART_MIXED), emptyList())
        assertTrue(base.isRetrieveOk)
        assertTrue(base.copy(retrieveStatus = RetrieveStatus.OK).isRetrieveOk)
        assertFalse(base.copy(retrieveStatus = RetrieveStatus.ERROR_TRANSIENT_FAILURE).isRetrieveOk)
    }

    @Test
    fun `mms time resolution and version nibbles`() {
        assertEquals(500, MmsTime.Absolute(500).toEpochSeconds(1_000))
        assertEquals(1_600, MmsTime.Relative(600).toEpochSeconds(1_000))
        assertEquals(1, MmsVersion.major(MmsVersion.V1_3))
        assertEquals(3, MmsVersion.minor(MmsVersion.V1_3))
        assertEquals(MmsVersion.V1_2, MmsVersion.DEFAULT)
    }

    @Test
    fun `part file name falls back through disposition, type and location, and is made safe`() {
        val p = PduPart(ContentType("image/png"), ByteArray(0), contentLocation = "../loc.png")
        assertEquals("../loc.png", p.fileName)
        assertFalse(p.safeFileName!!.contains('/'))
        assertEquals("n.png", p.copy(contentType = ContentType("image/png", name = "n.png")).fileName)
        assertEquals("f.png", p.copy(contentType = ContentType("image/png", name = "n.png", fileName = "f.png")).fileName)
        assertEquals("d.png", p.copy(dispositionFileName = "d.png", contentType = ContentType("image/png", fileName = "f.png")).fileName)
        assertNull(PduPart(ContentType("image/png"), ByteArray(0)).safeFileName)
        assertNull(p.text())
        assertNull(p.text(10))
    }

    @Test
    fun `bounded text never decodes more than it needs`() {
        val big = PduPart.text("é".repeat(100_000))
        assertEquals(10, big.text(10)!!.length)
        assertEquals(100_000, big.text()!!.length)
        assertEquals(PduPart.text("x"), PduPart.text("x"))
        assertEquals(PduPart.text("x").hashCode(), PduPart.text("x").hashCode())
        assertTrue("bytes=1" in PduPart.text("x").toString(), "toString never dumps the payload")
    }
}
