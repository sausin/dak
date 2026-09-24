package app.dak.ui.conversation

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class MmsPartNamesTest {

    @Test
    fun extensionFollowsTheFinalContentType() {
        // Transcoded voice note: recorded as AMR / WAV, sent as AAC in MP4.
        assertEquals("voice.m4a", MmsPartNames.fileName(0, "voice.amr", "audio/mp4"))
        assertEquals("memo.m4a", MmsPartNames.fileName(0, "memo.wav", "audio/mp4"))
        // Transcoded video: whatever it was, it is H.264 MP4 now.
        assertEquals("clip.mp4", MmsPartNames.fileName(1, "clip.mov", "video/mp4"))
        // Re-encoded photo.
        assertEquals("IMG_2041.jpg", MmsPartNames.fileName(0, "IMG_2041.HEIC", "image/jpeg"))
        // Untouched 3GP video keeps a 3GP extension (no longer forced to .mp4).
        assertEquals("old.3gp", MmsPartNames.fileName(0, "old.mp4", "video/3gpp"))
    }

    @Test
    fun contentTypeParametersAndCaseAreIgnored() {
        assertEquals("m4a", MmsPartNames.extensionFor("Audio/MP4; codecs=mp4a.40.2"))
        assertEquals("vcf", MmsPartNames.extensionFor("text/x-vcard"))
        assertEquals("vcf", MmsPartNames.extensionFor("text/vcard"))
        assertNull(MmsPartNames.extensionFor("application/x-unknown"))
    }

    @Test
    fun unknownTypesKeepASanitisedOriginalExtension() {
        assertEquals("notes.odt", MmsPartNames.fileName(0, "notes.odt", "application/vnd.oasis.opendocument.text"))
        assertEquals("x.exe", MmsPartNames.fileName(0, "x.e/x\\e", "application/octet-stream"))
        assertEquals("part3.bin", MmsPartNames.fileName(2, null, "application/octet-stream"))
        assertEquals("part1.bin", MmsPartNames.fileName(0, "???", "application/octet-stream"))
    }
}
