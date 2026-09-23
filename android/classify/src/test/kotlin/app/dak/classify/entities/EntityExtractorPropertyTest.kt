package app.dak.classify.entities

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Seeded random SMS-like bodies: spans are always sorted, disjoint, inside the body and quote the body's own text. */
class EntityExtractorPropertyTest {

    private val parts = listOf(
        "Rs", "₹", "INR", "OTP", "is", "your", "A/c", "XX1234", "ending", "PNR", "AWB", "Ref", "UTR", "@ybl", "@okicici",
        "upi", "https://", "www.", ".com", "bit.ly/", "@", "mail", "call", "+91", "Blue Dart", "Delhivery", "order", "#",
        "-", ".", ",", ":", "/", " ", "\n", "(", ")", "for", "at", "track",
    )

    @Test
    fun `spans are sorted, disjoint and quote the body`() {
        val r = Random(8080)
        repeat(3_000) {
            val sb = StringBuilder()
            repeat(r.nextInt(1, 25)) {
                if (r.nextInt(3) == 0) {
                    val zero = if (r.nextInt(10) == 0) '०' else '0'
                    repeat(r.nextInt(1, 14)) { sb.append(zero + r.nextInt(10)) }
                } else {
                    sb.append(parts[r.nextInt(parts.size)])
                }
                if (r.nextBoolean()) sb.append(' ')
            }
            val body = sb.toString()
            val region = listOf("IN", "GB", null, "zz", "")[r.nextInt(5)]
            val spans = EntityExtractor.extract(body, region)
            var lastEnd = 0
            for (s in spans) {
                assertTrue(s.start >= lastEnd && s.start < s.end && s.end <= body.length, "bad span $s in <$body>")
                assertEquals(body.substring(s.start, s.end), s.text, "span text differs in <$body>")
                assertTrue(s.value.isNotEmpty(), "empty value $s in <$body>")
                lastEnd = s.end
            }
        }
    }
}
