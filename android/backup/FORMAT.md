# Dak export format v1

An open, documented backup/export format: a ZIP archive with a JSON manifest, JSON Lines message
chunks, and content-addressed attachment blobs. This is the format `BackupEngine` writes to a
user's own storage (encrypted — see below) and the format the in-app "export" feature offers in
the clear, so "another Microsoft that vanishes" never traps anyone's messages.

JSON Schema for the manifest and each message line lives at
`src/main/resources/app/dak/backup/dak-export-v1.schema.json` (destined to move to a repo-level
`shared/formats` directory once other modules need it).

## Container

A standard ZIP file (`java.util.zip`, DEFLATE), with entries always written in this order so a
single forward streaming pass over the archive can produce or consume it — no random access
(`ZipFile`) required, though it also happens to be usable as an ordinary ZIP by any unzip tool:

```
attachments/<sha256>       0 or more, content-addressed blobs (lower-case hex SHA-256 of the bytes)
messages/0000.jsonl        one JSON Message per line (see below)
messages/0001.jsonl        ... chunked, 1000 lines per file by default
threads.json               array of per-thread preferences
settings.json              opaque JSON string, passed through unparsed by this module
manifest.json              written LAST, once every other part's size and SHA-256 are known
```

Writing the manifest last (rather than first, as a table of contents) is what lets the archive be
produced in one streaming pass: each part's SHA-256 can only be known once its bytes have been
written, and the manifest records every part's hash. `DakExportWriter`/`DakExportReader` are the
reference implementation; see their KDoc for the exact API.

## `manifest.json`

```json
{
  "format": "dak-export",
  "version": 1,
  "createdAt": 1758000000000,
  "appVersion": "1.0.0",
  "device": "Pixel 8 (optional)",
  "counts": { "messages": 42, "threads": 7, "attachments": 3 },
  "parts": [
    { "name": "messages/0000.jsonl", "sha256": "…64 hex chars…", "sizeBytes": 12345 }
  ],
  "id": "b3f5…",
  "parentId": null,
  "kind": "FULL",
  "deletedKeys": []
}
```

`id`/`parentId`/`kind`/`deletedKeys` support incremental backups (see `BackupPlanner` and
`BackupEngine`): an `INCREMENTAL` manifest's `messages/*.jsonl` only contain **added or changed**
messages since `parentId`'s snapshot, and `deletedKeys` lists message keys removed since then. A
plain export (Settings → Backup and data → Export) is always a standalone `FULL` manifest.

## `messages/NNNN.jsonl`

One JSON object per line, each one a `MessageRecord`: the canonical `core-model.Message` fields,
plus optional enrichment that only Dak's own encrypted index knows about (`category`, `labels`,
`starred`, `archived`). A reader that only wants the raw message can ignore the enrichment fields
entirely; they default to absent/false so a provider-only export is valid too.

```json
{
  "key": "sms:1042",
  "kind": "SMS",
  "threadId": 7,
  "address": "+919812345678",
  "body": "Your OTP is 482913",
  "dateMillis": 1758000000000,
  "subId": 1,
  "box": "INBOX",
  "read": true,
  "seen": true,
  "attachments": [],
  "category": "OTP",
  "labels": ["bank"],
  "starred": false,
  "archived": false
}
```

`attachments[].sha256` references a blob at `attachments/<sha256>` in the same archive.

## `threads.json`

An array of per-thread preferences that live outside the Telephony provider:

```json
[{ "threadId": 7, "replySubId": 1, "pinned": true, "muted": false, "archived": false, "bubbleColorArgb": -16711936 }]
```

## `settings.json`

A single opaque JSON string. This module never parses it — it round-trips whatever the app wrote.

## Attachments

`attachments/<sha256>` blobs are addressed by the lower-case hex SHA-256 of their bytes, so
identical attachments across messages (or across incremental snapshots) are stored once.

## Also exported: SMS Backup & Restore (SyncTech) XML

`app.dak.backup.xml.SmsBackupRestoreXmlExporter`/`SmsBackupRestoreXmlImporter` read and write the
widely-used SyncTech XML format (`<smses><sms .../><mms>...</mms></smses>`) as a second, more
broadly interoperable export target. It carries less enrichment (no category/labels/starred —
those are Dak-specific) but is readable by many other tools. See its KDoc for the exact attribute
mapping (SMS `type` and MMS `msg_box` map 1:1 onto `core-model.MessageBox`'s provider ints; `addr
type="137"` is the sender, `"151"`/`"130"` are to/cc recipients).

## Encryption envelope

Neither format above is encrypted by itself. `BackupEngine` wraps whichever bytes it writes to a
`BackupTarget` in the `BackupCrypto` envelope (magic `DAKENC1`) when constructed with a
`BackupEncryption` config — see `crypto/BackupCrypto.kt`'s KDoc for that binary layout. The export
format documented above is always the plaintext underneath.
