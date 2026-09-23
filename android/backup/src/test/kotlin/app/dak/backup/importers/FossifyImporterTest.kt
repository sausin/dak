package app.dak.backup.importers

import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FossifyImporterTest {
    private val importer = FossifyImporter()

    @Test
    fun `parses a plain sms entry`() {
        val json = """
            [{"address":"1234567890","body":"hi there","date":1700000000000,"dateSent":1700000001000,
              "type":1,"subscriptionId":1,"read":true,"locked":false,"protocol":0,"serviceCenter":null}]
        """.trimIndent()
        val messages = importer.import(ByteArrayInputStream(json.toByteArray())).toList()
        assertEquals(1, messages.size)
        val m = messages.single()
        assertEquals(MessageKind.SMS, m.kind)
        assertEquals("1234567890", m.address)
        assertEquals("hi there", m.body)
        assertEquals(1700000000000L, m.dateMillis)
        assertEquals(1700000001000L, m.dateSentMillis)
        assertEquals(MessageBox.INBOX, m.box)
        assertEquals(1, m.subId)
        assertEquals(true, m.read)
    }

    @Test
    fun `parses an mms entry with parts and addresses`() {
        val json = """
            [{"backupType":"mms","date":5,"type":1,"read":false,
              "addresses":[{"address":"999","type":137}],
              "parts":[{"contentType":"text/plain","text":"caption"},
                       {"contentType":"image/png","name":"pic.png","data":"aGVsbG8="}]}]
        """.trimIndent()
        val messages = importer.import(ByteArrayInputStream(json.toByteArray())).toList()
        val m = messages.single()
        assertEquals(MessageKind.MMS, m.kind)
        assertEquals("999", m.address)
        assertEquals("caption", m.body)
        assertEquals(1, m.attachments.size)
        assertEquals("image/png", m.attachments[0].mimeType)
        assertEquals("hello", m.attachments[0].bytes!!().toString(Charsets.UTF_8))
    }

    @Test
    fun `ignores unknown fields and tolerates the older field shape`() {
        val json = """[{"address":"1","body":"b","date":"1","sub_id":"2","totallyUnknownField":{"nested":true}}]"""
        val messages = importer.import(ByteArrayInputStream(json.toByteArray())).toList()
        assertEquals(1, messages.size)
        assertEquals(2, messages.single().subId)
    }

    @Test
    fun `sniff recognizes a fossify-shaped json array`() {
        assertTrue(importer.sniff("""[{"address":"1","body":"x"}]""".toByteArray(), "messages.json"))
        assertTrue(!importer.sniff("<smses></smses>".toByteArray(), "backup.xml"))
    }

    @Test
    fun `skips entries with no usable address or date`() {
        val json = """[{"body":"no address or date"}]"""
        val messages = importer.import(ByteArrayInputStream(json.toByteArray())).toList()
        assertEquals(0, messages.size)
    }
}
