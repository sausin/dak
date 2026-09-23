package app.dak.backup.importers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmsOrganizerImporterTest {
    private val importer = SmsOrganizerImporter()

    @Test
    fun `parses a plain json array with lowercase-insensitive keys`() {
        val json = """[{"Address":"12345","Body":"hi","Date":1700000000000,"Type":"inbox"}]"""
        val result = importer.import(ByteArrayInputStream(json.toByteArray()))
        assertEquals(1, result.messages.size)
        assertEquals("12345", result.messages.single().address)
        assertEquals("hi", result.messages.single().body)
        assertEquals(1700000000000L, result.messages.single().dateMillis)
    }

    @Test
    fun `accepts epoch seconds as well as milliseconds`() {
        val json = """[{"address":"1","body":"a","date":1700000000}]""" // 10-digit: seconds
        val result = importer.import(ByteArrayInputStream(json.toByteArray()))
        assertEquals(1700000000000L, result.messages.single().dateMillis)
    }

    @Test
    fun `parses a wrapper object with a messages array`() {
        val json = """{"messages":[{"sender":"foo","message":"bar","timestamp":1000000000000}]}"""
        val result = importer.import(ByteArrayInputStream(json.toByteArray()))
        assertEquals(1, result.messages.size)
        assertEquals("foo", result.messages.single().address)
        assertEquals("bar", result.messages.single().body)
    }

    @Test
    fun `unwraps a zip and finds the first json entry`() {
        val zipBytes = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry("readme.txt"))
                zip.write("not json".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("backup.json"))
                zip.write("""[{"address":"1","body":"from zip","date":1}]""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val result = importer.import(ByteArrayInputStream(zipBytes))
        assertEquals(1, result.messages.size)
        assertEquals("from zip", result.messages.single().body)
    }

    @Test
    fun `skips unparseable entries and reports them instead of failing outright`() {
        val json = """[{"address":"1","body":"ok","date":1}, {"nothingUseful":true}, "not even an object"]"""
        val result = importer.import(ByteArrayInputStream(json.toByteArray()))
        assertEquals(1, result.messages.size)
        assertEquals(2, result.skipped)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `sniff is permissive about json and zip`() {
        assertTrue(importer.sniff("[1,2,3]".toByteArray()))
        assertTrue(importer.sniff(byteArrayOf(0x50, 0x4B, 3, 4)))
        assertTrue(!importer.sniff("plain text".toByteArray()))
    }
}
