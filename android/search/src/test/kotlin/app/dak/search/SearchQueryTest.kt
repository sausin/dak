package app.dak.search

import app.dak.core.model.Category
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchQueryTest {
    private val now = ZonedDateTime.of(2026, 9, 22, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    private fun roundTrip(input: String) {
        val first = QueryParser.parse(input, now)
        val second = QueryParser.parse(first.toQueryString(), now)
        assertEquals(first, second, "round trip mismatch for '$input' -> '${first.toQueryString()}'")
    }

    @Test
    fun `round trips simple filters`() {
        roundTrip("from:hdfc")
        roundTrip("from:\"Amit Kumar\"")
        roundTrip("category:transaction")
        roundTrip("sim:1")
        roundTrip("sim:Jio")
        roundTrip("has:attachment")
        roundTrip("has:link")
        roundTrip("has:otp")
        roundTrip("in:archive")
        roundTrip("in:bin")
        roundTrip("in:inbox")
        roundTrip("is:starred")
        roundTrip("is:unread")
        roundTrip("is:read")
    }

    @Test
    fun `round trips amount filters`() {
        roundTrip("amount:>500")
        roundTrip("amount:<100")
        roundTrip("amount:100..500")
        roundTrip("amount:=250")
    }

    @Test
    fun `round trips date filters`() {
        roundTrip("before:2026-01-15")
        roundTrip("after:2026-01-15")
        roundTrip("during:today")
        roundTrip("during:\"last week\"")
    }

    @Test
    fun `round trips negated filter`() {
        roundTrip("-from:spammer")
        roundTrip("-category:spam")
    }

    @Test
    fun `round trips free text combinations`() {
        roundTrip("swiggy zomato")
        roundTrip("swiggy OR zomato")
        roundTrip("\"order confirmed\"")
        roundTrip("-spam")
    }

    @Test
    fun `chips expose label and filter`() {
        val q = SearchQuery(null, listOf(Filter.CategoryIs(Category.OTP), Filter.IsUnread))
        val chips = q.chips()
        assertEquals(2, chips.size)
        assertEquals("Category: Otp", chips[0].label)
        assertEquals(Filter.IsUnread, chips[1].filter)
    }

    @Test
    fun `withFilter is idempotent and withoutFilter removes`() {
        val base = SearchQuery.EMPTY
        val withStarred = base.withFilter(Filter.IsStarred)
        assertEquals(listOf(Filter.IsStarred), withStarred.filters)
        assertEquals(withStarred, withStarred.withFilter(Filter.IsStarred))
        assertTrue(withStarred.withoutFilter(Filter.IsStarred).filters.isEmpty())
        assertFalse(withStarred.withoutFilter(Filter.IsUnread).filters.isEmpty())
    }
}
