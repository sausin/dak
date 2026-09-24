# R8 rules for Dak release builds (debug builds are not minified). See docs/release.md for the audit behind them.
#
# Most keeps come from the libraries' own consumer rules and need nothing here:
#  - kotlinx.serialization (META-INF/com.android.tools/r8/*.pro in serialization-core): Companion fields,
#    serializer() and $$serializer of @Serializable classes. Serial names are compile-time strings (@SerialName, or
#    the original FQN), so renaming classes never changes JSON. Dak mostly calls X.serializer() directly.
#  - Hilt / Dagger, Room (RoomDatabase subclasses and their _Impl), WorkManager (worker constructors), DataStore,
#    Coil/OkHttp, coroutines, Compose, AndroidX Startup.
#  - SQLCipher (net.zetetic:sqlcipher-android proguard.txt): keeps net.zetetic.database.** for JNI.
#  - Manifest components (activities, receivers, services, providers) and layout/XML class references: AAPT2
#    generates keep rules for them.
#  - Bundled data (classify default-templates.json / model-weights.json, libphonenumber metadata) is loaded with
#    getResourceAsStream on absolute paths; R8 keeps Java resources and does not rename them.

# Automation action kinds are persisted by class simple name: RunHistory.kindOf, ActionRegistry (audit log),
# AutomationRunLog and RuleValidator store ActionSpec::class.simpleName ("ForwardSms", "Webhook", ...) in history
# and audit rows, and the rule list shows it. Obfuscation would store "a"/"b" and break rows across releases.
# getSimpleName() of a nested class reads the InnerClasses attribute, so that attribute must survive too.
-keepnames class app.dak.automations.rule.ActionSpec
-keepnames class app.dak.automations.rule.ActionSpec$*
-keepattributes InnerClasses,EnclosingMethod

# WorkManager stores the worker class name in its own database, and Hilt's worker factory looks @HiltWorker classes
# up by name. Work enqueued by one release must still resolve after an update, so worker names are never
# obfuscated (the libraries keep them today; this makes it explicit and independent of their rule versions).
-keepnames class * extends androidx.work.ListenableWorker

# Enums persisted by name (InstrumentType, Category, ScheduledSendStatus, settings values, ...) round-trip through
# name() / valueOf(). name() returns the original constant name (R8 keeps the string), and valueOf() goes through
# values() reflectively. proguard-android-optimize.txt keeps these for all enums; repeated here for Dak's own enums so
# persisted values do not depend on the default file.
-keepclassmembers enum app.dak.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Readable stack traces in crash reports (retrace with the uploaded mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
