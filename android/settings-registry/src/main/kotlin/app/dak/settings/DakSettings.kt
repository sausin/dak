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
    serialize = { it.toString() }, deserialize = { it.toIntOrNull() },
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
        "Jev cloud classification", "Send an unclear message's sender and template to the cloud for a second opinion. Uses a small amount of data.",
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
        "Home currency", "Default currency for accounts without their own currency in their SMS.",
        default = "INR",
        keywords = listOf("currency", "inr"),
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
    val deliveryReports = boolSetting(
        "simsSending.deliveryReports", SettingsGroup.SIMS_SENDING,
        "Delivery reports", "Ask the carrier to confirm each SMS was delivered. Uses a small amount of battery.",
        default = false, advanced = true,
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
        perCategoryAlerts, notificationChannels, otpDisplaySize, otpAutoDelete, consumedOtpHandling, consumedOtpWindowMinutes,
        quickActions, selfTest, soundPerSim, bubbles, lockScreenPrivacy,
        tabSet, senderMerges, blockList, autoArchivePromosDays, classifierConfidenceThreshold, jevOptIn, jevMonthlyCap,
        accounts, homeCurrency, hideBalancesOnLock, ratesSource, reconciliationToleranceMinor,
        sim1Name, sim1Color, sim2Name, sim2Color, defaultReplySim, numberNormalization, roamingWarnings, costWarnings,
        deliveryReports, sendRateSpreading, exactAlarmPermission,
        backupDestination, backupSchedule, encryptionKeyRecovery, exportData, importData,
        otpBinRetention, otherBinRetentionDays, binBiometricLock, binExcludedFromBackup, indexSchedule, rebuildIndex,
        rulesList, scheduledSends, webhooks, sendApiKeys, auditLog,
        translationLanguages, autoTranslateRules, downloadedPacks, freeTasterPack, packStorageLocation,
    )

    fun byKey(key: String): SettingDef<*>? = all.firstOrNull { it.key == key }

    fun byGroup(group: SettingsGroup): List<SettingDef<*>> = all.filter { it.group == group }
}
