package app.dak.mms.pdu

/**
 * Minimal SMIL presentation generator for outgoing MMS: one `<par>` slide per media item, text last, with an
 * Image region on top and a Text region below (the layout every MMS client renders).
 */
object Smil {
    /** What kind of SMIL element references a part. */
    enum class Kind(internal val element: String) {
        TEXT("text"), IMAGE("img"), VIDEO("video"), AUDIO("audio"), OTHER("ref");

        companion object {
            /** Element kind for a MIME type. */
            fun forMimeType(mimeType: String): Kind {
                val m = mimeType.lowercase()
                return when {
                    m.startsWith("text/") -> TEXT
                    m.startsWith("image/") -> IMAGE
                    m.startsWith("video/") -> VIDEO
                    m.startsWith("audio/") -> AUDIO
                    else -> OTHER
                }
            }
        }
    }

    /** A reference from the presentation to a part, by its Content-Location. */
    data class Item(val src: String, val kind: Kind)

    /** Slide duration used for every `<par>`. */
    const val SLIDE_DURATION_MS: Int = 5000

    fun build(items: List<Item>): String {
        val sb = StringBuilder()
        sb.append("<smil><head><layout><root-layout/>")
        sb.append("<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>")
        sb.append("<region id=\"Text\" fit=\"scroll\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>")
        sb.append("</layout></head><body>")
        val ordered = items.filter { it.kind != Kind.TEXT } + items.filter { it.kind == Kind.TEXT }
        for (item in ordered) {
            sb.append("<par dur=\"").append(SLIDE_DURATION_MS).append("ms\">")
            sb.append('<').append(item.kind.element).append(" src=\"").append(escape(item.src)).append('"')
            when (item.kind) {
                Kind.TEXT -> sb.append(" region=\"Text\"")
                Kind.IMAGE, Kind.VIDEO -> sb.append(" region=\"Image\"")
                Kind.AUDIO, Kind.OTHER -> Unit
            }
            sb.append("/></par>")
        }
        sb.append("</body></smil>")
        return sb.toString()
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
