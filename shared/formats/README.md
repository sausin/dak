# Shared formats

Platform-neutral formats that outlive any single client. The Android implementation is the
reference; future iOS/web clients read and write the same shapes.

| File | What | Reference implementation |
| --- | --- | --- |
| `dak-export-v1.md`, `dak-export-v1.schema.json` | Open export format (ZIP: manifest + JSONL message chunks + attachments), optionally wrapped in the `DAKENC1` end-to-end encryption envelope | `android/backup` |
| Template bundle | Signed JSON of DLT sender headers → brand/category + regex rules, updated over the air | `android/classify` (`default-templates.json`) |
| Automation rules | Versioned JSON AST of rules (trigger + conditions + actions) | `android/automations` (`RuleCodec`) |
| `helplines-v1.json`, `helplines-v1.schema.json` | Official fraud/spam/emergency helplines for the Report fraud screen, signed-verifiable for OTA refresh | `android/app` (`assets/helplines-v1.json`, `app.dak.safety.helplines`) |

The copies here are kept in sync with the module resources; the module copy is authoritative.

## Helplines bundle (`helplines-v1.json`)

Signed envelope `{ "payload": {...}, "signature": null }`, same scheme as the template bundle: the copy bundled in
the app is trusted as is; an OTA copy must carry a base64 Ed25519 signature over the canonical JSON of `payload`
and a higher `revision`, or it is ignored (fail closed).

Each helpline: `id`, `name`, `country` (ISO, `IN`), `category` (`cybercrime`, `fraud_communication`, `spam`,
`emergency`, `banking`, `bank_card_block`), `action` (`call` → dialer only, never an automatic call; `sms` →
composer; `url` → browser), `target`, `purpose`, `sourceUrl` (the official page that publishes it),
`lastVerified` (date checked against `sourceUrl`, or null), `needsVerification`, optional `verificationNote` and
`smsFormat` (`{text}`, `{sender}`, `{date:dd/MM/yy}` placeholders).

Rules for editing:
- Only official numbers from the publishing authority's own site. Never add a number from a search result,
  forum or SMS.
- `bankCardBlock` stays empty until each number is checked on the bank's own website; users add their own
  bank's card-block number in the app instead (stored on device, labelled unverified).
- Entries with `needsVerification: true` must be checked against `sourceUrl` before a release.
- Bump `revision` on every change and keep `android/app/src/main/assets/helplines-v1.json` identical (a unit test
  compares them).
