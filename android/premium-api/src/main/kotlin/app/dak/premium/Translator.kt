package app.dak.premium

/** On-device translation (ML Kit in premium). Free binds [NoOpTranslator]. */
interface Translator {
    val isAvailable: Boolean
    /** BCP-47 tag or null if undetermined. */
    suspend fun detectLanguage(text: String): String?
    suspend fun translate(text: String, targetLanguage: String): String?
}

object NoOpTranslator : Translator {
    override val isAvailable: Boolean = false
    override suspend fun detectLanguage(text: String): String? = null
    override suspend fun translate(text: String, targetLanguage: String): String? = null
}

/**
 * Premium AI-assisted search: turns a natural-language question into the structured query language
 * understood by `:search` (e.g. "from:swiggy category:transaction during:\"last month\""). Only the query
 * text is ever sent; never message content.
 */
interface QueryUnderstanding {
    val isAvailable: Boolean
    suspend fun toStructuredQuery(naturalLanguage: String): String?
}

object NoOpQueryUnderstanding : QueryUnderstanding {
    override val isAvailable: Boolean = false
    override suspend fun toStructuredQuery(naturalLanguage: String): String? = null
}
