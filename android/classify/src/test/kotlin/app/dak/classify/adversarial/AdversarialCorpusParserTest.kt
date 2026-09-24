package app.dak.classify.adversarial

import app.dak.classify.adversarial.AdversarialCorpus.Expect
import app.dak.core.model.Category
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The corpus format itself (`shared/adversarial/README.md`): escapes, generators and strictness. */
class AdversarialCorpusParserTest {

    private fun parse(text: String, file: String = "xx.tsv"): Pair<List<AdversarialCorpus.Entry>, List<AdversarialCorpus.ParseError>> {
        val entries = ArrayList<AdversarialCorpus.Entry>()
        val errors = ArrayList<AdversarialCorpus.ParseError>()
        AdversarialCorpus.parse(file, text, entries, errors)
        return entries to errors
    }

    @Test
    fun `escapes decode to the characters they name`() {
        assertEquals("a\nb\tc\rd", AdversarialCorpus.decode("""a\nb\tc\rd"""))
        assertEquals("\u0000", AdversarialCorpus.decode("""\0"""))
        assertEquals("back\\slash", AdversarialCorpus.decode("""back\\slash"""))
        assertEquals("\u202Egnp.exe", AdversarialCorpus.decode("""\u202Egnp.exe"""))
        assertEquals("😀", AdversarialCorpus.decode("""\u{1F600}"""))
        assertEquals("x\uD800y", AdversarialCorpus.decode("""x\uD800y"""), "a lone surrogate is allowed on purpose")
        assertEquals("{firstName} {0} %s", AdversarialCorpus.decode("""{firstName} {0} %s"""), "braces are literal unless a generator")
        assertEquals("{repeat:\"a\":2}", AdversarialCorpus.decode("""\{repeat:"a":2}"""))
    }

    @Test
    fun `bad escapes are rejected`() {
        for (bad in listOf("""\q""", """trailing\""", """\u12""", """\uZZZZ""", """\u{}""", """\u{110000}""", """\u{1234567}""", """\"quote""")) {
            assertFailsWith<IllegalArgumentException>(bad) { AdversarialCorpus.decode(bad) }
        }
    }

    @Test
    fun `generators repeat their text`() {
        assertEquals("ababab!", AdversarialCorpus.decode("""{repeat:"ab":3}!"""))
        assertEquals("x" + "a\n".repeat(4) + "y", AdversarialCorpus.decode("""x{repeat:"a\n":4}y"""))
        assertEquals("\"q\"\"q\"", AdversarialCorpus.decode("""{repeat:"\"q\"":2}"""))
        assertEquals("", AdversarialCorpus.decode("""{repeat:"z":0}"""))
        assertEquals(1_000_000, AdversarialCorpus.decode("""{repeat:"a ":500000}""").length)
        assertEquals("\u202E".repeat(3), AdversarialCorpus.decode("""{repeat:"\u202E":3}"""))
    }

    @Test
    fun `malformed or oversized generators are rejected`() {
        for (bad in listOf(
            """{repeat:a:3}""",
            """{repeat:"a":}""",
            """{repeat:"a":3""",
            """{repeat:"a"3}""",
            """{repeat:"":3}""",
            """{repeat:"unterminated:3}""",
            """{repeat:"ab":1500000}""",
        )) {
            assertFailsWith<IllegalArgumentException>(bad) { AdversarialCorpus.decode(bad) }
        }
    }

    @Test
    fun `a well-formed file parses with its region, tags and expectations`() {
        val (entries, errors) = parse(
            "# comment\n\n#! region=IN\n" +
                "xx-one\t+919800000001\tscam,reason:return-request\tcontact,hinglish\tRs 500 galti se bheja\\n wapas karo\n" +
                "xx-two\tVM-HDFCBK-S\tnot-scam,otp:482913,category:otp\t-\t482913 is your OTP\r\n" +
                "  # indented comment\n" +
                "xx-three\t<empty>\tno-crash,amount:1500.5,txn:credit\tregion:GB,after:xx-one\tbody\n",
        )
        assertEquals(emptyList(), errors)
        assertEquals(3, entries.size)
        val (one, two, three) = entries
        assertEquals("IN", one.region)
        assertEquals("Rs 500 galti se bheja\n wapas karo", one.body)
        assertTrue(one.isContact)
        assertEquals(setOf("contact", "hinglish"), one.tags)
        assertIs<Expect.Reason>(one.expects[1])
        assertEquals(4, one.line)
        assertEquals("482913 is your OTP", two.body, "CRLF line endings are accepted")
        assertEquals(Category.OTP, (two.expects[2] as Expect.Category).category)
        assertEquals("", three.sender)
        assertEquals("GB", three.region)
        assertEquals("xx-one", three.after)
        assertEquals(150050L, (three.expects[1] as Expect.Amount).minor)
    }

    @Test
    fun `strict parser reports every problem with its line`() {
        val (entries, errors) = parse(
            "#! region=IN\n" +
                "xx-a\tS\tscam\t-\n" + // 4 fields
                "xx-b\tS\tscammy\t-\tbody\n" + // unknown expectation
                "xx-c\tS\treason:not-a-reason\t-\tbody\n" +
                "yy-d\tS\tscam\t-\tbody\n" + // wrong id prefix
                "xx-e\tS\tscam\tBad Tag\tbody\n" +
                "xx-f\tS\tscam\t-\tbad \\q escape\n" +
                "xx-g\tS\tamount:-5\t-\tbody\n" +
                "xx-h\tS\totp:12\t-\tbody\n" +
                "xx-i\tS\t\t-\tbody\n" +
                "#! region=GB\n",
        )
        assertEquals(emptyList(), entries)
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11), errors.map { it.line }.distinct())
        assertTrue(errors.all { it.file == "xx.tsv" })
    }

    @Test
    fun `raw invisible characters must be written as escapes`() {
        val (entries, errors) = parse("#! region=IN\nxx-a\tS\tscam\t-\tbody with a raw \u202E override\n")
        assertEquals(emptyList(), entries)
        assertTrue(errors.single().message.contains("U+202E"), errors.toString())
        val (ok, none) = parse("#! region=IN\nxx-a\tS\tscam\t-\tbody with an escaped \\u202E override and visible \u0939\u093F\u0902\u0926\u0940 \uD83D\uDE00\n")
        assertEquals(emptyList(), none)
        assertEquals("body with an escaped \u202E override and visible \u0939\u093F\u0902\u0926\u0940 \uD83D\uDE00", ok.single().body)
    }

    @Test
    fun `a file needs a region directive before its entries`() {
        val (_, errors) = parse("xx-a\tS\tscam\t-\tbody\n")
        assertTrue(errors.any { "directive" in it.message }, errors.toString())
        val (_, bad) = parse("#! region=india\n")
        assertTrue(bad.any { "region" in it.message }, bad.toString())
        val (_, none) = parse("#! region=none\nxx-a\tS\tscam\t-\tbody\n")
        assertEquals(emptyList(), none)
    }

    @Test
    fun `duplicate ids and dangling after-references are caught across files`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dak-adversarial-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(dir, "aa.tsv").writeText("#! region=IN\naa-one\tS\tscam\t-\tbody\naa-one\tS\tscam\t-\tbody\naa-two\tS\tscam\tafter:aa-nine\tbody\n")
            val result = AdversarialCorpus.load(dir)
            assertTrue(result.errors.any { "duplicate id 'aa-one'" in it.message }, result.errors.toString())
            assertTrue(result.errors.any { "after:aa-nine" in it.message }, result.errors.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `every expectation keyword is documented in the README`() {
        val readme = File(AdversarialCorpus.repoRoot(), "${AdversarialCorpus.DIR}/README.md").readText()
        val missing = AdversarialCorpus.EXPECT_KEYWORDS.filter { "`$it" !in readme }
        assertEquals(emptyList(), missing, "document these in ${AdversarialCorpus.DIR}/README.md")
        assertNull(AdversarialCorpus.parseExpect("category:nonsense"))
        assertIs<Expect.Links>(AdversarialCorpus.parseExpect("links:0"))
    }
}
