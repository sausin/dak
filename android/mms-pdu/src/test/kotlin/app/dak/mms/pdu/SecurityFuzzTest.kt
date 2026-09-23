package app.dak.mms.pdu

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Red-team tests: hostile PDUs (anyone can send a WAP push; the retrieved message comes from wherever the
 * notification pointed) must never crash, hang or blow up memory, and values that leave the codec must be safe.
 */
class SecurityFuzzTest {

    /** Decodes and fails the test on an internal error (unexpected exception mapped to offset -1). */
    private fun decodeNoBug(bytes: ByteArray): PduDecodeResult {
        val result = MmsPduDecoder.decode(bytes)
        if (result is PduDecodeResult.Failure) {
            val e = result.error
            if (e is PduError.Malformed && e.offset == -1) throw AssertionError("internal decoder error for ${hex(bytes.copyOf(minOf(64, bytes.size)))}: ${e.detail}")
        }
        return result
    }

    private val seeds: List<ByteArray> by lazy {
        listOf(
            MmsPduEncoder.encode(
                MmsMessageBuilder.build(
                    to = listOf("+15551234567", "+15557654321"),
                    text = "Hello ✓ ünïcode",
                    attachments = listOf(
                        MmsMessageBuilder.Attachment("image/png", "a.png", ByteArray(48) { it.toByte() }),
                        MmsMessageBuilder.Attachment("audio/amr", "b.amr", ByteArray(20) { 3 }),
                    ),
                    subject = "Sübject",
                    transactionId = "T1",
                ),
            ),
            MmsPduEncoder.encode(
                NotificationInd(contentLocation = "http://mmsc/x?id=1", transactionId = "t", from = "+1555", messageSize = 1234, expiry = MmsTime.Relative(3600)),
            ),
            MmsPduEncoder.encode(
                RetrieveConf(
                    contentType = ContentType.multipartRelated(start = "<smil>"),
                    parts = listOf(
                        PduPart.text("hi", contentId = "<t>", contentLocation = "t.txt"),
                        PduPart(ContentType("image/jpeg", name = "p.jpg"), ByteArray(30) { 9 }, contentLocation = "p.jpg", contentDisposition = "attachment", dispositionFileName = "p.jpg"),
                    ),
                    from = "+15550001111",
                    to = listOf("+15550002222"),
                    subject = "s",
                ),
            ),
            MmsPduEncoder.encode(SendConf(responseStatus = ResponseStatus.OK, transactionId = "x", messageId = "m")),
            MmsPduEncoder.encode(DeliveryInd(messageId = "m", status = MmsStatus.RETRIEVED, to = listOf("+1"))),
        )
    }

    /** Deterministic structure-aware mutation fuzzing: ≥100k iterations under a wall-clock cap. */
    @Test
    fun seededMutationFuzz() {
        val random = Random(0xDA_C0DE)
        val iterations = 120_000
        val deadline = System.nanoTime() + 60_000_000_000L // generous cap for slow CI machines
        var ran = 0
        while (ran < iterations && System.nanoTime() < deadline) {
            val seed = seeds[random.nextInt(seeds.size)]
            decodeNoBug(mutate(seed, random))
            ran++
        }
        assertTrue(ran >= 100_000, "only $ran iterations ran before the time cap: decoder is too slow on hostile input")
    }

    private fun mutate(seed: ByteArray, random: Random): ByteArray {
        var data = seed.copyOf()
        repeat(random.nextInt(1, 6)) {
            data = when (random.nextInt(7)) {
                0 -> data.also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                1 -> data.also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = INTERESTING[random.nextInt(INTERESTING.size)].toByte() }
                2 -> if (data.size > 1) data.copyOf(random.nextInt(1, data.size)) else data // truncate
                3 -> { // insert a hostile uintvar (huge lengths)
                    val at = random.nextInt(data.size + 1)
                    data.copyOfRange(0, at) + bytes(0x8F, 0xFF, 0xFF, 0xFF, 0x7F) + data.copyOfRange(at, data.size)
                }
                4 -> { // duplicate a slice (repeated headers / parts)
                    if (data.size < 2) data else {
                        val from = random.nextInt(data.size - 1)
                        val to = random.nextInt(from + 1, data.size)
                        data.copyOfRange(0, to) + data.copyOfRange(from, to) + data.copyOfRange(to, data.size)
                    }
                }
                5 -> { // delete a slice
                    if (data.size < 2) data else {
                        val from = random.nextInt(data.size - 1)
                        val to = random.nextInt(from + 1, data.size)
                        data.copyOfRange(0, from) + data.copyOfRange(to, data.size)
                    }
                }
                else -> data.also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = (it[random.nextInt(it.size)].toInt() xor (1 shl random.nextInt(8))).toByte() }
            }
        }
        return data
    }

    @Test
    fun hugeDeclaredLengthsDoNotAllocate() {
        // Value-length quote + uintvar 0xFFFFFFF claims a 256 MiB subject in a 12-byte PDU.
        val r = decodeNoBug(bytes(0x8C, 0x82, 0x96, 0x1F, 0xFF, 0xFF, 0xFF, 0x7F, 0x83, text("x")))
        assertIs<PduDecodeResult.Failure>(r)
        // Multipart part claiming a 2 GiB data length.
        val m = decodeNoBug(bytes(0x8C, 0x84, 0x84, 0xA3, 0x01, 0x01, 0x87, 0xFF, 0xFF, 0xFF, 0x7F, 0x83, "x"))
        assertIs<PduDecodeResult.Failure>(m)
    }

    @Test
    fun oversizedPduRejectedWithoutParsing() {
        val big = ByteArray(MmsLimits.MAX_PDU_BYTES + 1).also { it[0] = 0x8C.toByte(); it[1] = 0x84.toByte() }
        val r = MmsPduDecoder.decode(big)
        assertIs<PduDecodeResult.Failure>(r)
    }

    @Test
    fun thousandsOfPartsRejected() {
        val count = 5_000
        val body = java.io.ByteArrayOutputStream()
        body.write(uintvar(count))
        repeat(count) { body.write(entry(bytes(0x83), bytes("a"))) } // text/plain, 1 octet
        val pdu = bytes(0x8C, 0x84, 0x84, 0xA3, body.toByteArray())
        val r = decodeNoBug(pdu)
        val f = assertIs<PduDecodeResult.Failure>(r)
        assertTrue(f.error.message.contains("parts"), f.error.message)
    }

    @Test
    fun partLimitAllowsRealisticMessages() {
        val count = 40
        val body = java.io.ByteArrayOutputStream()
        body.write(uintvar(count))
        repeat(count) { body.write(entry(bytes(0x83), bytes("a"))) }
        val r = decodeNoBug(bytes(0x8C, 0x84, 0x84, 0xA3, body.toByteArray()))
        assertEquals(count, assertIs<RetrieveConf>(r.getOrNull()).parts.size)
    }

    @Test
    fun nestedMultipartsCannotExceedPartLimit() {
        // Outer multipart with 3 nested multiparts of 200 parts each: flattened total 600 > MAX_PARTS.
        fun inner(n: Int): ByteArray {
            val b = java.io.ByteArrayOutputStream()
            b.write(uintvar(n))
            repeat(n) { b.write(entry(bytes(0x83), bytes("z"))) }
            return b.toByteArray()
        }
        val outer = java.io.ByteArrayOutputStream()
        outer.write(uintvar(3))
        repeat(3) { outer.write(entry(bytes(0xA3), inner(200))) } // 0xA3 = multipart/mixed
        val r = decodeNoBug(bytes(0x8C, 0x84, 0x84, 0xA3, outer.toByteArray()))
        assertIs<PduDecodeResult.Failure>(r)
    }

    @Test
    fun deeplyNestedMultipartIsBounded() {
        var payload = bytes(uintvar(1), entry(bytes(0x83), bytes("leaf")))
        repeat(200) { payload = bytes(uintvar(1), entry(bytes(0xA3), payload)) }
        val r = decodeNoBug(bytes(0x8C, 0x84, 0x84, 0xA3, payload))
        // Beyond MAX_NESTING the inner multipart stays an opaque part; decoding succeeds without recursion blow-up.
        assertIs<RetrieveConf>(r.getOrNull())
    }

    @Test
    fun repeatedRecipientsAreCapped() {
        val out = java.io.ByteArrayOutputStream()
        out.write(bytes(0x8C, 0x84))
        repeat(5_000) { i -> out.write(bytes(0x97, text("+1555${i.toString().padStart(7, '0')}/TYPE=PLMN"))) }
        out.write(bytes(0x84, 0x83, "body"))
        val conf = assertIs<RetrieveConf>(decodeNoBug(out.toByteArray()).getOrNull())
        assertEquals(MmsLimits.MAX_ADDRESSES, conf.to.size)
    }

    @Test
    fun longSubjectIsTruncated() {
        val subject = "S".repeat(100_000)
        val r = decodeNoBug(bytes(0x8C, 0x82, 0x96, text(subject), 0x83, text("http://m/x")))
        val n = assertIs<NotificationInd>(r.getOrNull())
        assertEquals(MmsLimits.MAX_HEADER_TEXT_CHARS, n.subject?.length)
    }

    @Test
    fun garbageCharsetsDecodeWithoutThrowing() {
        val random = Random(7)
        val charsets = listOf(null, 0, 3, 4, 17, 18, 38, 39, 106, 113, 114, 1000, 1013, 1014, 1015, 2025, 2026, 2252, 99999, -5)
        repeat(5_000) {
            val data = random.nextBytes(random.nextInt(0, 64))
            for (cs in charsets) MmsCharset.decode(data, cs)
        }
    }

    // --- Values leaving the codec ------------------------------------------------------------------------------

    @Test
    fun contentLocationAllowList() {
        assertTrue(MmsSafety.isDownloadableContentLocation("http://mmsc.carrier.com/mms?id=12345"))
        assertTrue(MmsSafety.isDownloadableContentLocation("https://10.1.2.3:8080/x"))
        assertTrue(MmsSafety.isDownloadableContentLocation("HTTP://MMSC.EXAMPLE/X"))
        listOf(
            null, "", "file:///data/data/app.dak/databases/index.db", "content://mms/part/1", "javascript:alert(1)",
            "intent://x#Intent;end", "ftp://host/x", "/relative/path", "mmsc/x", "http://", "http:///x",
            "http://localhost/x", "http://127.0.0.1:5555/", "http://[::1]/x", "http://0.0.0.0/",
            "http://user:pw@host/x", "http://host/a b", "http://host/\u0000x", "http://hóst/x",
            "http://host/" + "a".repeat(MmsSafety.MAX_CONTENT_LOCATION_CHARS),
        ).forEach { assertFalse(MmsSafety.isDownloadableContentLocation(it), "accepted $it") }
    }

    @Test
    fun partFileNamesAreSanitised() {
        assertEquals("passwd", MmsSafety.safeFileName("../../../../etc/passwd"))
        assertEquals("b.jpg", MmsSafety.safeFileName("C:\\a\\b.jpg"))
        assertEquals("x.jpg", MmsSafety.safeFileName("/data/data/app.dak/x.jpg"))
        assertEquals("evilgpj.exe", MmsSafety.safeFileName("evil\u202Egpj.exe"))
        assertEquals("ab.png", MmsSafety.safeFileName("a\u0000b.png"))
        assertEquals("hidden", MmsSafety.safeFileName(".hidden"))
        assertNull(MmsSafety.safeFileName(".."))
        assertNull(MmsSafety.safeFileName("../"))
        assertNull(MmsSafety.safeFileName("   "))
        assertNull(MmsSafety.safeFileName(null))
        val long = MmsSafety.safeFileName("x".repeat(10_000) + ".jpeg")!!
        assertEquals(MmsSafety.MAX_FILE_NAME_CHARS, long.length)
        assertTrue(long.endsWith(".jpeg"))
        assertEquals("photo.jpg", MmsSafety.safeFileName("photo.jpg"))
        assertEquals("фото 1.jpg", MmsSafety.safeFileName("фото 1.jpg"))
    }

    @Test
    fun decodedTraversalNamesExposeSafeFileName() {
        val headers = bytes(0x9E, 0x8E, text("../../shared_prefs/x.xml"))
        val pdu = bytes(0x8C, 0x84, 0x84, 0xA3, uintvar(1), entry(headers, bytes(1, 2, 3)))
        val part = assertIs<RetrieveConf>(decodeNoBug(pdu).getOrNull()).parts.single()
        assertEquals("../../shared_prefs/x.xml", part.contentLocation) // raw value is preserved (provider stays raw)
        assertEquals("x.xml", part.safeFileName)
    }

    private companion object {
        val INTERESTING = intArrayOf(0x00, 0x01, 0x1E, 0x1F, 0x20, 0x7F, 0x80, 0x81, 0x83, 0x84, 0x8C, 0x8D, 0x96, 0x97, 0xA3, 0xB3, 0xFF)
    }
}
