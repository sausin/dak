package app.dak.ui.conversation

/**
 * File names Dak makes up for attachments that have none: a camera photo ([CAMERA_PHOTO]), a contact card without
 * a name ([CONTACT_STEM]`.vcf`) and an MMS part without a name (`part1.jpg`, [MmsPartNames]). They are machine names
 * (the MMS still carries them) but English, so the UI shows "Photo" / "Contact card" / "Attachment" in the app
 * language instead of them ([isGenerated]).
 */
internal object AttachmentNames {
    const val CAMERA_PHOTO: String = "photo.jpg"
    const val CONTACT_STEM: String = "contact"

    private val generated = Regex("(?i)(photo|$CONTACT_STEM|part\\d+)(\\.[a-z0-9]{1,8})?")

    /** True for a missing name or one Dak generated; show a localized label instead. */
    fun isGenerated(name: String?): Boolean = name.isNullOrBlank() || generated.matches(name.trim())

    /** [name] when it is a real one worth showing, else null. */
    fun shown(name: String?): String? = name?.takeUnless { isGenerated(it) }
}
