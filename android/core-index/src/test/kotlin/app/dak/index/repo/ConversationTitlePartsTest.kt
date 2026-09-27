package app.dak.index.repo

import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.index.ContactLookup
import app.dak.index.NoContactLookup
import app.dak.index.db.dao.ConversationRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group titles are kept as their participants ([app.dak.index.ConversationSummary.titleParts]) so the app can join
 * them the way its language writes a list; [app.dak.index.ConversationSummary.title] stays a ", "-joined fallback.
 */
class ConversationTitlePartsTest {

    private val contacts = object : ContactLookup {
        private val names = mapOf("+911111111111" to "Asha", "+912222222222" to "Ravi")
        override fun displayName(address: String): String? = names[address]
        override fun isContact(address: String): Boolean = address in names
        override fun addressesMatching(nameQuery: String): List<String> = emptyList()
        override fun namesMatching(prefix: String, limit: Int): List<String> = emptyList()
    }

    private fun row(kind: MessageKind, address: String, canonical: String? = null, id: String = "t:1") = ConversationRow(
        conversationId = id, dateMillis = 0, kind = kind, providerId = 1, threadId = 1, address = address,
        mergeKey = address, canonicalSender = canonical, snippet = "", category = Category.PERSONAL,
        box = MessageBox.INBOX, hasAttachment = false, unreadCount = 0, messageCount = 1, subIds = null,
        threadIds = null, pinned = false, muted = false, archived = false, starred = false, groupName = null,
    )

    @Test
    fun groupMmsKeepsParticipantsInOrder() {
        val s = Mappers.conversationSummary(row(MessageKind.MMS, "+911111111111 +913333333333 +912222222222"), contacts)
        assertEquals(listOf("Asha", "+913333333333", "Ravi"), s.titleParts)
        assertEquals("Asha, +913333333333, Ravi", s.title)
    }

    @Test
    fun groupWithoutContactsListsAddresses() {
        val s = Mappers.conversationSummary(row(MessageKind.MMS, "+913333333333 +914444444444"), NoContactLookup)
        assertEquals(listOf("+913333333333", "+914444444444"), s.titleParts)
        assertEquals("+913333333333 +914444444444", s.title)
    }

    @Test
    fun singleSenderHasNoParts() {
        val s = Mappers.conversationSummary(row(MessageKind.SMS, "+911111111111"), contacts)
        assertTrue(s.titleParts.isEmpty())
        assertEquals("Asha", s.title)
        val unknown = Mappers.conversationSummary(row(MessageKind.MMS, "Unknown"), contacts)
        assertTrue(unknown.titleParts.isEmpty())
        assertEquals("Unknown", unknown.title) // the app shows `unknown_sender` for it
    }

    @Test
    fun noneResolvedIsNull() {
        assertNull(Mappers.titlePartsForAddresses(listOf("+913333333333"), contacts))
        assertEquals(listOf("Asha"), Mappers.titlePartsForAddresses(listOf("+911111111111"), contacts))
    }
}
