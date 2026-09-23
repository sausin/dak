package app.dak.automations.birthdays

/** A ready-made wish. [language] is a BCP-47 tag, for grouping in the picker. */
public data class WishTemplate(val id: String, val language: String, val text: String)

/**
 * Birthday/anniversary wish templates. Placeholders: `{firstName}`, `{name}` (full display name) and `{age}`
 * (empty when the birth year is unknown). Unknown placeholders pass through unchanged; the result is trimmed and
 * double spaces left by an empty placeholder are collapsed.
 */
public object WishTemplates {

    public val birthdayDefaults: List<WishTemplate> = listOf(
        WishTemplate("en_warm", "en", "Happy birthday, {firstName}! Wishing you a wonderful year ahead."),
        WishTemplate("en_short", "en", "Happy birthday {firstName}! 🎂"),
        WishTemplate("en_formal", "en", "Dear {name}, many happy returns of the day. Best wishes for the year ahead."),
        WishTemplate("hi_warm", "hi", "जन्मदिन की हार्दिक शुभकामनाएँ, {firstName}! आपका आने वाला साल खुशियों से भरा हो।"),
        WishTemplate("hi_latin", "hi-Latn", "Janamdin ki dher saari shubhkamnayein, {firstName}!"),
    )

    public val anniversaryDefaults: List<WishTemplate> = listOf(
        WishTemplate("en_anniv", "en", "Happy anniversary, {firstName}! Wishing you many more happy years together."),
        WishTemplate("hi_anniv", "hi", "सालगिरह की हार्दिक शुभकामनाएँ, {firstName}!"),
    )

    public val DEFAULT_BIRTHDAY: String = birthdayDefaults.first().text
    public val DEFAULT_ANNIVERSARY: String = anniversaryDefaults.first().text

    /** Fills the placeholders. A blank [firstName] falls back to the first word of [name]. */
    public fun render(template: String, name: String, firstName: String?, age: Int? = null): String {
        val first = firstName?.trim()?.takeIf { it.isNotEmpty() } ?: firstWord(name)
        val values = mapOf("firstName" to first, "name" to name.trim(), "age" to (age?.toString() ?: ""))
        val out = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val open = template.indexOf('{', i)
            val close = if (open >= 0) template.indexOf('}', open) else -1
            if (open < 0 || close < 0) {
                out.append(template, i, template.length)
                break
            }
            out.append(template, i, open)
            val key = template.substring(open + 1, close)
            out.append(values[key] ?: "{$key}")
            i = close + 1
        }
        return out.toString().replace(Regex(" {2,}"), " ").replace(" ,", ",").replace(" !", "!").trim()
    }

    /** First word of a display name ("Dr. Anita Rao" → "Anita": a leading title is skipped). */
    public fun firstWord(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val titles = setOf("mr", "mrs", "ms", "dr", "shri", "smt", "prof", "sri")
        return words.firstOrNull { it.trimEnd('.').lowercase() !in titles } ?: words.firstOrNull().orEmpty()
    }
}

/** What kind of contact event a wish is for. */
public enum class OccasionKind { BIRTHDAY, ANNIVERSARY }

/**
 * Tags a scheduled send as a birthday/anniversary wish, stored in the scheduled send's `ruleId` column so the
 * executor can dedupe (never twice in a year per contact) and honour "Ask me first":
 * `birthday:<ask|auto>:<contactId>:<kind>:<year>`.
 */
public data class WishTag(val ask: Boolean, val contactId: Long, val kind: OccasionKind, val year: Int) {

    /** Dedupe key: one wish per contact, occasion kind and year (for birthdays, effectively contactId+year). */
    val dedupeKey: String get() = "$contactId:${kind.name}:$year"

    public fun encode(): String = "$PREFIX${if (ask) "ask" else "auto"}:$contactId:${kind.name}:$year"

    public companion object {
        private const val PREFIX = "birthday:"

        /** Parses [ruleId], or null when it is not a wish tag. */
        public fun decode(ruleId: String?): WishTag? {
            if (ruleId == null || !ruleId.startsWith(PREFIX)) return null
            val parts = ruleId.removePrefix(PREFIX).split(':')
            if (parts.size != 4) return null
            val ask = when (parts[0]) { "ask" -> true; "auto" -> false; else -> return null }
            val contactId = parts[1].toLongOrNull() ?: return null
            val kind = OccasionKind.entries.firstOrNull { it.name == parts[2] } ?: return null
            val year = parts[3].toIntOrNull() ?: return null
            return WishTag(ask, contactId, kind, year)
        }
    }
}
