package app.dak.backup.xml

import app.dak.backup.format.AttachmentRecord
import app.dak.backup.format.MessageRecord
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmsBackupRestoreXmlTest {

    @Test
    fun `round trips a plain sms`() {
        val record = MessageRecord(
            key = "sms:1",
            kind = MessageKind.SMS,
            threadId = 1,
            address = "VM-HDFCBK",
            body = "Your OTP is 482913. Valid for 10 mins.",
            dateMillis = 1_700_000_000_000L,
            subId = 1,
            box = MessageBox.INBOX,
            read = true,
        )
        val out = ByteArrayOutputStream()
        SmsBackupRestoreXmlExporter.write(out, sequenceOf(record), count = 1)

        val imported = SmsBackupRestoreXmlImporter().import(ByteArrayInputStream(out.toByteArray())).toList()
        assertEquals(1, imported.size)
        val m = imported.single()
        assertEquals(MessageKind.SMS, m.kind)
        assertEquals(record.address, m.address)
        assertEquals(record.body, m.body)
        assertEquals(record.dateMillis, m.dateMillis)
        assertEquals(record.box, m.box)
        assertEquals(record.subId, m.subId)
        assertEquals(record.read, m.read)
    }

    @Test
    fun `round trips body with newlines, entities and emoji`() {
        val body = "Line1\nLine2 <tag> & \"quoted\" 😀 rupee ₹500"
        val record = MessageRecord(
            key = "sms:2", kind = MessageKind.SMS, threadId = 1, address = "1234567890",
            body = body, dateMillis = 1L,
        )
        val out = ByteArrayOutputStream()
        SmsBackupRestoreXmlExporter.write(out, sequenceOf(record), count = 1)
        val imported = SmsBackupRestoreXmlImporter().import(ByteArrayInputStream(out.toByteArray())).toList()
        assertEquals(body, imported.single().body)
    }

    @Test
    fun `round trips an mms with an attachment`() {
        val attachmentBytes = "fake image bytes".toByteArray()
        val sha = "abc123"
        val record = MessageRecord(
            key = "mms:1", kind = MessageKind.MMS, threadId = 2, address = "9998887777",
            body = "check this out", dateMillis = 5L, box = MessageBox.INBOX,
            attachments = listOf(AttachmentRecord(mimeType = "image/jpeg", sha256 = sha, name = "photo.jpg")),
        )
        val out = ByteArrayOutputStream()
        SmsBackupRestoreXmlExporter.write(out, sequenceOf(record), count = 1) { s -> if (s == sha) attachmentBytes else null }

        val decoded = mutableListOf<ByteArray>()
        val imported = SmsBackupRestoreXmlImporter().importWithAttachments(ByteArrayInputStream(out.toByteArray())) { decoded += it }.toList()
        val m = imported.single()
        assertEquals(MessageKind.MMS, m.kind)
        assertEquals("check this out", m.body)
        assertEquals("9998887777", m.address)
        assertEquals(1, m.attachments.size)
        assertEquals("image/jpeg", m.attachments[0].mimeType)
        assertTrue(decoded.single().contentEquals(attachmentBytes))
    }

    @Test
    fun `sniff recognizes the format`() {
        val importer = SmsBackupRestoreXmlImporter()
        assertTrue(importer.sniff("<?xml version=\"1.0\"?><smses count=\"1\">".toByteArray(), "backup.xml"))
        assertTrue(importer.sniff("<smses count=\"0\"></smses>".toByteArray(), null))
        assertTrue(!importer.sniff("[{\"a\":1}]".toByteArray(), "export.json"))
    }
}
