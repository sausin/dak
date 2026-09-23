package app.dak.backup.importers

import app.dak.backup.xml.SmsBackupRestoreXmlImporter

/**
 * Picks the right [Importer] for a file, given the first bytes and (if available) its name.
 * Checked in order from most to least specific, since [SmsOrganizerImporter]'s format is
 * undocumented and its [SmsOrganizerImporter.sniff] is deliberately permissive.
 */
object ImportDetector {
    private val specific: List<Importer> = listOf(SmsBackupRestoreXmlImporter(), FossifyImporter())

    /** Returns the best-matching specific [Importer] (SMS Backup & Restore XML or Fossify), or null. */
    fun detect(headerBytes: ByteArray, fileName: String? = null): Importer? =
        specific.firstOrNull { it.sniff(headerBytes, fileName) }

    /**
     * Like [detect], but falls back to the tolerant [SmsOrganizerImporter] if no specific format
     * matched and the bytes look like a JSON document or a ZIP at all. Returns null only when
     * nothing recognisable was found.
     */
    fun detectOrFallback(headerBytes: ByteArray, fileName: String? = null): DetectedImporter? {
        detect(headerBytes, fileName)?.let { return DetectedImporter.Specific(it) }
        val organizer = SmsOrganizerImporter()
        return if (organizer.sniff(headerBytes, fileName)) DetectedImporter.SmsOrganizer(organizer) else null
    }
}

/** Result of [ImportDetector.detectOrFallback]: either a precise [Importer] or the tolerant SMS Organizer path. */
sealed class DetectedImporter {
    data class Specific(val importer: Importer) : DetectedImporter()
    data class SmsOrganizer(val importer: SmsOrganizerImporter) : DetectedImporter()
}
