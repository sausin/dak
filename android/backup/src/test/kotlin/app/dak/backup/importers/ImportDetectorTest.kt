package app.dak.backup.importers

import app.dak.backup.xml.SmsBackupRestoreXmlImporter
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportDetectorTest {

    @Test
    fun `detects sms backup and restore xml`() {
        val importer = ImportDetector.detect("<?xml version=\"1.0\"?><smses count=\"1\">".toByteArray(), "backup.xml")
        assertIs<SmsBackupRestoreXmlImporter>(importer)
    }

    @Test
    fun `detects fossify json`() {
        val importer = ImportDetector.detect("""[{"address":"1","body":"x"}]""".toByteArray(), "messages.json")
        assertIs<FossifyImporter>(importer)
    }

    @Test
    fun `returns null for unrecognized content via detect`() {
        assertNull(ImportDetector.detect("random bytes".toByteArray(), "file.bin"))
    }

    @Test
    fun `falls back to the tolerant sms organizer importer`() {
        val detected = ImportDetector.detectOrFallback("""[{"foo":"bar"}]""".toByteArray(), "unknown.json")
        assertTrue(detected is DetectedImporter.SmsOrganizer)
    }

    @Test
    fun `detectOrFallback returns null for truly unrecognizable content`() {
        assertNull(ImportDetector.detectOrFallback("random bytes".toByteArray(), "file.bin"))
    }
}
