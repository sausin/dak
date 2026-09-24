# `:backup`

Pure-Kotlin JVM module (no `android.*` imports) implementing Dak's open export format, its
end-to-end encryption envelope, incremental backup planning/engine, the SMS Backup & Restore XML
exporter/importer, and importers for Fossify Messages and SMS Organizer. Depends only on
`:core-model`, `kotlinx-serialization-json` and `kotlinx-coroutines-core`.

See `FORMAT.md` for the on-disk export format and `src/main/resources/app/dak/backup/dak-export-v1.schema.json`
for its JSON Schema.

## `app.dak.backup.format` — the open export format

- **`MessageRecord`** / **`AttachmentRecord`** / **`ThreadPrefs`** / **`Manifest`** / **`ManifestMeta`**
  — the format's data model (see `FORMAT.md`). `MessageRecord.contentHash(): String` is the stable
  hash `BackupPlanner` diffs on.
- **`DakExportWriter(output: OutputStream, chunkSize: Int = 1000)`** — streaming writer.
  - `writeAttachment(sha256: String, bytes: ByteArray)` / `writeAttachment(sha256: String, input: InputStream)`
  - `writeMessages(messages: Sequence<MessageRecord>)` — consumes the sequence exactly once, lazily.
  - `writeThreads(threads: List<ThreadPrefs>)`, `writeSettings(settingsJson: String)`
  - `writeAutomationRuns(runs: Sequence<AutomationRunRecord>)` — optional `automation_runs.jsonl`
  - `finish(meta: ManifestMeta): Manifest` — writes the trailer `manifest.json`; call last.
  - `close()`
- **`DakExportReader(input: InputStream)`** — streaming reader.
  - `readMessages(attachmentSink: (sha256, InputStream) -> Unit = { _, _ -> }): Sequence<MessageRecord>`
    — lazy; iterate it fully to populate `threads`, `settingsJson`, `automationRuns` and `manifest` (they sit
    after the message chunks in the archive).
- **`AutomationRunRestore.plan(incoming, existingKeys, nowMillis)`** — which backed-up run-history rows to insert:
  dedupes, drops invalid or future-dated rows, bounds strings and count. Rows are log entries only.
- **`PersonalDataExportWriter(output)`** — the "Export my Dak data" ZIP (`writeJson`, `writeJsonLines`, `finish`).
- **`Hashing`** — `sha256Hex(ByteArray|String|InputStream)`, `hexToBytes`, `ByteArray.toHex()`.

## `app.dak.backup.xml` — SMS Backup & Restore (SyncTech) XML

- **`XmlTokenizer(input: InputStream)`** / **`XmlToken`** / **`XmlEntities`** — a small hand-written
  streaming XML reader (StAX/xmlpull are not available here; see `FORMAT.md`/KDoc for why), with
  full entity decoding (`&amp;&lt;&gt;&quot;&apos;`, decimal/hex numeric, surrogate pairs).
- **`XmlWriter(output: OutputStream)`** — the matching escaping writer
  (`startElement`/`endElement`/`selfClosingElement`/`raw`/`flush`).
- **`SmsBackupRestoreXmlExporter.write(output, messages: Sequence<MessageRecord>, count: Int, attachmentBytes: (sha256) -> ByteArray? = { null })`**
- **`SmsBackupRestoreXmlImporter`** (implements `Importer`) —
  `import(input): Sequence<ImportedMessage>` or `importWithAttachments(input, attachmentSink: (ByteArray) -> Unit)`.

## Safety limits (`app.dak.backup.format.ArchiveLimits`)

All import and restore input is treated as attacker-controlled. `DakExportReader` accepts attachment entries
only when the name is 64 lower-case hex characters (so it can never be a path), caps every entry while it
inflates, and caps the total size and the entry count. The importers cap whole-file JSON (128 MiB), JSON depth
(64, via `checkJsonDepth`) and parts/addresses per MMS. `XmlTokenizer` never expands DTD entities and caps
names, attribute counts, attribute values and text runs (`XmlLimits`). `BackupEngine.restore` rejects
parent-id cycles, chains longer than 10k snapshots and snapshot ids with path syntax. Exceeding a limit throws
`ArchiveLimitException` (an `IOException`). `LimitedInputStream` / `readBounded` are public helpers.

## `app.dak.backup.crypto` — end-to-end encryption envelope

- **`RecoveryCode`** — `generate(random: SecureRandom = SecureRandom()): Generated(secret, formatted)`,
  `format(secret: ByteArray): String`, `parse(input: String): Result<ByteArray>` (24-char grouped
  Crockford base32 with a mod-37 check character; tolerant of case/spacing/`I`/`L`/`O` typos).
- **`BackupCrypto`** — passphrase or recovery code, either unlocks a backup; PBKDF2-HMAC-SHA256 KDF
  (`DEFAULT_ITERATIONS = 310_000`, configurable within `MIN_ITERATIONS..MAX_ITERATIONS` = 100k..5M,
  enforced on write and on read so a crafted header cannot demand 2^31 iterations), AES-256-GCM STREAM segments (`SEGMENT_SIZE = 64
  KiB`) with a final-segment flag that turns truncation into a detected authentication failure.
  - `encryptingOutputStream(rawOutput, passphrase: CharArray, iterations = DEFAULT_ITERATIONS, random = SecureRandom()): EncryptResult(recoveryCode, output)`
  - `decryptingInputStream(rawInput, passphrase: CharArray): InputStream` — throws `WrongPassphraseException`
  - `decryptingInputStreamWithRecoveryCode(rawInput, recoveryCode: String): InputStream` — throws `RecoveryCodeMismatchException`
  - Both throw `TamperedException` on a corrupted/truncated stream and `MalformedHeaderException` on
    a non-Dak-encrypted stream. All four extend `BackupCryptoException`.

## `app.dak.backup.engine` — incremental backups

- **`BackupTarget`** (suspend `list`/`openRead`/`openWrite`/`delete`) — implement this for Drive,
  Dropbox or a SAF folder in `:app`. **`LocalDirectoryTarget(dir: File)`** is the file-based
  implementation used by this module's tests.
- **`BackupPlanner.plan(previousDigest: Map<String, String>, current: Sequence<MessageRecord>): BackupPlan`**
  (`added`/`changed`/`deletedKeys`/`unchangedCount`) and `BackupPlanner.digestOf(messages): Map<String, String>`.
- **`BackupEngine(target: BackupTarget, encryption: BackupEncryption? = null)`**
  - `suspend fun backup(messages: Sequence<MessageRecord>, threads = emptyList(), settingsJson = "{}", automationRuns = emptySequence(), appVersion: String, attachmentSource: suspend (sha256) -> InputStream? = { null }, previousManifest: Manifest? = null, previousDigest: Map<String,String> = emptyMap(), knownAttachmentHashes: Set<String> = emptySet(), device: String? = null, now = System.currentTimeMillis(), id = UUID.randomUUID().toString()): BackupResult`
    — omit `previousManifest`/`previousDigest` for a FULL snapshot, pass a prior `BackupResult`'s
    `manifest`/`digest` for an INCREMENTAL one.
  - `fun restore(key: RestoreKey? = null, existingKeys: (kind: MessageKind, address: String, dateMillis: Long, bodyHash: String) -> Boolean, attachmentSink: (sha256, InputStream) -> Unit = { _, _ -> }, fromBlobName: String? = null): Flow<MessageRecord>`
    — walks the incremental chain, merges it, and emits only messages `existingKeys` reports as not
    already present. **Never deletes or blanks anything.**
  - `suspend fun readExtras(key: RestoreKey? = null, fromBlobName: String? = null): SnapshotExtras` — the settings
    JSON and automation run history of the newest (or named) snapshot.
  - `RestoreKey.Passphrase(CharArray)` / `RestoreKey.RecoveryCode(String)`.

## `app.dak.backup.importers` — foreign-backup importers

- **`Importer`** — `id`, `sniff(headerBytes, fileName): Boolean`, `import(input): Sequence<ImportedMessage>`.
- **`ImportedMessage`** / **`ImportedAttachment`** / **`ImportResult`**.
- **`FossifyImporter`** (`Importer`) — Fossify Messages JSON export; tolerant of old/new field shapes.
- **`SmsOrganizerImporter`** — `sniff(...)`, `import(input, fileNameHint = null): ImportResult`. SMS
  Organizer's Drive backup format is undocumented; this is a best-effort, tolerant reader (ZIP or
  bare JSON, case-insensitive keys, epoch seconds or millis). **Verify against a real SMS Organizer
  backup before relying on it** — tracked in the build plan's "Bring to the first build session".
- **`ImportDetector.detect(headerBytes, fileName): Importer?`** — SMS Backup & Restore XML or
  Fossify. **`ImportDetector.detectOrFallback(...): DetectedImporter?`** — also falls back to
  `SmsOrganizerImporter` for anything else that looks like JSON or a ZIP.

## Known limitations / next steps for whoever wires this into `:app`

- `BackupEngine.restore` materializes the merged effective message set (across an incremental
  chain) in memory before emitting. Fine for the message counts this app targets; a future
  optimization for very large inboxes would be an on-disk/streaming merge instead.
- `BackupEngine.backup` also materializes the message list it writes (the delta for an incremental
  backup, or the full set for a full one) to compute the attachment set in one pass; `DakExportWriter`
  itself still streams entry-by-entry I/O.
- `SmsOrganizerImporter`'s shape-guessing is provisional until checked against a real backup file.
- Drive/Dropbox/SAF `BackupTarget` implementations belong in `:app` (need Android/Google APIs).
