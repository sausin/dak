# iOS (future)

iOS exposes no SMS read/send API. Planned scope (no parity promise, ever):

- `ILMessageFilterExtension` target in Swift that classifies unknown senders using the synced,
  classified sender list produced by the Android app (`shared/formats/sender-identity`).
- A companion app that shows data synced from the Android app (premium, via the ciphertext relay).

Nothing is built here yet.
