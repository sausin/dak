package app.dak.mms.pdu

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Send-side SMIL generation ([Smil]) and the receive-side presentation order ([SmilPresentation]). */
class SmilTest {

    private fun keys(vararg locations: String) = locations.map { SmilPresentation.PartKey(contentLocation = it) }

    // --- Send side ----------------------------------------------------------------------------------------------

    @Test
    fun captionSharesTheSlideOfTheFirstImage() {
        val smil = Smil.build(
            listOf(Smil.Item("text_0.txt", Smil.Kind.TEXT), Smil.Item("a.jpg", Smil.Kind.IMAGE), Smil.Item("b.jpg", Smil.Kind.IMAGE)),
        )
        assertEquals(
            "<smil><head><layout><root-layout width=\"320\" height=\"480\"/>" +
                "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>" +
                "<region id=\"Text\" fit=\"scroll\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>" +
                "</layout></head><body>" +
                "<par dur=\"5000ms\"><img src=\"a.jpg\" region=\"Image\"/><text src=\"text_0.txt\" region=\"Text\"/></par>" +
                "<par dur=\"5000ms\"><img src=\"b.jpg\" region=\"Image\"/></par>" +
                "</body></smil>",
            smil,
        )
    }

    @Test
    fun captionPrefersVisualMediaThenAnyMedia() {
        val audioThenVideo = Smil.slides(
            listOf(Smil.Item("s.amr", Smil.Kind.AUDIO), Smil.Item("v.mp4", Smil.Kind.VIDEO), Smil.Item("t.txt", Smil.Kind.TEXT)),
        )
        assertEquals(listOf(listOf("s.amr"), listOf("v.mp4", "t.txt")), audioThenVideo.map { s -> s.map { it.src } })
        val cardOnly = Smil.slides(listOf(Smil.Item("c.vcf", Smil.Kind.OTHER), Smil.Item("t.txt", Smil.Kind.TEXT)))
        assertEquals(listOf(listOf("c.vcf", "t.txt")), cardOnly.map { s -> s.map { it.src } })
        val textOnly = Smil.slides(listOf(Smil.Item("t.txt", Smil.Kind.TEXT)))
        assertEquals(listOf(listOf("t.txt")), textOnly.map { s -> s.map { it.src } })
        assertEquals(emptyList(), Smil.slides(emptyList()))
    }

    @Test
    fun generatedSmilParsesBackToTheSameSlides() {
        val req = MmsMessageBuilder.build(
            to = listOf("+15551234567"), text = "Look",
            attachments = listOf(
                MmsMessageBuilder.Attachment("image/jpeg", "a&b.jpg", byteArrayOf(1)),
                MmsMessageBuilder.Attachment("audio/amr", "note.amr", byteArrayOf(2)),
            ),
            transactionId = "T",
        )
        val smil = req.parts[0].text()!!
        val slides = assertNotNull(SmilPresentation.parse(smil))
        assertEquals(listOf(listOf("a_b.jpg", "text_0.txt"), listOf("note.amr")), slides.slides.map { s -> s.map { it.src } })
        // Every src resolves to a part of the message.
        val parts = req.parts.map { SmilPresentation.PartKey(it.contentId, it.contentLocation) }
        assertEquals(listOf(1, 3, 2, 0), SmilPresentation.order(smil, parts))
    }

    // --- Receive side -------------------------------------------------------------------------------------------

    @Test
    fun receiveOrderFollowsTheSmilNotThePdu() {
        val smil = """
            <?xml version="1.0"?>
            <!-- sender's layout -->
            <smil xmlns="http://www.w3.org/2001/SMIL20/Language">
              <head><layout><root-layout width="480" height="320"/><region id="Image"/></layout></head>
              <body>
                <par dur="3s"><img src="second.jpg" region="Image"/><text src="cap2.txt"/></par>
                <par dur="3s"><img src="cid:first@x"/><text src='cap1.txt'></text></par>
                <seq><audio src="tune.amr"/></seq>
              </body>
            </smil>
        """.trimIndent()
        val parts = listOf(
            SmilPresentation.PartKey(contentLocation = "smil.xml"),
            SmilPresentation.PartKey(contentId = "<first@x>", contentLocation = "first.jpg"),
            SmilPresentation.PartKey(contentLocation = "cap1.txt"),
            SmilPresentation.PartKey(contentLocation = "Second.JPG"),
            SmilPresentation.PartKey(contentLocation = "cap2.txt"),
            SmilPresentation.PartKey(contentLocation = "tune.amr"),
            SmilPresentation.PartKey(contentLocation = "extra.vcf"),
        )
        val slides = assertNotNull(SmilPresentation.parse(smil))
        assertEquals(3, slides.slides.size)
        // second.jpg matches Second.JPG ignoring case; unreferenced parts (the SMIL itself, extra.vcf) keep PDU order at the end.
        assertEquals(listOf(3, 4, 1, 2, 5, 0, 6), SmilPresentation.order(smil, parts))
    }

    @Test
    fun referencesResolveByNameFileNameContentIdAndEscapes() {
        val parts = listOf(
            SmilPresentation.PartKey(name = "photo one.jpg"),
            SmilPresentation.PartKey(fileName = "clip.mp4"),
            SmilPresentation.PartKey(contentId = "<t1>"),
        )
        val smil = "<smil><body><par><text src=\"t1\"/><video src=\"./clip.mp4\"/><img src=\"photo%20one.jpg\"/></par></body></smil>"
        assertEquals(listOf(2, 1, 0), SmilPresentation.order(smil, parts))
        val entities = "<smil><body><img src=\"a&amp;b.jpg\"/><img src=\"c&#46;jpg\"/></body></smil>"
        assertEquals(listOf(1, 0), SmilPresentation.order(entities, keys("c.jpg", "a&b.jpg")))
    }

    @Test
    fun eachPartIsPlacedOnceEvenWhenReferencedTwice() {
        val smil = "<smil><body><par><img src=\"b.jpg\"/></par><par><img src=\"b.jpg\"/><img src=\"a.jpg\"/></par></body></smil>"
        assertEquals(listOf(1, 0), SmilPresentation.order(smil, keys("a.jpg", "b.jpg")))
    }

    @Test
    fun missingInvalidOrUselessSmilKeepsPartOrder() {
        val parts = keys("a.jpg", "b.jpg", "c.txt")
        val identity = listOf(0, 1, 2)
        assertEquals(identity, SmilPresentation.order(null, parts))
        assertEquals(identity, SmilPresentation.order("", parts))
        assertEquals(identity, SmilPresentation.order("not xml at all", parts))
        assertEquals(identity, SmilPresentation.order("<smil><body><img src=\"b.jpg\"/>", parts), "unclosed")
        assertEquals(identity, SmilPresentation.order("<smil><body><img src=b.jpg/></body></smil>", parts), "unquoted")
        assertEquals(identity, SmilPresentation.order("<smil><body></par></body></smil>", parts), "mismatched")
        assertEquals(identity, SmilPresentation.order("<smil><body><img src=\"zzz.jpg\"/></body></smil>", parts), "nothing resolves")
        assertEquals(identity, SmilPresentation.order("<smil><head><img src=\"b.jpg\"/></head></smil>", parts), "no body")
        assertEquals(emptyList(), SmilPresentation.order("<smil><body><img src=\"b.jpg\"/></body></smil>", emptyList()))
    }

    // --- Hostile SMIL -------------------------------------------------------------------------------------------

    @Test
    fun externalEntitiesAndDtdsAreNeverInterpreted() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE smil [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
              <!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
            ]>
            <smil><body><par><img src="&xxe;"/><img src="&lol2;"/><img src="b.jpg"/></par></body></smil>
        """.trimIndent()
        val slides = assertNotNull(SmilPresentation.parse(xxe))
        // Unknown entities stay literal text: they cannot name a part, and nothing is read or expanded.
        assertEquals(listOf("&xxe;", "&lol2;", "b.jpg"), slides.refs.map { it.src })
        assertEquals(listOf(1, 0), SmilPresentation.order(xxe, keys("a.jpg", "b.jpg")))
    }

    @Test
    fun urlsAndPathsNeverMatchAnythingButALiteralPartLabel() {
        val smil = "<smil><body><img src=\"http://evil.example/x.jpg\"/><img src=\"file:///sdcard/a.jpg\"/>" +
            "<img src=\"content://mms/part/1\"/><img src=\"../a.jpg\"/></body></smil>"
        assertEquals(listOf(0), SmilPresentation.order(smil, keys("a.jpg")))
    }

    @Test
    fun limitsAreEnforced() {
        val deep = "<smil><body>" + "<par>".repeat(SmilPresentation.MAX_DEPTH + 1) + "<img src=\"a.jpg\"/>" +
            "</par>".repeat(SmilPresentation.MAX_DEPTH + 1) + "</body></smil>"
        assertNull(SmilPresentation.parse(deep))
        val many = "<smil><body>" + "<img src=\"a.jpg\"/>".repeat(SmilPresentation.MAX_ELEMENTS) + "</body></smil>"
        assertNull(SmilPresentation.parse(many))
        val huge = "<smil><body><img src=\"a.jpg\"/></body></smil>" + " ".repeat(SmilPresentation.MAX_SMIL_CHARS)
        assertNull(SmilPresentation.parse(huge))
        val longSrc = "<smil><body><img src=\"" + "a".repeat(MmsLimits.MAX_TOKEN_CHARS + 1) + "\"/></body></smil>"
        assertNull(SmilPresentation.parse(longSrc))
        val longName = "<smil><body><" + "x".repeat(100) + "/></body></smil>"
        assertNull(SmilPresentation.parse(longName))
        listOf("<!-- never closed", "<![CDATA[ open", "<!DOCTYPE [ <x>", "<?pi", "<smil a=\"x", "<smil ", "<", "</", "<smil/")
            .forEach { assertNull(SmilPresentation.parse(it), it) }
    }

    /** Random and mutated SMIL: parsing never throws and stays fast, and the order is always a permutation. */
    @Test
    fun fuzzedSmilNeverThrowsAndAlwaysYieldsAPermutation() {
        val random = Random(0x5111L)
        val seed = Smil.build(
            listOf(Smil.Item("a.jpg", Smil.Kind.IMAGE), Smil.Item("b.mp4", Smil.Kind.VIDEO), Smil.Item("t.txt", Smil.Kind.TEXT)),
        )
        val parts = keys("t.txt", "b.mp4", "a.jpg", "x.vcf")
        val alphabet = "<>/=\"'&;#!?-[]: abcdimgsrcparbodysmilxt.jpg%\n\u0000‮"
        val deadline = System.nanoTime() + 20_000_000_000L
        var iterations = 0
        while (iterations < 20_000 && System.nanoTime() < deadline) {
            val input = when (random.nextInt(3)) {
                0 -> mutate(seed, random)
                1 -> String(CharArray(random.nextInt(0, 400)) { alphabet[random.nextInt(alphabet.length)] })
                else -> mutate(mutate(seed, random), random) + seed.substring(random.nextInt(seed.length))
            }
            val start = System.nanoTime()
            val order = SmilPresentation.order(input, parts)
            val millis = (System.nanoTime() - start) / 1_000_000
            assertTrue(millis < 200, "slow SMIL ($millis ms): $input")
            assertEquals(parts.indices.toList(), order.sorted(), "not a permutation for: $input")
            iterations++
        }
        assertTrue(iterations > 1000)
    }

    private fun mutate(s: String, random: Random): String {
        val sb = StringBuilder(s)
        repeat(random.nextInt(1, 6)) {
            if (sb.isEmpty()) return@repeat
            val at = random.nextInt(sb.length)
            when (random.nextInt(4)) {
                0 -> sb.deleteCharAt(at)
                1 -> sb.insert(at, "<>\"'/&!-?"[random.nextInt(9)])
                2 -> sb.setCharAt(at, (random.nextInt(0x20, 0x7F)).toChar())
                else -> sb.insert(at, sb.substring(at, minOf(sb.length, at + random.nextInt(1, 40))))
            }
        }
        return sb.toString()
    }
}
