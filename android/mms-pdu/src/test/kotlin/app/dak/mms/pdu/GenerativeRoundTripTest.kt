package app.dak.mms.pdu

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Property-style round trips: for every PDU type, a few hundred seeded random instances with every optional field
 * independently present or absent must survive encode -> decode unchanged. Complements the byte-exact fixtures in
 * [EncoderRoundTripTest] by exploring field combinations nobody wrote by hand. Fixed seed, so any failure is
 * reproducible from the printed instance.
 */
class GenerativeRoundTripTest {

    private val random = Random(0xD4C_0DE)
    private val samplesPerType = 300

    private fun <T> maybe(value: () -> T): T? = if (random.nextBoolean()) value() else null

    private fun token(max: Int = 24): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._@"
        return buildString { repeat(random.nextInt(1, max)) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun humanText(): String = listOf(
        token(), "Hello, world!", "नमस्ते दुनिया", "😀 party 🎉", "Café crème", "价格 ¥500", "a b", token(60),
    ).random(random)

    private fun address(): String = when (random.nextInt(3)) {
        0 -> "+1555" + random.nextInt(1_000_000, 9_999_999)
        1 -> "9" + random.nextLong(100_000_000L, 999_999_999L)
        else -> "${token(10).filter { it.isLetterOrDigit() }.ifEmpty { "a" }}@example.com"
    }

    private fun addresses(): List<String> = List(random.nextInt(0, 4)) { address() }
    private fun version() = listOf(MmsVersion.V1_0, MmsVersion.V1_1, MmsVersion.V1_2, MmsVersion.V1_3).random(random)
    private fun seconds() = random.nextLong(0, 1L shl 40)
    private fun priority() = listOf(Priority.LOW, Priority.NORMAL, Priority.HIGH).random(random)
    private fun messageClass() = listOf(MessageClass.PERSONAL, MessageClass.ADVERTISEMENT, MessageClass.INFORMATIONAL, MessageClass.AUTO, "x-${token(8).lowercase().filter { it.isLetter() }.ifEmpty { "c" }}").random(random)
    private fun expiry(): MmsTime = if (random.nextBoolean()) MmsTime.Absolute(seconds()) else MmsTime.Relative(random.nextLong(1, 1L shl 31))

    private fun parts(): List<PduPart> = List(random.nextInt(0, 5)) { i ->
        if (random.nextBoolean()) {
            PduPart.text(humanText(), contentId = "<t$i>", contentLocation = "t$i.txt")
        } else {
            PduPart(
                contentType = ContentType("image/png", name = "i$i.png"),
                data = random.nextBytes(random.nextInt(0, 300)),
                contentId = "<i$i>",
                contentLocation = "i$i.png",
            )
        }
    }

    private fun roundTrip(pdu: MmsPdu): MmsPdu {
        val bytes = MmsPduEncoder.encode(pdu)
        val decoded = MmsPduDecoder.decode(bytes)
        assertIs<PduDecodeResult.Success>(decoded, "decode failed for $pdu")
        return decoded.pdu
    }

    private fun check(generate: () -> MmsPdu) {
        repeat(samplesPerType) {
            val pdu = generate()
            assertEquals(pdu, roundTrip(pdu), "round trip changed $pdu")
        }
    }

    @Test
    fun sendReq() = check {
        SendReq(
            transactionId = token(), to = addresses().ifEmpty { listOf(address()) },
            contentType = ContentType.multipartRelated(start = "<t0>", type = ContentType.TEXT_PLAIN), parts = parts(),
            cc = addresses(), bcc = addresses(), from = maybe(::address), subject = maybe(::humanText), dateSeconds = maybe(::seconds),
            messageClass = maybe(::messageClass), expiry = maybe(::expiry), priority = maybe(::priority),
            deliveryReport = maybe { random.nextBoolean() }, readReport = maybe { random.nextBoolean() }, mmsVersion = version(),
        )
    }

    @Test
    fun notificationInd() = check {
        NotificationInd(
            contentLocation = "http://mmsc.example.com/" + token(), transactionId = maybe { token() }, from = maybe(::address),
            subject = maybe(::humanText), messageClass = maybe(::messageClass), messageSize = random.nextLong(0, 1L shl 32),
            expiry = maybe(::expiry), priority = maybe(::priority), deliveryReport = maybe { random.nextBoolean() }, mmsVersion = version(),
        )
    }

    @Test
    fun retrieveConf() = check {
        RetrieveConf(
            contentType = ContentType(ContentType.MULTIPART_MIXED), parts = parts(), transactionId = maybe { token() },
            messageId = maybe { token() }, dateSeconds = maybe(::seconds), from = maybe(::address), to = addresses(), cc = addresses(),
            subject = maybe(::humanText), messageClass = maybe(::messageClass), priority = maybe(::priority),
            deliveryReport = maybe { random.nextBoolean() }, readReport = maybe { random.nextBoolean() },
            retrieveStatus = maybe { listOf(RetrieveStatus.OK, RetrieveStatus.ERROR_TRANSIENT_FAILURE, RetrieveStatus.ERROR_PERMANENT_FAILURE).random(random) },
            retrieveText = maybe(::humanText), mmsVersion = version(),
        )
    }

    @Test
    fun smallPdus() {
        check { SendConf(responseStatus = listOf(ResponseStatus.OK, ResponseStatus.ERROR_TRANSIENT_FAILURE, 0xE5).random(random), transactionId = maybe { token() }, messageId = maybe { token() }, responseText = maybe(::humanText), mmsVersion = version()) }
        check { NotifyRespInd(transactionId = token(), status = (0x80..0x87).random(random), reportAllowed = maybe { random.nextBoolean() }, mmsVersion = version()) }
        check { AcknowledgeInd(transactionId = token(), reportAllowed = maybe { random.nextBoolean() }, mmsVersion = version()) }
        check { DeliveryInd(messageId = token(), status = (0x80..0x87).random(random), to = addresses(), dateSeconds = maybe(::seconds), mmsVersion = version()) }
        check { ReadOrigInd(messageId = maybe { token() }, from = maybe(::address), to = addresses(), dateSeconds = maybe(::seconds), readStatus = maybe { listOf(ReadStatus.READ, ReadStatus.DELETED_WITHOUT_BEING_READ).random(random) }, mmsVersion = version()) }
        check { ReadRecInd(messageId = token(), to = address(), from = maybe(::address), dateSeconds = maybe(::seconds), readStatus = listOf(ReadStatus.READ, ReadStatus.DELETED_WITHOUT_BEING_READ).random(random), mmsVersion = version()) }
    }

    @Test
    fun builtMessagesRoundTripAndTheirSmilReferencesEveryPart() {
        repeat(100) {
            val attachments = List(random.nextInt(0, 4)) { i ->
                MmsMessageBuilder.Attachment(listOf("image/jpeg", "video/mp4", "audio/amr", "application/pdf").random(random), "file $i/${humanText()}", random.nextBytes(50))
            }
            val req = MmsMessageBuilder.build(listOf(address()), maybe(::humanText), attachments, subject = maybe(::humanText), transactionId = token())
            assertEquals(req, roundTrip(req))
            val smil = req.parts.first().text()!!
            for (p in req.parts.drop(1)) assertTrue("src=\"${p.contentLocation}\"" in smil, "SMIL misses ${p.contentLocation}: $smil")
            assertEquals(req.parts.size, req.parts.map { it.contentLocation!!.lowercase() }.toSet().size, "locations unique")
        }
    }
}
