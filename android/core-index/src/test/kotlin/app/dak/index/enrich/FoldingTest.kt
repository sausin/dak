package app.dak.index.enrich

import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldingTest {

    private val brands = mapOf("HDFCBK" to "HDFCBK", "HDFC" to "HDFCBK", "HDFCBN" to "HDFCBK", "9223000000" to "9223000000")
    private val rules = GroupingRules(brandKey = { brands[it] })

    @Test
    fun `brand headers fold into the brand's primary header`() {
        assertEquals("m:HDFCBK", SenderGrouping.resolve("VM-HDFCBK", 1, rules).conversationId)
        assertEquals("m:HDFCBK", SenderGrouping.resolve("JD-HDFCBN-T", 2, rules).conversationId)
        assertEquals("m:HDFCBK", SenderGrouping.resolve("HDFC", 3, rules).conversationId)
        assertEquals("HDFCBK", SenderGrouping.resolve("HDFC", 3, rules).mergeKey)
        // A numeric long code the bundle lists folds too; unknown numbers keep their thread.
        assertEquals("m:9223000000", SenderGrouping.resolve("+919223000000", 4, rules).conversationId)
        assertEquals("t:5", SenderGrouping.resolve("+919876543210", 5, rules).conversationId)
        assertEquals("m:AMAZON", SenderGrouping.resolve("AX-AMAZON", 6, rules).conversationId)
    }

    @Test
    fun `unfold rule keeps a channel standalone`() {
        val unfolded = GroupingRules(folds = mapOf("HDFCBN" to "HDFCBN"), brandKey = { brands[it] })
        assertEquals("m:HDFCBN", SenderGrouping.resolve("VM-HDFCBN", 1, unfolded).conversationId)
        assertEquals("m:HDFCBK", SenderGrouping.resolve("VM-HDFCBK", 2, unfolded).conversationId)
        val numberUnfolded = GroupingRules(folds = mapOf("9223000000" to "9223000000"), brandKey = { brands[it] })
        assertEquals("t:4", SenderGrouping.resolve("+919223000000", 4, numberUnfolded).conversationId)
    }

    @Test
    fun `manual folds group numbers and headers`() {
        val manual = GroupingRules(folds = mapOf("9876543210" to "+9876543210", "9123456789" to "+9876543210"))
        assertEquals("m:+9876543210", SenderGrouping.resolve("+919876543210", 1, manual).conversationId)
        assertEquals("m:+9876543210", SenderGrouping.resolve("09123456789", 2, manual).conversationId)
        assertEquals("+9876543210", SenderGrouping.resolve("09123456789", 2, manual).mergeKey)
    }

    @Test
    fun `group MMS lists are never folded and legacy aliases still win`() {
        val all = GroupingRules(folds = mapOf("9876543210" to "+X"), aliases = mapOf("VM-HDFCBK" to "=VM-HDFCBK"), brandKey = { brands[it] })
        assertEquals("t:9", SenderGrouping.resolve("+919876543210 +919123456789", 9, all).conversationId)
        assertEquals("m:=VM-HDFCBK", SenderGrouping.resolve("vm-hdfcbk", 1, all).conversationId)
    }

    @Test
    fun `repeat rules`() {
        val hour = 3_600_000L
        assertTrue(RepeatRules.isRepeat("same", null, 0, "same", null, 23 * hour))
        assertFalse(RepeatRules.isRepeat("same", null, 0, "same", null, 25 * hour))
        assertTrue(RepeatRules.isRepeat("OTP 1234 for login", "1234", 0, "Your OTP is 1234", "1234", 5 * 60_000))
        assertFalse(RepeatRules.isRepeat("OTP 1234 for login", "1234", 0, "Your OTP is 1234", "1234", 11 * 60_000))
        // Template look-alikes with different data are not repeats.
        assertFalse(RepeatRules.isRepeat("Bal is Rs 5,000", null, 0, "Bal is Rs 4,200", null, hour))
        assertTrue(RepeatRules.eligible(MessageBox.INBOX, Category.TRANSACTION, "x"))
        assertFalse(RepeatRules.eligible(MessageBox.INBOX, Category.PERSONAL, "ok"))
        assertFalse(RepeatRules.eligible(MessageBox.SENT, Category.TRANSACTION, "x"))
        val a = MessageKey(MessageKind.SMS, 10)
        val b = MessageKey(MessageKind.SMS, 11)
        assertEquals("sms:11", RepeatRules.groupKeyOf(a, 200, b, 100))
        assertEquals("sms:10", RepeatRules.groupKeyOf(a, 100, b, 100))
    }

    private fun candidate(id: String, channels: Set<String>, brand: String? = null, last: Long = 0) =
        FoldCandidate(id, brand ?: channels.first(), channels, brand, last)

    @Test
    fun `suggests same brand and similar headers, skipping decided channels`() {
        val proposals = FoldSuggester.suggest(
            listOf(
                candidate("m:AMAZON", setOf("AMAZON"), last = 3),
                candidate("m:AMAZONIN", setOf("AMAZONIN"), last = 5),
                candidate("m:AMAZONPAY", setOf("AMAZONPAY"), last = 1),
                candidate("m:JIO", setOf("JIO"), brand = "Jio", last = 2),
                candidate("m:JIONET", setOf("JIONET"), brand = "Jio", last = 4),
                candidate("t:1", setOf("9876543210")),
                candidate("t:2", setOf("9876543211")),
            ),
            decidedChannels = emptySet(),
        )
        assertEquals(2, proposals.size)
        val brand = proposals.first { it.reason == FoldSuggestionReason.SAME_BRAND }
        assertEquals(listOf("m:JIONET", "m:JIO"), brand.conversationIds)
        assertEquals("Jio", brand.title)
        val similar = proposals.first { it.reason == FoldSuggestionReason.SIMILAR_HEADER }
        assertEquals(listOf("m:AMAZONIN", "m:AMAZON", "m:AMAZONPAY"), similar.conversationIds)

        val afterUnfold = FoldSuggester.suggest(
            listOf(candidate("m:AMAZON", setOf("AMAZON")), candidate("m:AMAZONIN", setOf("AMAZONIN"))),
            decidedChannels = setOf("AMAZONIN"),
        )
        assertTrue(afterUnfold.isEmpty())
    }
}
