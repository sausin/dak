package app.dak.backup.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersonalDataExportTest {

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                out[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        return out
    }

    @Test
    fun `writes sections, readme and a manifest with hashes and counts`() {
        val buffer = ByteArrayOutputStream()
        val manifest = PersonalDataExportWriter(buffer).use { w ->
            w.writeJson("settings", "Your settings", """{"a":"1"}""")
            w.writeJson("automation_rules", "Your rules", """[{"id":"r1"},{"id":"r2"}]""", items = 2)
            w.writeJsonLines("automation_runs", "Run history", sequenceOf("""{"x":1}""", """{"x":2}""", """{"x":3}"""))
            w.finish(createdAt = 5, appVersion = "1.0", readme = "hello", notIncluded = listOf("System SMS store"))
        }
        val files = entries(buffer.toByteArray())
        assertEquals(
            listOf("data/settings.json", "data/automation_rules.json", "data/automation_runs.jsonl", "README.txt", "manifest.json"),
            files.keys.toList(),
        )
        assertEquals(3, manifest.parts.first { it.name == "data/automation_runs.jsonl" }.items)
        assertEquals(2, manifest.parts.first { it.name == "data/automation_rules.json" }.items)
        manifest.parts.forEach { part ->
            val bytes = files.getValue(part.name)
            assertEquals(Hashing.sha256Hex(bytes), part.sha256, part.name)
            assertEquals(bytes.size.toLong(), part.sizeBytes, part.name)
        }
        val written = Json.decodeFromString(PersonalDataManifest.serializer(), files.getValue("manifest.json").toString(Charsets.UTF_8))
        assertEquals(manifest, written)
        assertEquals(PERSONAL_DATA_FORMAT_NAME, written.format)
        assertEquals(listOf("System SMS store"), written.notIncluded)
        assertEquals("hello", files.getValue("README.txt").toString(Charsets.UTF_8))
    }

    @Test
    fun `section names cannot become paths or repeat`() {
        PersonalDataExportWriter(ByteArrayOutputStream()).use { w ->
            listOf("../etc", "a/b", "", "Upper", "x.json", "a b").forEach { bad ->
                assertFailsWith<IllegalArgumentException>(bad) { w.writeJson(bad, "d", "{}") }
            }
            w.writeJson("ok", "d", "{}")
            assertFailsWith<IllegalArgumentException> { w.writeJsonLines("ok", "d", emptySequence()) }
        }
    }

    @Test
    fun `json lines are capped and must be single lines`() {
        val buffer = ByteArrayOutputStream()
        PersonalDataExportWriter(buffer).use { w ->
            w.writeJsonLines("rows", "d", generateSequence(0) { it + 1 }.map { """{"i":$it}""" }, maxLines = 10)
            val m = w.finish(1, "t", "r")
            assertEquals(10, m.parts.single().items)
        }
        PersonalDataExportWriter(ByteArrayOutputStream()).use { w ->
            assertFailsWith<IllegalArgumentException> { w.writeJsonLines("bad", "d", sequenceOf("{}\n{}")) }
        }
    }

    @Test
    fun `nothing can be written after finish`() {
        PersonalDataExportWriter(ByteArrayOutputStream()).use { w ->
            w.finish(1, "t", "r")
            assertFailsWith<IllegalStateException> { w.writeJson("late", "d", "{}") }
        }
    }
}
