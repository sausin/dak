# Shared formats

Platform-neutral formats that outlive any single client. The Android implementation is the
reference; future iOS/web clients read and write the same shapes.

| File | What | Reference implementation |
| --- | --- | --- |
| `dak-export-v1.md`, `dak-export-v1.schema.json` | Open export format (ZIP: manifest + JSONL message chunks + attachments), optionally wrapped in the `DAKENC1` end-to-end encryption envelope | `android/backup` |
| Template bundle | Signed JSON of DLT sender headers → brand/category + regex rules, updated over the air | `android/classify` (`default-templates.json`) |
| Automation rules | Versioned JSON AST of rules (trigger + conditions + actions) | `android/automations` (`RuleCodec`) |

The copies here are kept in sync with the module resources; the module copy is authoritative.
