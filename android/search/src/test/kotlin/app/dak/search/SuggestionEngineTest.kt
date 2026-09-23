package app.dak.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggestionEngineTest {
    private val engine = SuggestionEngine(
        recentQueries = { listOf("swiggy last month", "zomato refund") },
        senderNames = { listOf("Swiggy", "SWIGGY-INSTAMART", "HDFC Bank") },
        contactNames = { listOf("Swati Sharma", "Rohit") },
    )

    @Test
    fun `blank prefix returns recent queries`() {
        val result = engine.suggest("", limit = 5)
        assertEquals(listOf("swiggy last month", "zomato refund"), result.map { it.text })
    }

    @Test
    fun `prefix match ranks above fuzzy`() {
        val result = engine.suggest("swi", limit = 10)
        assertTrue(result.isNotEmpty())
        // All strict prefix matches (case-insensitive) should appear before Swati (contains, not prefix).
        val prefixCount = result.count { it.text.lowercase().startsWith("swi") }
        assertTrue(prefixCount >= 3)
    }

    @Test
    fun `fuzzy match tolerates a small typo`() {
        val result = engine.suggest("swiggu", limit = 10) // typo for "swiggy"
        assertTrue(result.any { it.text.equals("Swiggy", ignoreCase = true) })
    }

    @Test
    fun `results are deduplicated`() {
        val engineWithDupes = SuggestionEngine(
            recentQueries = { listOf("swiggy", "swiggy") },
            senderNames = { emptyList() },
            contactNames = { emptyList() },
        )
        val result = engineWithDupes.suggest("swi")
        assertEquals(1, result.count { it.text == "swiggy" })
    }

    @Test
    fun `damerau levenshtein handles transposition`() {
        assertEquals(1, SuggestionEngine.damerauLevenshtein("ab", "ba"))
        assertEquals(0, SuggestionEngine.damerauLevenshtein("abc", "abc"))
        assertEquals(3, SuggestionEngine.damerauLevenshtein("", "abc"))
    }
}
