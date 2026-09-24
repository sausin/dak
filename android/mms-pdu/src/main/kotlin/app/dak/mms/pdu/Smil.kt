package app.dak.mms.pdu

/**
 * Minimal SMIL presentation generator for outgoing MMS (OMA MMS-CONF / 3GPP TS 26.140 SMIL profile): an Image region
 * on top and a Text region below in a [ROOT_WIDTH]×[ROOT_HEIGHT] root layout (the layout every MMS client renders),
 * and one `<par>` slide per media item. The text goes in the same slide as the first image or video (its caption),
 * else with the first other media item; a text-only message is one text slide. Receiving phones then show the caption
 * under the photo instead of on a slide of its own. See [SmilPresentation] for the receive side.
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

    /** Root layout size in pixels (portrait, like AOSP's default layout). */
    const val ROOT_WIDTH: Int = 320
    const val ROOT_HEIGHT: Int = 480

    fun build(items: List<Item>): String {
        val sb = StringBuilder()
        sb.append("<smil><head><layout>")
        sb.append("<root-layout width=\"").append(ROOT_WIDTH).append("\" height=\"").append(ROOT_HEIGHT).append("\"/>")
        sb.append("<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>")
        sb.append("<region id=\"Text\" fit=\"scroll\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>")
        sb.append("</layout></head><body>")
        for (slide in slides(items)) {
            sb.append("<par dur=\"").append(SLIDE_DURATION_MS).append("ms\">")
            for (item in slide) element(sb, item)
            sb.append("</par>")
        }
        sb.append("</body></smil>")
        return sb.toString()
    }

    /**
     * [items] grouped into slides: one per media item, and every text item joins the first image/video slide (else
     * the first media slide); with no media, one slide per text item. Within a slide the media comes before its text.
     */
    fun slides(items: List<Item>): List<List<Item>> {
        val media = items.filter { it.kind != Kind.TEXT }
        val texts = items.filter { it.kind == Kind.TEXT }
        if (media.isEmpty()) return texts.map { listOf(it) }
        val anchor = media.indexOfFirst { it.kind == Kind.IMAGE || it.kind == Kind.VIDEO }.coerceAtLeast(0)
        return media.mapIndexed { i, item -> if (i == anchor) listOf(item) + texts else listOf(item) }
    }

    private fun element(sb: StringBuilder, item: Item) {
        sb.append('<').append(item.kind.element).append(" src=\"").append(escape(item.src)).append('"')
        when (item.kind) {
            Kind.TEXT -> sb.append(" region=\"Text\"")
            Kind.IMAGE, Kind.VIDEO -> sb.append(" region=\"Image\"")
            Kind.AUDIO, Kind.OTHER -> Unit
        }
        sb.append("/>")
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
