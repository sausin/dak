# Web / desktop client (premium, future)

One TypeScript codebase for the paired web/desktop client. It talks to the relay server
(separate repo), which only ever stores ciphertext addressed by pairing id. Pairing keys are
exchanged on-device via QR. Nothing is built here yet; the Android seam is `PremiumGateway`
in `android/premium-api`.
