package app.dak.settings

import app.dak.premium.Feature

private fun boolSetting(
    key: String,
    group: SettingsGroup,
    title: String,
    summary: String,
    default: Boolean,
    keywords: List<String> = emptyList(),
    tier: SettingTier = SettingTier.Free,
    advanced: Boolean = false,
    visible: (DeviceContext) -> Boolean = { true },
) = SettingDef(
    key = key, group = group, title = title, summary = summary, control = ControlType.Toggle,
    default = default, keywords = keywords, tier = tier, advanced = advanced, visible = visible,
    serialize = { it.toString() }, deserialize = { it.toBooleanStrictOrNull() },
)

private fun textSetting(
    key: String,
    group: SettingsGroup,
    title: String,
    summary: String,
    default: String,
    keywords: List<String> = emptyList(),
    tier: SettingTier = SettingTier.Free,
    advanced: Boolean = false,
    visible: (DeviceContext) -> Boolean = { true },
) = SettingDef(
    key = key, group = group, title = title, summary = summary, control = ControlType.Text,
    default = default, keywords = keywords, tier = tier, advanced = advanced, visible = visible,
    serialize = { it }, deserialize = { it },
)

private fun intSetting(
    key: String,
    group: SettingsGroup,
    title: String,
    summary: String,
    default: Int,
    range: IntRange,
    step: Int = 1,
    keywords: List<String> = emptyList(),
    tier: SettingTier = SettingTier.Free,
    advanced: Boolean = false,
    visible: (DeviceContext) -> Boolean = { true },
) = SettingDef(
    key = key, group = group, title = title, summary = summary,
    control = ControlType.Slider(range, step), default = default, keywords = keywords, tier = tier,
    advanced = advanced, visible = visible,
    // Out-of-range values (a hand-edited or older export, a corrupt store) read as unset rather than bypassing the
    // slider's bounds, e.g. a 0-minute consumed-OTP window or an unlimited cloud-classification cap.
    serialize = { it.toString() }, deserialize = { raw -> raw.toIntOrNull()?.takeIf { it in range } },
)

private fun choiceSetting(
    key: String,
    group: SettingsGroup,
    title: String,
    summary: String,
    default: String,
    options: List<ChoiceOption>,
    keywords: List<String> = emptyList(),
    tier: SettingTier = SettingTier.Free,
    advanced: Boolean = false,
    visible: (DeviceContext) -> Boolean = { true },
) = SettingDef(
    key = key, group = group, title = title, summary = summary,
    control = ControlType.SingleChoice(options), default = default, keywords = keywords, tier = tier,
    advanced = advanced, visible = visible,
    serialize = { it }, deserialize = { v -> options.map { it.value }.firstOrNull { it == v } },
)

private fun actionSetting(
    key: String,
    group: SettingsGroup,
    title: String,
    summary: String,
    keywords: List<String> = emptyList(),
    tier: SettingTier = SettingTier.Free,
    advanced: Boolean = false,
    visible: (DeviceContext) -> Boolean = { true },
) = SettingDef(
    key = key, group = group, title = title, summary = summary, control = ControlType.Action,
    default = "", keywords = keywords, tier = tier, advanced = advanced, visible = visible,
    serialize = { it }, deserialize = { it },
)

/**
 * Every setting implied by the build plan's "Proposed groups" table and the feature sections it
 * points at (Notifications, OTP lifecycle and recycle bin, Finance ledger, Roaming and language,
 * Relay rules, Search). Grouped and ordered to match the spec. See [SettingsGroup] for group
 * order and each group's "Advanced block" split (`advanced = true`).
 */
object DakSettings {

    // ---------------------------------------------------------------- Notifications
    val perCategoryAlerts = actionSetting(
        "notifications.perCategoryAlerts", SettingsGroup.NOTIFICATIONS,
        "Alerts per category", "Choose which categories notify you, and how loudly.",
        keywords = listOf("personal", "transaction", "otp", "promotion", "spam", "sound"),
    )
    val notificationChannels = actionSetting(
        "notifications.channels", SettingsGroup.NOTIFICATIONS,
        "Notification channels",
        "Channels per category and SIM, custom conversation channels, and reset.",
        keywords = listOf("channel", "sound", "vibration", "importance", "sim", "conversation", "priority"),
    )
    val otpDisplaySize = choiceSetting(
        "notifications.otpDisplaySize", SettingsGroup.NOTIFICATIONS,
        "OTP display size", "How large the code appears on the notification and lock screen.",
        default = "large",
        options = listOf(ChoiceOption("normal", "Normal"), ChoiceOption("large", "Large")),
        keywords = listOf("one time password", "code", "verification", "text size"),
    )
    val otpAutoCopy = boolSetting(
        "notifications.otpAutoCopy", SettingsGroup.NOTIFICATIONS,
        "Copy OTPs automatically",
        "Put a new code on the clipboard as it arrives, hidden from clipboard previews. Off: a Copy button instead.",
        default = true,
        keywords = listOf("one time password", "otp", "code", "copy", "clipboard", "verification", "paste"),
    )
    val otpAutoDelete = choiceSetting(
        "notifications.otpAutoDelete", SettingsGroup.NOTIFICATIONS,
        "OTP auto-delete", "Remove OTP messages automatically after they arrive.",
        default = "24h",
        options = listOf(ChoiceOption("1h", "1 hour"), ChoiceOption("24h", "24 hours"), ChoiceOption("off", "Off")),
        keywords = listOf("one time password", "code", "verification", "auto delete", "expire"),
    )
    val consumedOtpHandling = choiceSetting(
        "notifications.consumedOtpHandling", SettingsGroup.NOTIFICATIONS,
        "Consumed OTP handling",
        "How to notify for an OTP an app already read automatically.",
        default = "silentAutoDelete",
        options = listOf(
            ChoiceOption("silentAutoDelete", "Silent, auto-delete"),
            ChoiceOption("silentOnly", "Silent only"),
            ChoiceOption("normal", "Treat as normal"),
        ),
        keywords = listOf("one time password", "sms retriever", "webotp", "silent"),
        advanced = true,
    )
    val consumedOtpWindowMinutes = intSetting(
        "notifications.consumedOtpWindowMinutes", SettingsGroup.NOTIFICATIONS,
        "Consumed OTP delete window",
        "Minutes to wait before deleting a consumed OTP, so a retrying app can still fetch it.",
        default = 10, range = 5..60, step = 5,
        keywords = listOf("one time password", "consumed", "delay"),
        advanced = true,
    )
    val quickActions = boolSetting(
        "notifications.quickActions", SettingsGroup.NOTIFICATIONS,
        "Quick actions", "Show reply, archive and delete actions on notifications.",
        default = true,
    )
    val selfTest = actionSetting(
        "notifications.selfTest", SettingsGroup.NOTIFICATIONS,
        "Notification self-test", "Send a test notification to check sound, heads-up and bubbles.",
    )
    val soundPerSim = actionSetting(
        "notifications.soundPerSim", SettingsGroup.NOTIFICATIONS,
        "Sound per SIM", "Choose a different notification sound for each SIM.",
        advanced = true, visible = { it.simCount > 1 },
    )
    val bubbles = boolSetting(
        "notifications.bubbles", SettingsGroup.NOTIFICATIONS,
        "Bubbles", "Show conversations as floating bubbles over other apps.",
        default = false, advanced = true,
    )
    val lockScreenPrivacy = choiceSetting(
        "notifications.lockScreenPrivacy", SettingsGroup.NOTIFICATIONS,
        "Lock-screen privacy", "How much of a message shows on the lock screen.",
        default = "hideContent",
        options = listOf(
            ChoiceOption("full", "Full message"),
            ChoiceOption("hideContent", "Sender only"),
            ChoiceOption("hideAll", "Hide sender and message"),
        ),
        keywords = listOf("lock screen", "privacy", "preview"),
        advanced = true,
    )

    // ---------------------------------------------------------------- Categories and spam
    val tabSet = actionSetting(
        "categoriesSpam.tabSet", SettingsGroup.CATEGORIES_SPAM,
        "Inbox tabs", "Choose which category tabs appear on the inbox.",
    )
    val swipeRight = choiceSetting(
        "categoriesSpam.swipeRight", SettingsGroup.CATEGORIES_SPAM,
        "Swipe right on a conversation", "What swiping a conversation to the right does in the inbox.",
        default = SwipeActions.ARCHIVE, options = SwipeActions.options,
        keywords = listOf("swipe", "gesture", "archive", "delete", "pin", "read"),
    )
    val swipeLeft = choiceSetting(
        "categoriesSpam.swipeLeft", SettingsGroup.CATEGORIES_SPAM,
        "Swipe left on a conversation", "What swiping a conversation to the left does in the inbox. Deleting offers undo.",
        default = SwipeActions.DELETE, options = SwipeActions.options,
        keywords = listOf("swipe", "gesture", "archive", "delete", "pin", "read", "bin"),
    )
    val inboxOtpCopy = boolSetting(
        "categoriesSpam.inboxOtpCopy", SettingsGroup.CATEGORIES_SPAM,
        "Copy code from the inbox", "Show a \"Copy code\" button on conversations with an OTP from the last 10 minutes.",
        default = true,
        keywords = listOf("one time password", "otp", "code", "copy", "verification"),
    )
    val senderMerges = actionSetting(
        "categoriesSpam.senderMerges", SettingsGroup.CATEGORIES_SPAM,
        "Sender merges", "Group different sender IDs for the same brand into one.",
        keywords = listOf("merge group", "brand"),
    )
    val blockList = actionSetting(
        "categoriesSpam.blockList", SettingsGroup.CATEGORIES_SPAM,
        "Blocked numbers", "The system block list, shared with the Phone app.",
        keywords = listOf("block", "spam"),
    )
    val autoArchivePromosDays = intSetting(
        "categoriesSpam.autoArchivePromosDays", SettingsGroup.CATEGORIES_SPAM,
        "Auto-archive promotions", "Move promotional messages to Archive after this many days (0 = never).",
        default = 30, range = 0..90, step = 1,
        keywords = listOf("promo", "promotions", "archive"),
    )
    val fakeCreditWarnings = boolSetting(
        "categoriesSpam.fakeCreditWarnings", SettingsGroup.CATEGORIES_SPAM,
        "Warn about fake credit alerts",
        "Flag \"money credited\" messages that don't come from your bank, and \"sent by mistake, please return\" requests. " +
            "Turning this off hides the warnings; likely fakes still never count toward your balances.",
        default = true,
        keywords = listOf("scam", "fraud", "fake", "credit", "sent by mistake", "return money", "upi"),
    )
    val classifierConfidenceThreshold = intSetting(
        "categoriesSpam.classifierConfidenceThreshold", SettingsGroup.CATEGORIES_SPAM,
        "Classifier confidence threshold",
        "How sure the on-device classifier must be before it files a message automatically.",
        default = 70, range = 0..100, step = 5,
        keywords = listOf("classifier", "confidence", "accuracy"),
        advanced = true,
    )
    val jevOptIn = boolSetting(
        "categoriesSpam.jevOptIn", SettingsGroup.CATEGORIES_SPAM,
        "Jev cloud classification",
        "Send an unclear message's sender and a masked copy to the cloud for a second opinion. Asks for your consent first. Uses a small amount of data.",
        default = false,
        keywords = listOf("jev", "cloud", "edge case"),
        advanced = true,
    )
    val jevMonthlyCap = intSetting(
        "categoriesSpam.jevMonthlyCap", SettingsGroup.CATEGORIES_SPAM,
        "Jev monthly cap", "Maximum messages sent to cloud classification per month.",
        default = 100, range = 0..2000, step = 50,
        keywords = listOf("jev", "cloud", "cap", "limit"),
        advanced = true,
    )

    // ---------------------------------------------------------------- Finance
    val accounts = actionSetting(
        "finance.accounts", SettingsGroup.FINANCE,
        "Accounts and cards", "Manage the bank accounts and cards the ledger tracks.",
    )
    val homeCurrency = textSetting(
        "finance.homeCurrency", SettingsGroup.FINANCE,
        "Home currency",
        "Default currency (ISO code, e.g. INR, USD) for accounts whose SMS don't state one. Blank: from your SIM's country.",
        default = "",
        keywords = listOf("currency", "inr", "usd", "region"),
    )
    val hideBalancesOnLock = boolSetting(
        "finance.hideBalancesOnLock", SettingsGroup.FINANCE,
        "Hide balances on lock screen", "Mask account balances until the phone is unlocked.",
        default = false,
        keywords = listOf("balance", "privacy", "lock screen"),
    )
    val ratesSource = choiceSetting(
        "finance.ratesSource", SettingsGroup.FINANCE,
        "Exchange rates source", "Where daily mid-market rates for foreign transactions come from.",
        default = "ecb",
        options = listOf(ChoiceOption("ecb", "ECB"), ChoiceOption("openSource", "Open source rates")),
        keywords = listOf("exchange rate", "forex", "currency"),
        advanced = true,
    )
    val reconciliationToleranceMinor = intSetting(
        "finance.reconciliationToleranceMinor", SettingsGroup.FINANCE,
        "Reconciliation tolerance",
        "How far a settlement amount may differ from the estimate and still match, in paise.",
        default = 500, range = 0..5000, step = 50,
        keywords = listOf("reconciliation", "tolerance", "settlement", "markup"),
        advanced = true,
    )

    // ---------------------------------------------------------------- SIMs and sending
    val sim1Name = textSetting(
        "simsSending.sim1.name", SettingsGroup.SIMS_SENDING,
        "SIM 1 name", "Label shown for SIM 1 across the app.", default = "SIM 1",
    )
    val sim1Color = textSetting(
        "simsSending.sim1.color", SettingsGroup.SIMS_SENDING,
        "SIM 1 colour", "Accent colour used for SIM 1's bubbles and chips.", default = "#3B82F6",
    )
    val sim2Name = textSetting(
        "simsSending.sim2.name", SettingsGroup.SIMS_SENDING,
        "SIM 2 name", "Label shown for SIM 2 across the app.", default = "SIM 2",
        visible = { it.simCount > 1 },
    )
    val sim2Color = textSetting(
        "simsSending.sim2.color", SettingsGroup.SIMS_SENDING,
        "SIM 2 colour", "Accent colour used for SIM 2's bubbles and chips.", default = "#F97316",
        visible = { it.simCount > 1 },
    )
    val defaultReplySim = choiceSetting(
        "simsSending.defaultReplySim", SettingsGroup.SIMS_SENDING,
        "Default reply SIM", "Which SIM sends a reply when a thread has no SIM of its own yet.",
        default = "ask",
        options = listOf(ChoiceOption("sim1", "SIM 1"), ChoiceOption("sim2", "SIM 2"), ChoiceOption("ask", "Ask each time")),
        visible = { it.simCount > 1 },
    )
    val numberNormalization = boolSetting(
        "simsSending.numberNormalization", SettingsGroup.SIMS_SENDING,
        "Normalise numbers when sending",
        "Send to the full +country-code number so replies work while roaming.",
        default = true,
        keywords = listOf("e164", "roaming", "country code"),
    )
    val roamingWarnings = boolSetting(
        "simsSending.roamingWarnings", SettingsGroup.SIMS_SENDING,
        "Roaming warnings", "Warn before a bulk or scheduled send while roaming, since SMS is billed differently.",
        default = true,
        keywords = listOf("roaming", "billing", "warning"),
    )
    val costWarnings = boolSetting(
        "simsSending.costWarnings", SettingsGroup.SIMS_SENDING,
        "Warn before costly SMS",
        "Ask before texting premium-rate or unknown short codes, international numbers, or while roaming abroad. " +
            "Automations never send to premium-rate numbers you haven't approved.",
        default = true,
        keywords = listOf("premium", "short code", "cost", "charges", "international", "roaming", "warning"),
    )
    val enterToSend = boolSetting(
        "simsSending.enterToSend", SettingsGroup.SIMS_SENDING,
        "Enter key sends", "The keyboard's Enter key sends the message instead of starting a new line.",
        default = false,
        keywords = listOf("keyboard", "enter", "return", "ime", "send button", "new line"),
    )
    val deliveryReports = boolSetting(
        "simsSending.deliveryReports", SettingsGroup.SIMS_SENDING,
        "Delivery reports",
        "Ask the carrier to confirm each message was delivered (MMS only where the carrier supports it). " +
            "Uses a small amount of battery.",
        default = false, advanced = true,
    )
    val mmsReadReceipts = boolSetting(
        "simsSending.mmsReadReceipts", SettingsGroup.SIMS_SENDING,
        "Read receipts for MMS",
        "When someone asks, tell them you have read their MMS, and ask for the same on MMS you send. Never sent to " +
            "businesses or short codes. Only works where the carrier supports MMS read reports.",
        default = false, advanced = true,
        keywords = listOf("read receipt", "read report", "seen", "mms", "privacy"),
    )
    val mmsDeliveryToSenders = boolSetting(
        "simsSending.mmsDeliveryToSenders", SettingsGroup.SIMS_SENDING,
        "Let senders see MMS delivery",
        "Allow the carrier to tell someone that their MMS reached you. Turn off to keep that private.",
        default = true, advanced = true,
        keywords = listOf("delivery report", "report allowed", "mms", "privacy"),
    )
    val sendRateSpreading = boolSetting(
        "simsSending.sendRateSpreading", SettingsGroup.SIMS_SENDING,
        "Send-rate spreading", "Space out a large batch of sends to avoid carrier throttling.",
        default = true, advanced = true,
    )
    val exactAlarmPermission = actionSetting(
        "simsSending.exactAlarmPermission", SettingsGroup.SIMS_SENDING,
        "Exact alarm permission", "Needed for scheduled sends to fire at the exact time you set.",
        advanced = true, visible = { it.apiLevel >= 31 },
    )
    val broadcastLists = actionSetting(
        "simsSending.broadcastLists", SettingsGroup.SIMS_SENDING,
        "Broadcast lists",
        "Send one message to up to 50 people you know, as separate SMS; replies come back 1:1. Personal use only: " +
            "spam breaks Dak's terms and telecom rules.",
        keywords = listOf("broadcast", "bulk", "send to many", "mass message", "group message", "list", "trai"),
    )

    // ---------------------------------------------------------------- Backup and data
    val backupDestination = actionSetting(
        "backupData.destination", SettingsGroup.BACKUP_DATA,
        "Backup destination", "Where encrypted backups are stored (your own Drive or Dropbox).",
    )
    val backupSchedule = choiceSetting(
        "backupData.schedule", SettingsGroup.BACKUP_DATA,
        "Backup schedule", "How often an encrypted backup runs automatically.",
        default = "daily",
        options = listOf(ChoiceOption("daily", "Daily"), ChoiceOption("weekly", "Weekly"), ChoiceOption("manual", "Manual only")),
    )
    val encryptionKeyRecovery = actionSetting(
        "backupData.encryptionKeyRecovery", SettingsGroup.BACKUP_DATA,
        "Encryption key and recovery code", "View or regenerate the key and code that unlock your backups.",
        keywords = listOf("recovery code", "encryption key"),
    )
    val exportData = actionSetting(
        "backupData.export", SettingsGroup.BACKUP_DATA,
        "Export", "Save messages and settings to an open file format.",
    )
    val importData = actionSetting(
        "backupData.import", SettingsGroup.BACKUP_DATA,
        "Import", "Restore messages and settings from a backup file.",
    )
    val otpBinRetention = choiceSetting(
        "backupData.otpBinRetention", SettingsGroup.BACKUP_DATA,
        "OTP bin retention", "How long a deleted OTP stays in the recycle bin before it is purged for good.",
        default = "1day",
        options = listOf(
            ChoiceOption("shorter", "Shorter than a day"),
            ChoiceOption("1day", "1 day"),
            ChoiceOption("longer", "Longer than a day"),
            ChoiceOption("untilEmptied", "Until I empty it"),
        ),
        keywords = listOf("recycle bin", "otp", "retention", "one time password"),
        tier = SettingTier.Premium(Feature.ADJUSTABLE_OTP_BIN_RETENTION),
    )
    val otherBinRetentionDays = intSetting(
        "backupData.otherBinRetentionDays", SettingsGroup.BACKUP_DATA,
        "Recycle bin retention", "How long other deleted messages stay in the recycle bin.",
        default = 30, range = 30..30, step = 1,
        keywords = listOf("recycle bin", "retention"),
    )
    val binBiometricLock = boolSetting(
        "backupData.binBiometricLock", SettingsGroup.BACKUP_DATA,
        "Lock the recycle bin", "Require a fingerprint or face check to open the recycle bin.",
        default = false,
        keywords = listOf("biometric", "recycle bin", "privacy"),
        visible = { it.hasBiometric },
    )
    val binExcludedFromBackup = boolSetting(
        "backupData.binExcludedFromBackup", SettingsGroup.BACKUP_DATA,
        "Exclude bin from backup", "Never include the recycle bin's contents (often recent OTPs) in backups.",
        default = true,
        keywords = listOf("recycle bin", "privacy", "backup"),
    )

    // Privacy centre (Settings → Privacy). Also reachable in one tap from the Settings root; these rows make it
    // searchable ("privacy policy", "delete my data", "consent", "gdpr"...). All open the same screen.
    val privacyCenter = actionSetting(
        "privacy.center", SettingsGroup.BACKUP_DATA,
        "Privacy", "Privacy policy, what can leave your phone and your choices, export or delete your Dak data.",
        keywords = listOf("privacy", "data", "consent", "permissions", "gdpr", "dpdp", "data protection", "rights"),
    )
    val privacyPolicy = actionSetting(
        "privacy.policy", SettingsGroup.BACKUP_DATA,
        "Privacy policy", "What Dak does with your data, in plain language. Works offline.",
        keywords = listOf("privacy policy", "policy", "terms", "data use"),
    )
    val dataSharingChoices = actionSetting(
        "privacy.dataSharing", SettingsGroup.BACKUP_DATA,
        "Data that leaves your phone", "Everything that could send data off the phone is off until you allow it. See or withdraw your choices.",
        keywords = listOf("consent", "withdraw", "cloud", "jev", "webhook", "relay", "sharing", "opt out"),
    )
    val exportMyData = actionSetting(
        "privacy.exportMyData", SettingsGroup.BACKUP_DATA,
        "Export my Dak data", "Save your settings, rules, run history, accounts and ledger to a file you choose.",
        keywords = listOf("export my data", "download my data", "data portability", "access request", "gdpr", "dpdp"),
    )
    val deleteMyData = actionSetting(
        "privacy.deleteMyData", SettingsGroup.BACKUP_DATA,
        "Delete my Dak data", "Erase everything Dak stores on this phone. Your SMS stay in the phone's message store.",
        keywords = listOf("delete my data", "delete", "erase", "forget me", "right to erasure", "wipe", "reset app"),
    )

    // Privacy and security (app lock). Rows live in this group to keep seven groups; the lock method row opens the
    // App lock screen (setup and verification happen there, never through a plain value editor).
    val appLock = choiceSetting(
        "privacy.appLock", SettingsGroup.BACKUP_DATA,
        "App lock",
        "Ask for your fingerprint, face or PIN to open Dak: your phone's screen lock or a separate app PIN.",
        default = "off",
        options = listOf(
            ChoiceOption("off", "Off"),
            ChoiceOption("device", "Phone screen lock"),
            ChoiceOption("appPin", "App PIN"),
        ),
        keywords = listOf("app lock", "lock", "pin", "fingerprint", "face", "biometric", "password", "security", "privacy", "passcode"),
    )
    val autoLockAfter = choiceSetting(
        "privacy.autoLockAfter", SettingsGroup.BACKUP_DATA,
        "Auto-lock", "How long Dak can stay in the background before it asks again.",
        default = "1m",
        options = listOf(
            ChoiceOption("immediately", "Immediately"),
            ChoiceOption("30s", "After 30 seconds"),
            ChoiceOption("1m", "After 1 minute"),
            ChoiceOption("5m", "After 5 minutes"),
            ChoiceOption("15m", "After 15 minutes"),
        ),
        keywords = listOf("app lock", "timeout", "auto lock", "security"),
    )
    val lockOnScreenOff = boolSetting(
        "privacy.lockOnScreenOff", SettingsGroup.BACKUP_DATA,
        "Lock when the screen turns off", "Lock Dak as soon as the screen goes off, whatever the auto-lock time.",
        default = true,
        keywords = listOf("app lock", "screen off", "security"),
    )
    val hideInRecents = choiceSetting(
        "privacy.hideInRecents", SettingsGroup.BACKUP_DATA,
        "Hide content in Recents",
        "Blank Dak's preview in the recent-apps list. This also blocks screenshots of Dak.",
        default = "whenLockOn",
        options = listOf(
            ChoiceOption("whenLockOn", "When app lock is on"),
            ChoiceOption("always", "Always"),
            ChoiceOption("never", "Never"),
        ),
        keywords = listOf("recents", "screenshot", "privacy", "flag secure", "app switcher"),
    )
    val protectSensitiveScreens = boolSetting(
        "privacy.protectSensitiveScreens", SettingsGroup.BACKUP_DATA,
        "Protect sensitive screens",
        "Ask to unlock before opening the recycle bin, passbook, backup, automations and forwarding, even with app lock off.",
        default = false,
        keywords = listOf("app lock", "passbook", "recycle bin", "backup", "automations", "forwarding", "security", "privacy"),
    )
    val indexSchedule = choiceSetting(
        "backupData.indexSchedule", SettingsGroup.BACKUP_DATA,
        "Index schedule", "When the encrypted search index rebuilds itself.",
        default = "tonight",
        options = listOf(ChoiceOption("now", "Now"), ChoiceOption("plugged", "When plugged in"), ChoiceOption("tonight", "Tonight")),
        advanced = true,
    )
    val rebuildIndex = actionSetting(
        "backupData.rebuildIndex", SettingsGroup.BACKUP_DATA,
        "Rebuild index now", "Rebuild the search index from the Telephony provider. May take a few minutes.",
        advanced = true,
    )

    // ---------------------------------------------------------------- Automations
    val rulesList = actionSetting(
        "automations.rulesList", SettingsGroup.AUTOMATIONS,
        "Rules", "Automation rules that file, archive or forward messages for you.",
    )
    val scheduledSends = actionSetting(
        "automations.scheduledSends", SettingsGroup.AUTOMATIONS,
        "Scheduled sends", "Messages queued to send at a later time.",
    )
    val scheduledHeadsUp = choiceSetting(
        "automations.scheduledHeadsUp", SettingsGroup.AUTOMATIONS,
        "Heads-up before scheduled messages",
        "A notification shortly before a scheduled message or automatic birthday wish goes out, with Send now, Delay " +
            "and Cancel. Automatic birthday wishes sent later in the day also get a morning heads-up.",
        default = ScheduledHeadsUpLead.DEFAULT,
        options = ScheduledHeadsUpLead.options,
        keywords = listOf("scheduled", "reminder", "heads up", "before sending", "birthday", "send later", "notification"),
    )
    val forwarding = actionSetting(
        "automations.forwarding", SettingsGroup.AUTOMATIONS,
        "Auto-forwarding", "Forward chosen senders to someone for a period, e.g. bank alerts to your CA. Free: sent from your own SIM.",
        keywords = listOf("forward", "auto forward", "chartered accountant", "ca", "relay"),
    )
    val birthdayWishes = actionSetting(
        "automations.birthdayWishes", SettingsGroup.AUTOMATIONS,
        "Birthday wishes", "Birthdays from your contacts, with a wish sent or suggested on the day.",
        keywords = listOf("birthday", "anniversary", "wish", "greeting", "contacts"),
    )
    val webhooks = actionSetting(
        "automations.webhooks", SettingsGroup.AUTOMATIONS,
        "Webhooks", "Send a signed HTTP request when a rule fires.",
        keywords = listOf("webhook", "api"),
        tier = SettingTier.Premium(Feature.WEBHOOKS), advanced = true,
    )
    val sendApiKeys = actionSetting(
        "automations.sendApiKeys", SettingsGroup.AUTOMATIONS,
        "Send API keys", "Keys that let your own services ask this phone to send an SMS.",
        keywords = listOf("api key", "send api"),
        tier = SettingTier.Premium(Feature.SEND_API), advanced = true,
    )
    val auditLog = actionSetting(
        "automations.auditLog", SettingsGroup.AUTOMATIONS,
        "Automation audit log", "Every automated action, with the rule that caused it.",
        keywords = listOf("audit", "log", "history"),
        tier = SettingTier.Premium(Feature.WEBHOOKS), advanced = true,
    )

    // ---------------------------------------------------------------- Translation
    val translationLanguages = actionSetting(
        "translation.languages", SettingsGroup.TRANSLATION,
        "Languages", "Languages you read, so anything else offers to translate.",
        tier = SettingTier.Premium(Feature.TRANSLATION),
    )
    val autoTranslateRules = actionSetting(
        "translation.autoTranslateRules", SettingsGroup.TRANSLATION,
        "Auto-translate rules", "Automatically translate messages from a chosen sender or language.",
        tier = SettingTier.Premium(Feature.TRANSLATION),
    )
    val downloadedPacks = actionSetting(
        "translation.downloadedPacks", SettingsGroup.TRANSLATION,
        "Downloaded language packs", "On-device ML Kit packs. Larger packs use more storage.",
        keywords = listOf("ml kit", "offline", "language pack"),
        tier = SettingTier.Premium(Feature.TRANSLATION),
    )
    val freeTasterPack = boolSetting(
        "translation.freeTasterPack", SettingsGroup.TRANSLATION,
        "Try one language free", "Download and use one translation language pack without upgrading.",
        default = false,
        keywords = listOf("taster", "free", "trial"),
    )
    val packStorageLocation = actionSetting(
        "translation.packStorageLocation", SettingsGroup.TRANSLATION,
        "Pack storage location", "Where downloaded language packs are stored on device.",
        tier = SettingTier.Premium(Feature.TRANSLATION), advanced = true,
    )

    /** Every setting, in registry (declaration) order. Backs search indexing, group listing and reset. */
    val all: List<SettingDef<*>> = listOf(
        perCategoryAlerts, notificationChannels, otpDisplaySize, otpAutoCopy, otpAutoDelete, consumedOtpHandling, consumedOtpWindowMinutes,
        quickActions, selfTest, soundPerSim, bubbles, lockScreenPrivacy,
        tabSet, swipeRight, swipeLeft, inboxOtpCopy, senderMerges, blockList, autoArchivePromosDays, fakeCreditWarnings, classifierConfidenceThreshold, jevOptIn, jevMonthlyCap,
        accounts, homeCurrency, hideBalancesOnLock, ratesSource, reconciliationToleranceMinor,
        sim1Name, sim1Color, sim2Name, sim2Color, defaultReplySim, numberNormalization, roamingWarnings, costWarnings,
        enterToSend, deliveryReports, mmsReadReceipts, mmsDeliveryToSenders, sendRateSpreading, exactAlarmPermission, broadcastLists,
        backupDestination, backupSchedule, encryptionKeyRecovery, exportData, importData,
        otpBinRetention, otherBinRetentionDays, binBiometricLock, binExcludedFromBackup,
        privacyCenter, privacyPolicy, dataSharingChoices, exportMyData, deleteMyData,
        appLock, autoLockAfter, lockOnScreenOff, hideInRecents, protectSensitiveScreens, indexSchedule, rebuildIndex,
        rulesList, scheduledSends, scheduledHeadsUp, forwarding, birthdayWishes, webhooks, sendApiKeys, auditLog,
        translationLanguages, autoTranslateRules, downloadedPacks, freeTasterPack, packStorageLocation,
    )

    fun byKey(key: String): SettingDef<*>? = all.firstOrNull { it.key == key }

    fun byGroup(group: SettingsGroup): List<SettingDef<*>> = all.filter { it.group == group }
}
