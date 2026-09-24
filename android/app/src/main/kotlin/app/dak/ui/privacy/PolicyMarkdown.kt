package app.dak.ui.privacy

/**
 * The tiny Markdown subset the bundled privacy policy uses (`assets/privacy-policy.md`, a copy of
 * `docs/privacy-policy.md`): `#`/`##`/`###` headings, `- ` bullets, paragraphs, `**bold**` and `[text](url)` links.
 * Anything else renders as plain text, so the policy stays readable even if someone adds other syntax. Pure Kotlin;
 * no network and no WebView, so the policy works offline.
 */
internal object PolicyMarkdown {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class Bullet(val text: String) : Block
    }

    /** A run of inline text; [url] set for a link, [bold] for `**...**`. */
    data class Span(val text: String, val bold: Boolean = false, val url: String? = null)

    fun parse(markdown: String): List<Block> {
        val blocks = ArrayList<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            when {
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("<!--") -> Unit // comments (e.g. the sync note at the top) are not shown
                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.matchEntire(trimmed)!!
                    blocks += Block.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    flush()
                    blocks += Block.Bullet(trimmed.substring(2).trim())
                }
                // A continuation line of a bullet (indented) joins that bullet.
                line.startsWith("  ") && paragraph.isEmpty() && blocks.lastOrNull() is Block.Bullet -> {
                    val last = blocks.removeAt(blocks.size - 1) as Block.Bullet
                    blocks += Block.Bullet(last.text + " " + trimmed)
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
        }
        flush()
        return blocks
    }

    /** Splits inline text into plain, bold and link spans. Unclosed markers are kept as literal text. */
    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty()) out += Span(plain.toString())
            plain.clear()
        }
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    out += Span(text.substring(i + 2, end), bold = true)
                    i = end + 2
                    continue
                }
            }
            if (text[i] == '[') {
                val close = text.indexOf("](", i + 1)
                val end = if (close > i) text.indexOf(')', close + 2) else -1
                if (close > i && end > close) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, end)
                    if (url.startsWith("https://") || url.startsWith("mailto:")) {
                        flushPlain()
                        out += Span(label, url = url)
                        i = end + 1
                        continue
                    }
                }
            }
            if (text[i] == '`') {
                i++ // inline code marks are dropped, the text is kept
                continue
            }
            plain.append(text[i])
            i++
        }
        flushPlain()
        return out
    }

    private val HEADING = Regex("^(#{1,3})\\s+(.+)$")
}
