package app.dak.mms.pdu

import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Zero-click hardening harness for the MMS decoder: every byte of a WAP push and of a downloaded m-retrieve-conf is
 * attacker-controlled, and the decoder runs with no user interaction. For each generated input this asserts:
 *
 * - no Throwable escapes [MmsPduDecoder.decode] (an Error such as StackOverflowError would kill the receiver's
 *   process), and no internal bug surfaces as an offset -1 failure;
 * - time per input stays under [MAX_MILLIS_PER_INPUT] and the whole run under a wall-clock cap;
 * - bytes allocated while decoding stay under a linear bound of the input size (no allocation from declared
 *   lengths, no per-nesting-level copies);
 * - every value that leaves the codec respects [MmsLimits] (address, identifier, MIME type, subject and part-count
 *   caps), and the consumer-side helpers (`text(maxChars)`, `safeFileName`, content-location check) never throw.
 *
 * Inputs come from three deterministic generators (fixed seeds, reproducible): a grammar-aware random PDU
 * generator covering every header field code and value encoding, byte-level mutation of valid PDUs, and a few
 * large inputs for the allocation bound.
 */
class HostileInputFuzzTest {

    private val threadBean: com.sun.management.ThreadMXBean? =
        (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
            ?.takeIf { it.isThreadAllocatedMemorySupported }
            ?.also { it.isThreadAllocatedMemoryEnabled = true }

    private var slowest = 0L

    /** Decodes [input] and checks every invariant; returns the result for further assertions. */
    private fun check(input: ByteArray): PduDecodeResult {
        val tid = Thread.currentThread().id
        val allocatedBefore = threadBean?.getThreadAllocatedBytes(tid) ?: 0L
        val start = System.nanoTime()
        val result = try {
            MmsPduDecoder.decode(input)
        } catch (t: Throwable) {
            throw AssertionError("decode threw ${t.javaClass.name} for ${describe(input)}", t)
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        slowest = maxOf(slowest, millis)
        assertTrue(millis < MAX_MILLIS_PER_INPUT, "decode took $millis ms for ${describe(input)}")
        threadBean?.let {
            val allocated = it.getThreadAllocatedBytes(tid) - allocatedBefore
            val bound = ALLOCATION_FACTOR * input.size + ALLOCATION_SLACK
            assertTrue(allocated <= bound, "decode allocated $allocated bytes (> $bound) for ${describe(input)}")
        }
        when (result) {
            is PduDecodeResult.Failure -> {
                val e = result.error
                if (e is PduError.Malformed && e.offset == -1) throw AssertionError("internal decoder error ${e.detail} for ${describe(input)}")
            }
            is PduDecodeResult.Success -> checkOutputs(result.pdu)
        }
        return result
    }

    private fun checkOutputs(pdu: MmsPdu) {
        val addresses = ArrayList<String>()
        val tokens = ArrayList<String?>()
        var parts: List<PduPart> = emptyList()
        var subject: String? = null
        when (pdu) {
            is NotificationInd -> {
                pdu.from?.let(addresses::add)
                tokens += listOf(pdu.contentLocation, pdu.transactionId, pdu.messageClass)
                subject = pdu.subject
                MmsSafety.isDownloadableContentLocation(pdu.contentLocation)
            }
            is RetrieveConf -> {
                pdu.from?.let(addresses::add); addresses += pdu.to; addresses += pdu.cc
                tokens += listOf(pdu.transactionId, pdu.messageId, pdu.messageClass)
                parts = pdu.parts
                subject = pdu.subject
                assertTrue((pdu.retrieveText?.length ?: 0) <= MmsLimits.MAX_HEADER_TEXT_CHARS)
            }
            is SendReq -> {
                pdu.from?.let(addresses::add); addresses += pdu.to; addresses += pdu.cc; addresses += pdu.bcc
                tokens += listOf(pdu.transactionId, pdu.messageClass)
                parts = pdu.parts
                subject = pdu.subject
            }
            is DeliveryInd -> { addresses += pdu.to; tokens += pdu.messageId }
            is ReadOrigInd -> { pdu.from?.let(addresses::add); addresses += pdu.to; tokens += pdu.messageId }
            is ReadRecInd -> { pdu.from?.let(addresses::add); addresses += pdu.to; tokens += pdu.messageId }
            is SendConf -> tokens += listOf(pdu.transactionId, pdu.messageId)
            is NotifyRespInd -> tokens += pdu.transactionId
            is AcknowledgeInd -> tokens += pdu.transactionId
        }
        assertTrue(addresses.size <= MmsLimits.MAX_ADDRESSES + 1, "too many addresses: ${addresses.size}")
        addresses.forEach { assertTrue(it.length <= MmsLimits.MAX_ADDRESS_CHARS, "address of ${it.length} chars") }
        tokens.forEach { assertTrue((it?.length ?: 0) <= MmsLimits.MAX_TOKEN_CHARS, "token of ${it?.length} chars") }
        assertTrue((subject?.length ?: 0) <= MmsLimits.MAX_HEADER_TEXT_CHARS)
        assertTrue(parts.size <= MmsLimits.MAX_PARTS, "${parts.size} parts")
        for (part in parts) {
            val ct = part.contentType
            assertTrue(ct.mimeType.length <= MmsLimits.MAX_MIME_TYPE_CHARS, "mime of ${ct.mimeType.length} chars")
            listOf(part.contentId, part.contentLocation, part.dispositionFileName, ct.name, ct.fileName, ct.start, ct.startInfo)
                .forEach { assertTrue((it?.length ?: 0) <= MmsLimits.MAX_TOKEN_CHARS, "part header of ${it?.length} chars") }
            assertTrue(ct.otherParameters.size <= 16)
            val text = part.text(MmsLimits.MAX_INLINE_TEXT_CHARS)
            assertTrue((text?.length ?: 0) <= MmsLimits.MAX_INLINE_TEXT_CHARS)
            part.safeFileName?.let { assertTrue(it.length <= MmsSafety.MAX_FILE_NAME_CHARS && !it.contains('/') && !it.startsWith(".")) }
        }
    }

    // --- Generators -----------------------------------------------------------------------------------------

    /** Grammar-aware random PDU: random header fields with random (often wrong) value encodings, then a body. */
    private fun randomPdu(random: Random): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x8C); out.write(if (random.nextInt(10) == 0) random.nextInt(256) else listOf(0x82, 0x84, 0x80, 0x86, 0x87, 0x88, 0x81).random(random))
        repeat(random.nextInt(0, 12)) {
            if (random.nextInt(12) == 0) {
                out.write(randomText(random)); out.write(randomText(random)) // application header
            } else {
                out.write(0x80 or random.nextInt(0x40))
                out.write(randomValue(random))
            }
        }
        if (random.nextBoolean()) {
            out.write(0x84) // Content-Type, then the body
            out.write(randomContentType(random, multipart = random.nextInt(3) != 0))
            out.write(randomMultipart(random, depth = 0))
        }
        return out.toByteArray()
    }

    private fun randomValue(random: Random): ByteArray = when (random.nextInt(9)) {
        0 -> byteArrayOf((0x80 or random.nextInt(0x80)).toByte()) // short integer / octet
        1 -> randomText(random)
        2 -> bytes(random.nextInt(1, 9), random.nextBytes(random.nextInt(0, 10))) // long integer (maybe truncated)
        3 -> vl(bytes(0x80 + random.nextInt(2), random.nextBytes(random.nextInt(0, 6)))) // time / from
        4 -> vl(bytes(0xEA, randomText(random))) // encoded string with UTF-8 charset
        5 -> vl(bytes(0x83, 0xE8, random.nextBytes(random.nextInt(0, 40)))) // UTF-16 charset (1000) with raw bytes
        6 -> bytes(0x1F, uintvarLong(random.nextLong(0, 1L shl 35)), random.nextBytes(random.nextInt(0, 8))) // huge length quote
        7 -> bytes(0x81, text("x".repeat(random.nextInt(0, 3000)))) // From: insert-address-ish / long address
        else -> random.nextBytes(random.nextInt(0, 16))
    }

    private fun randomText(random: Random): ByteArray {
        val length = when (random.nextInt(20)) {
            0 -> random.nextInt(1000, 5000) // over the identifier / address limits
            else -> random.nextInt(0, 24)
        }
        val alphabet = "abcXYZ019./:@+-_<>\"'‮\u0000 é"
        val s = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }.replace("\u0000", "")
        return if (random.nextInt(8) == 0) bytes(0x7F, s, 0) else bytes(s, 0)
    }

    private fun randomContentType(random: Random, multipart: Boolean): ByteArray = when (random.nextInt(4)) {
        0 -> byteArrayOf((if (multipart) listOf(0xA3, 0xB3, 0xA2).random(random) else listOf(0x83, 0x9E, 0xB6).random(random)).toByte())
        1 -> text(if (multipart) "application/vnd.wap.multipart.mixed" else "text/plain".repeat(if (random.nextInt(10) == 0) 60 else 1))
        else -> {
            val params = ByteArrayOutputStream()
            repeat(random.nextInt(0, 6)) {
                when (random.nextInt(5)) {
                    0 -> { params.write(0x81); params.write(listOf(0xEA, 0x84, 0x83).random(random)) } // charset
                    1 -> { params.write(0x85); params.write(randomText(random)) } // name
                    2 -> { params.write(0x8A); params.write(randomText(random)) } // start
                    3 -> { params.write(randomText(random)); params.write(randomText(random)) } // untyped
                    else -> params.write(random.nextBytes(random.nextInt(0, 4)))
                }
            }
            val media = if (multipart) byteArrayOf(0xB3.toByte()) else byteArrayOf(0x83.toByte())
            vl(bytes(media, params.toByteArray()))
        }
    }

    private fun randomMultipart(random: Random, depth: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val count = if (depth == 0 && random.nextInt(20) == 0) random.nextInt(200, 400) else random.nextInt(0, 5)
        out.write(uintvar(count))
        repeat(count) {
            val nested = depth < 5 && count < 5 && random.nextInt(4) == 0
            val headers = ByteArrayOutputStream()
            headers.write(randomContentType(random, multipart = nested))
            repeat(random.nextInt(0, 3)) {
                when (random.nextInt(4)) {
                    0 -> { headers.write(0x8E); headers.write(randomText(random)) } // Content-Location
                    1 -> { headers.write(0xC0); headers.write(randomText(random)) } // Content-ID
                    2 -> { headers.write(0xAE); headers.write(vl(bytes(0x81, 0x86, randomText(random)))) } // disposition
                    else -> headers.write(random.nextBytes(random.nextInt(0, 4)))
                }
            }
            val data = if (nested) randomMultipart(random, depth + 1) else random.nextBytes(random.nextInt(0, 64))
            val h = headers.toByteArray()
            // Occasionally lie about the lengths.
            val declaredData = if (random.nextInt(15) == 0) data.size + random.nextInt(-3, 1_000_000) else data.size
            out.write(uintvar(h.size)); out.write(uintvar(declaredData.coerceAtLeast(0))); out.write(h); out.write(data)
        }
        return out.toByteArray()
    }

    private fun mutate(seed: ByteArray, random: Random): ByteArray {
        var data = seed.copyOf()
        repeat(random.nextInt(1, 6)) {
            data = when (random.nextInt(6)) {
                0 -> data.also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                1 -> if (data.size > 1) data.copyOf(random.nextInt(1, data.size)) else data
                2 -> {
                    val at = random.nextInt(data.size + 1)
                    data.copyOfRange(0, at) + randomValue(random) + data.copyOfRange(at, data.size)
                }
                3 -> if (data.size < 2) data else {
                    val from = random.nextInt(data.size - 1)
                    val to = random.nextInt(from + 1, data.size)
                    data.copyOfRange(0, to) + data.copyOfRange(from, to) + data.copyOfRange(to, data.size)
                }
                4 -> if (data.size < 2) data else {
                    val from = random.nextInt(data.size - 1)
                    val to = random.nextInt(from + 1, data.size)
                    data.copyOfRange(0, from) + data.copyOfRange(to, data.size)
                }
                else -> data.also { if (it.isNotEmpty()) { val i = random.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl random.nextInt(8))).toByte() } }
            }
        }
        return data
    }

    private val seeds: List<ByteArray> by lazy {
        val nestedBody = bytes(
            uintvar(2),
            entry(bytes(0x83, 0x8E, text("t.txt")), bytes("hello")),
            entry(bytes(0xA3), bytes(uintvar(2), entry(bytes(0x9E), ByteArray(40) { 7 }), entry(bytes(0x83), bytes("inner")))),
        )
        listOf(
            MmsPduEncoder.encode(
                MmsMessageBuilder.build(
                    to = listOf("+15551234567", "alice@example.com"),
                    text = "Hello ‮evil ✓",
                    attachments = listOf(MmsMessageBuilder.Attachment("image/jpeg", "../../a.jpg", ByteArray(64) { it.toByte() })),
                    subject = "Sübject",
                    transactionId = "T1",
                ),
            ),
            MmsPduEncoder.encode(
                NotificationInd(contentLocation = "http://mmsc.example/x?id=1", transactionId = "t", from = "+1555", subject = "s", messageSize = 1234, expiry = MmsTime.Relative(3600)),
            ),
            bytes(0x8C, 0x84, 0x98, text("tid"), 0x8D, 0x93, 0x89, vl(bytes(0x80, text("+15550001111/TYPE=PLMN"))), 0x84, 0xA3, nestedBody),
            MmsPduEncoder.encode(ReadOrigInd(messageId = "m", from = "+1", to = listOf("+2"), readStatus = ReadStatus.READ)),
        )
    }

    // --- Tests ----------------------------------------------------------------------------------------------

    @Test
    fun grammarAwareRandomPdus() {
        val random = Random(0x5EC_0001)
        val deadline = System.nanoTime() + WALL_CLOCK_CAP_NANOS
        var ran = 0
        var decoded = 0
        while (ran < ITERATIONS && System.nanoTime() < deadline) {
            if (check(randomPdu(random)) is PduDecodeResult.Success) decoded++
            ran++
        }
        assertTrue(ran >= MIN_ITERATIONS, "only $ran iterations before the time cap (slowest input $slowest ms)")
        // The generator must reach the success paths too, or the output invariants are never exercised.
        assertTrue(decoded > ran / 50, "only $decoded of $ran generated PDUs decoded")
    }

    @Test
    fun mutatedValidPdus() {
        val random = Random(0x5EC_0002)
        val deadline = System.nanoTime() + WALL_CLOCK_CAP_NANOS
        var ran = 0
        while (ran < ITERATIONS && System.nanoTime() < deadline) {
            check(mutate(seeds[random.nextInt(seeds.size)], random))
            ran++
        }
        assertTrue(ran >= MIN_ITERATIONS, "only $ran iterations before the time cap (slowest input $slowest ms)")
    }

    @Test
    fun largeInputsStayWithinLinearAllocation() {
        val random = Random(0x5EC_0003)
        repeat(6) { i ->
            val size = (1 shl 20) * (i + 1)
            // Deeply nested multipart whose leaf carries most of the bytes: the old decoder copied it once per level.
            var payload = bytes(uintvar(1), entry(bytes(0x83), random.nextBytes(size)))
            repeat(3) { payload = bytes(uintvar(1), entry(bytes(0xA3), payload)) }
            val r = check(bytes(0x8C, 0x84, 0x84, 0xA3, payload))
            val parts = assertIs<RetrieveConf>(r.getOrNull()).parts
            assertEquals(1, parts.size)
            // A huge text part never becomes a huge String.
            assertTrue(parts.single().text(MmsLimits.MAX_INLINE_TEXT_CHARS)!!.length <= MmsLimits.MAX_INLINE_TEXT_CHARS)
        }
        repeat(20) { check(random.nextBytes(random.nextInt(1 shl 18, 1 shl 21))) }
    }

    // --- Targeted regressions ---------------------------------------------------------------------------------

    @Test
    fun oversizedAddressesAndIdentifiersAreDropped() {
        val longAddress = "+1" + "5".repeat(5_000)
        val longId = "i".repeat(MmsLimits.MAX_TOKEN_CHARS + 1)
        val pdu = bytes(
            0x8C, 0x84, 0x98, text(longId), 0x8B, text(longId), 0x8D, 0x93,
            0x89, vl(bytes(0x80, text(longAddress))),
            0x97, text(longAddress), 0x97, text("+15550002222"),
            0x96, text("s".repeat(5_000)),
            0x84, 0x83, "body",
        )
        val conf = assertIs<RetrieveConf>(check(pdu).getOrNull())
        assertNull(conf.from)
        assertNull(conf.transactionId)
        assertNull(conf.messageId)
        assertEquals(listOf("+15550002222"), conf.to)
        assertEquals(MmsLimits.MAX_HEADER_TEXT_CHARS, conf.subject?.length)
    }

    @Test
    fun oversizedPartHeadersAndMimeTypesAreBounded() {
        val longName = "n".repeat(MmsLimits.MAX_TOKEN_CHARS + 10)
        val headers = bytes(text("image/" + "x".repeat(1_000)), 0x8E, text(longName), 0xC0, text(longName))
        val pdu = bytes(0x8C, 0x84, 0x84, 0xA3, uintvar(1), entry(headers, bytes(1, 2, 3)))
        val part = assertIs<RetrieveConf>(check(pdu).getOrNull()).parts.single()
        assertEquals("application/octet-stream", part.contentType.mimeType)
        assertNull(part.contentLocation)
        assertNull(part.contentId)
    }

    @Test
    fun hugeTextPartIsDecodedOnlyUpToTheLimit() {
        val body = ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() }
        val pdu = bytes(0x8C, 0x84, 0x84, 0xA3, uintvar(1), entry(bytes(0x83), body))
        val part = assertIs<RetrieveConf>(check(pdu).getOrNull()).parts.single()
        assertEquals(MmsLimits.MAX_INLINE_TEXT_CHARS, part.text(MmsLimits.MAX_INLINE_TEXT_CHARS)!!.length)
        assertEquals("aaa", part.text(3))
    }

    @Test
    fun truncateNeverSplitsSurrogatePairs() {
        val s = "ab😀cd" // a b 😀 c d
        assertEquals("ab", MmsSafety.truncate(s, 3))
        assertEquals("ab😀", MmsSafety.truncate(s, 4))
        assertEquals(s, MmsSafety.truncate(s, 100))
        assertEquals("", MmsSafety.truncate(s, 0))
    }

    @Test
    fun contentLocationRejectsDisguisedLoopback() {
        listOf(
            "http://localhost./x", "http://LOCALHOST.:8080/x", "http://foo.localhost./x",
            "http://[::ffff:127.0.0.1]/x", "http://[::ffff:7f00:1]/x", "http://[::127.0.0.1]/x", "http://[0::1]/x",
            "http://[0:0::1]/x", "http://[::]/x", "http://[::ffff:0.0.0.0]/x", "http://[fe80::1%25eth0]/x".takeIf { false },
            "http://2130706433/x", "http://0x7f.0.0.1/x", "http://0177.0.0.1/x", "http://127.1/x", "http://0/x",
            "http://0.0.0.0./x", "http://127.0.0.1./x", "http://017700000001/x",
        ).filterNotNull().forEach { assertFalse(MmsSafety.isDownloadableContentLocation(it), "accepted $it") }
        listOf(
            "http://mmsc.carrier.com:8002/mms?id=1", "http://10.0.0.5/x", "http://100.64.1.2:8080/m", "http://[2001:db8::1]/x",
            "http://127example.com/x", "http://0x7f.example.com/x", "https://mms.msg.eng.t-mobile.com/mms/wapenc?T=abc",
        ).forEach { assertTrue(MmsSafety.isDownloadableContentLocation(it), "rejected $it") }
    }

    /** Value-length (short form up to 30, else Length-quote + uintvar) followed by [body]. */
    private fun vl(body: ByteArray): ByteArray = if (body.size <= 30) bytes(body.size, body) else bytes(0x1F, uintvar(body.size), body)

    private fun describe(input: ByteArray): String = "${input.size} octets: ${hex(input.copyOf(minOf(48, input.size)))}"

    private fun uintvarLong(value: Long): ByteArray {
        val groups = ArrayList<Int>()
        var v = value
        do {
            groups.add((v and 0x7F).toInt()); v = v shr 7
        } while (v != 0L)
        return ByteArray(groups.size) { i -> (groups[groups.size - 1 - i] or (if (i < groups.size - 1) 0x80 else 0)).toByte() }
    }

    private companion object {
        const val ITERATIONS = 60_000
        const val MIN_ITERATIONS = 20_000
        const val WALL_CLOCK_CAP_NANOS = 60_000_000_000L
        const val MAX_MILLIS_PER_INPUT = 2_000L

        /** Decoding copies each leaf part once (plus bounded strings), never once per nesting level or per declared length. */
        const val ALLOCATION_FACTOR = 2L
        const val ALLOCATION_SLACK = 4L * 1024 * 1024
    }
}
