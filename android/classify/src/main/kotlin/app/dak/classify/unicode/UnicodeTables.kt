package app.dak.classify.unicode

/**
 * The generated Unicode tables in `src/main/resources/app/dak/classify/unicode/` (see
 * `android/classify/tools/gen_unicode_tables.py`, which records the source files, their Unicode version and hashes in
 * each file's header). Each table is parsed once, on first use, so the ASCII fast paths never load them.
 */
internal object UnicodeTables {

    /** UTS #46 status of a code point, nontransitional processing. */
    enum class IdnaStatus { VALID, MAPPED, DEVIATION, IGNORED, DISALLOWED }

    /** Range starts, sorted; range i covers `starts[i] until starts[i + 1]`. */
    private class IdnaTable(val starts: IntArray, val kinds: CharArray, val mappings: Array<String?>, val offsets: IntArray)

    private val idna: IdnaTable by lazy {
        val starts = ArrayList<Int>(6_200)
        val kinds = StringBuilder(6_200)
        val mappings = ArrayList<String?>(6_200)
        val offsets = ArrayList<Int>(6_200)
        lines("idna-mapping.txt") { fields ->
            starts += fields[0].toInt(16)
            val kind = fields[1][0]
            kinds.append(kind)
            when (kind) {
                'M' -> {
                    mappings += codePointsToString(fields, 2)
                    offsets += 0
                }
                'O' -> {
                    mappings += null
                    offsets += fields[2].toInt(16)
                }
                else -> {
                    mappings += null
                    offsets += 0
                }
            }
        }
        check(starts.isNotEmpty() && starts[0] == 0) { "idna-mapping.txt is empty or does not start at U+0000" }
        IdnaTable(starts.toIntArray(), kinds.toString().toCharArray(), mappings.toTypedArray(), offsets.toIntArray())
    }

    private fun idnaIndex(cp: Int): Int {
        val starts = idna.starts
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= cp) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun idnaStatus(cp: Int): IdnaStatus = when (idna.kinds[idnaIndex(cp)]) {
        'V' -> IdnaStatus.VALID
        'M', 'O' -> IdnaStatus.MAPPED
        'D' -> IdnaStatus.DEVIATION
        'I' -> IdnaStatus.IGNORED
        else -> IdnaStatus.DISALLOWED
    }

    /** The UTS #46 mapping of a [IdnaStatus.MAPPED] code point. */
    fun idnaMapping(cp: Int): String {
        val i = idnaIndex(cp)
        return when (idna.kinds[i]) {
            'M' -> idna.mappings[i]!!
            'O' -> String(Character.toChars(cp + idna.offsets[i]))
            else -> String(Character.toChars(cp))
        }
    }

    /** Joining_Type (`L`, `D`, `R`, `T`, or `U` for everything else) and the virama set, for CONTEXTJ. */
    private class ContextTable(val joinStarts: IntArray, val joinEnds: IntArray, val joinTypes: CharArray, val viramas: Set<Int>)

    private val context: ContextTable by lazy {
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val types = StringBuilder()
        val viramas = HashSet<Int>()
        lines("idna-context.txt") { fields ->
            when (fields[0]) {
                "J" -> {
                    starts += fields[1].toInt(16)
                    ends += fields[2].toInt(16)
                    types.append(fields[3][0])
                }
                "V" -> viramas += fields[1].toInt(16)
            }
        }
        ContextTable(starts.toIntArray(), ends.toIntArray(), types.toString().toCharArray(), viramas)
    }

    fun joiningType(cp: Int): Char {
        val t = context
        var lo = 0
        var hi = t.joinStarts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < t.joinStarts[mid] -> hi = mid - 1
                cp > t.joinEnds[mid] -> lo = mid + 1
                else -> return t.joinTypes[mid]
            }
        }
        return 'U'
    }

    fun isVirama(cp: Int): Boolean = cp in context.viramas

    /** UTS #39 confusables (MA), source code point -> prototype string, for the scripts listed in the file header. */
    val confusables: Map<Int, String> by lazy {
        val map = HashMap<Int, String>(4_096)
        lines("confusables.txt") { fields -> map[fields[0].toInt(16)] = codePointsToString(fields, 1) }
        map
    }

    private fun codePointsToString(fields: List<String>, from: Int): String = buildString {
        for (i in from until fields.size) appendCodePoint(fields[i].toInt(16))
    }

    private inline fun lines(name: String, onLine: (List<String>) -> Unit) {
        val stream = requireNotNull(UnicodeTables::class.java.getResourceAsStream("/app/dak/classify/unicode/$name")) {
            "missing resource app/dak/classify/unicode/$name"
        }
        stream.bufferedReader(Charsets.UTF_8).useLines { seq ->
            for (line in seq) {
                if (line.isEmpty() || line[0] == '#') continue
                onLine(line.split(' '))
            }
        }
    }
}
