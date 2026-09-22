package app.dak.telephony.sim

import app.dak.core.model.SimInfo

/**
 * Line-per-SIM text encoding for the remembered-SIM list (SharedPreferences), so removed SIMs keep their name,
 * colour and country after they leave the device. Fields are `|`-separated with `\` escapes.
 */
internal object SimInfoCodec {
    private const val FIELDS = 9

    fun encode(sims: List<SimInfo>): String = sims.joinToString("\n") { s ->
        listOf(
            s.subId.toString(),
            s.slotIndex.toString(),
            s.displayName,
            s.carrierName.orEmpty(),
            s.countryIso.orEmpty(),
            s.colorArgb.toString(),
            s.number.orEmpty(),
            if (s.isEmbedded) "1" else "0",
            if (s.isActive) "1" else "0",
        ).joinToString("|") { escape(it) }
    }

    fun decode(value: String?): List<SimInfo> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split('\n').mapNotNull { line -> decodeLine(line) }
    }

    private fun decodeLine(line: String): SimInfo? {
        val f = splitEscaped(line)
        if (f.size != FIELDS) return null
        val subId = f[0].toIntOrNull() ?: return null
        return SimInfo(
            subId = subId,
            slotIndex = f[1].toIntOrNull() ?: -1,
            displayName = f[2],
            carrierName = f[3].ifEmpty { null },
            countryIso = f[4].ifEmpty { null },
            colorArgb = f[5].toIntOrNull() ?: 0,
            number = f[6].ifEmpty { null },
            isEmbedded = f[7] == "1",
            isActive = f[8] == "1",
        )
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '|' -> append("\\p")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    private fun splitEscaped(line: String): List<String> {
        val out = ArrayList<String>(FIELDS)
        val cur = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                when (line[i + 1]) {
                    'p' -> cur.append('|')
                    'n' -> cur.append('\n')
                    'r' -> cur.append('\r')
                    else -> cur.append(line[i + 1])
                }
                i += 2
                continue
            }
            if (c == '|') {
                out.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}
