# Device test plan (first phone pass)

Install the debug APK from the latest green CI run (`dak-debug-apks-<run>` artifact →
`app-free-debug.apk`). Debug builds install alongside other SMS apps (`app.dak.debug`).
Report back with: phone model + Android version, SIM setup, and for each failing step what you saw.
If the app crashes, `adb logcat -b crash` output (or a screenshot of the crash dialog) is gold.

## P0 — must work (this is what killed SMS Organizer)
1. **Onboarding**: RCS/SMS-limits screen → "Set as default" shows the system role dialog → accept →
   notification/contacts/phone permission prompts → indexing choice. App opens to the inbox within seconds.
2. **Receive SMS**: have someone send you an SMS. A notification arrives within seconds, with the app closed
   and the phone locked. Open it → lands in the right conversation.
3. **OTP**: trigger a real OTP (bank / Google / an app login). Notification shows the code large, **Copy code**
   works, autofill in Chrome/the app still works, the OTP appears under the OTP tab.
4. **Send SMS**: reply to a conversation → single tick → double tick when delivered (if your carrier sends
   delivery reports). Try both SIMs if dual-SIM; the SIM chip is right.
5. **Self-test**: Settings → Notifications → Self-test: all checks green; "send yourself a test SMS" round-trips.
   Repeat with battery optimisation ON for Dak.
6. **Existing history**: old threads show up; after indexing, bank alerts sit under Transactions and promos under
   Promotions. Nothing is missing compared with your previous SMS app.

## P1 — core features
7. MMS: receive a photo; send a photo (composer switches to MMS automatically).
8. Search: `from:hdfc`, `amount:>1000`, `has:otp`, an amount typed as `5,00,000` vs `500000`.
9. Folding: bank senders (VM-/JD-/AX-HDFCBK) show as one conversation; unfold one from the menu.
10. Passbook: accounts grouped (bank / credit card / debit card / wallet…); balances look right or say
    "unknown since"; any "is XX40065 the same as XX440065?" card behaves.
11. Recycle bin: delete a message → Undo; delete again → it's in the bin → Restore.
12. Scam flag: a credit-alert-looking SMS from a normal 10-digit number (ask a friend to send one) shows the
    red warning and never touches the passbook.
13. App lock (if enabled): lock → background the app → unlock with fingerprint / device PIN.
14. Tap numbers inside messages: phone numbers offer call/save; OTPs/amounts/references just copy.
15. Swipes in the inbox (archive right, delete left) with Undo; long-press multi-select. In a thread: long-press
    selects, taps toggle, rotate keeps the selection, back exits; copy joins texts oldest first; delete confirms
    then offers Undo. An OTP older than a day shows no copy chip.

## P2 — nice to check
16. Dark mode / AMOLED switch live; Hindi/other-language messages render correctly.
17. Backup: pick a folder (Drive works via the picker), set a passphrase, back up, note the recovery code.
18. Auto-forwarding rule for one sender with an end date; birthday wishes list reads your contacts.
19. Battery: after a normal day, Settings → Battery → Dak should be negligible.

## Known limitations going in
- SMS Organizer import is heuristic until tested with a real backup file.
- Premium features show as locked; that's expected.
