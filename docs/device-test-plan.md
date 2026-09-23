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
18. Auto-forwarding rule for one sender: defaults to 1 hour (date + time pickers, 12/24h per device); the
    recipient can only be picked from contacts; "Until I stop it" or > 1 hour shows the scam warning and asks for
    the fingerprint; deleting the recipient's contact pauses the rule on the next forward (with a notification).
    Birthday wishes list reads your contacts.
18a. Forwarding needs app lock: with app lock off, turning on (or saving) a forwarding rule shows "Set up app lock
    first" and its button opens App lock; nothing is saved as on. Turn app lock on, then enable the rule.
18b. With a forwarding rule on, App lock → switch off: a "Turn off app lock?" dialog lists the rule(s); Cancel keeps
    both; confirming asks for the fingerprint, then the rule reads "Turned off: app lock was switched off" and the
    "Forwarding active" notification goes. Same with an app PIN as the only lock (no phone screen lock) → Remove PIN.
    Removing the phone's screen lock in system settings while Dak uses it: on return, rules are off and a "Security
    alerts" notification says why.
18c. "Was this you?": enable a rule and wait 3 hours (or force it:
    `adb shell cmd jobscheduler run -f app.dak <job id>` from `adb shell dumpsys jobscheduler | grep outbound`, or
    temporarily lower `OutboundAutomations.REMINDER_DELAY_MILLIS` in a debug build). The alert shows the rule, when it
    was turned on, "Still active" and the count sent; "Turn off" works from the lock screen without unlocking; "See
    what was sent" opens its history. It repeats daily while the rule stays on.
18d. Let a 1-hour rule end: it moves to "Ended — tap to use again"; switching it on starts a fresh hour from now
    (a longer rule asks for the fingerprint again). Forward a message, then open the row's History (clock icon): the
    message, recipient and "Sent" chip are listed; tapping opens the original message. Forwarding → ⋮ → Forwarding
    history also shows rules that were deleted.
18e. Unattended sends after the lock is removed: with app lock on, create an auto-reply rule (a delay of a few
    minutes) and get it triggered, so a reply is queued. Switch app lock off before the reply is due. The reply must
    **not** go out. Automations → history shows it as "Not sent: app lock was off". Repeat with a forwarding rule
    while more than 30 forwards are queued (held forwards).
18f. Birthday wishes → "Send automatically": with app lock off, choosing it shows "Set up app lock first". With app
    lock on, set a contact's wish for a few minutes from now, then switch app lock off. At the time, no SMS goes out
    and the Send / Edit / Skip notification appears instead. Tapping Send sends it (from the chosen SIM).
18g. Report spam on a dual-SIM phone: open Report fraud for a spam SMS received on SIM 2. The TRAI 1909 row says
    "Sends from SIM 2…", and the composer opens with SIM 2 selected. Take SIM 2 out and repeat: it says the default
    SIM will be used.
18h. Links from other apps: `adb shell am start -a android.intent.action.SENDTO -d "smsto:+911234567890?body=a%26b%2550"`
    opens the composer with body `a&b%50`. `-d "sms:123,456?body=hi%20there"` gives two recipients. Share a photo to
    Dak with SENDTO + `EXTRA_STREAM` and check that it is attached.
18i. Video over MMS: attach a 10 s phone video (tens of MB) and send it as MMS. It sends within about a minute, the
    receiving phone (stock Messages / iPhone) plays it with sound, and the file is under the carrier limit. A
    2-minute video shows "Too large for MMS on this SIM…" without a crash. Attach a long voice recording (audio file)
    and check that it is shrunk or refused cleanly.
19. Battery: after a normal day, Settings → Battery → Dak should be negligible.

## Standards checks on the emulator (SMS/MMS P1 fixes)
Use `android/scripts/sms-pdu.py` (prints `adb emu sms pdu …` lines, or runs them with `--send`). Dak must be the
default SMS app unless a step says otherwise.
20. **Flash (class 0) SMS**: `./sms-pdu.py --send --flash +919876543210 "Flash test"`. A heads-up "Flash message from
    +919876543210" appears with the full text and Save / Dismiss; nothing new in the inbox. Tap the notification →
    dialog with the text (links not clickable). Dismiss → still nothing stored. Send again, tap **Save** → one row,
    already read, no second notification. Turn Dak's notifications off and send once more → stored as a normal SMS.
21. **Replace short message**: `./sms-pdu.py --send --pid 41 +919876543210 "Balance Rs 900"`, then
    `./sms-pdu.py --send --pid 41 +919876543210 "Balance Rs 750"`. The thread holds **one** message, now "Balance Rs
    750", unread. A `--pid 42` message from the same number, or `--pid 41` from another number, adds a new row.
22. **Silent (type 0) SMS**: `./sms-pdu.py --send --pid 40 +919876543210 "silent"`. No notification, no row
    (normally dropped by the platform before Dak sees it).
23. **Multipart partial failure**: needs a fault (no emulator console command fails one part). On a device: send a
    ~400-character SMS and toggle airplane mode right after tapping send. Expected: either the whole message is
    retried (nothing went out) or it shows "Only k of n parts were sent…" with no automatic resend; tapping retry
    resends the whole text once.
24. **Losing the default role**: make another app (Google Messages) the default. Open a conversation in Dak: the
    composer is read-only with "Dak is not your default SMS app" and **Make Dak your default SMS app**. Schedule a
    text a few minutes out *before* switching; when it comes due it stays in the scheduled list as pending (not
    failed, not "sent"), and its time moves 15 minutes on at each check while Dak is not the default. A new message
    to `112` stays sendable. Tap the button (or switch back in Settings) → the composer unlocks, a pending MMS
    download resumes, and the scheduled text goes out at its next check (within 15 minutes).
25. **Group MMS off**: on a carrier / emulator config with `enableGroupMms=false` (e.g. `adb shell cmd
    phone cc set-value -p enableGroupMms false`, verify the syntax for the Android version), a text to two people
    says "each recipient gets their own copy" and creates two 1:1 SMS rows; a photo to two people sends two MMS.
26. **MMS answers**: with auto-download off, receive an MMS (needs a real carrier or an MMSC test setup; the emulator
    cannot inject WAP push). The MMSC log / `adb logcat -s DakTelephony` shows an m-notifyresp-ind Deferred; tapping
    to download then sends m-acknowledge-ind. With auto-download on, the answer is notifyresp Retrieved.

### More SMS fixtures (`sms-pdu.py`)
The script's tests (`python3 -m pytest android/scripts/tests`) check every flag below by decoding the PDUs. These
steps check that the phone's framework and Dak handle them.

27. **16-bit concatenation reference, parts out of order**: `./sms-pdu.py --send --ref16 --shuffle +919876543210
    "$(printf 'Part test %.0s' {1..40})"` (400 characters, 3 parts sent in a random order). Expected: **one**
    inbox row with the text in the right order and one notification. Repeat with `--order 3,2,1` and without
    `--ref16`.
28. **Missing part**: `./sms-pdu.py --send --ref 77 --drop 2 +919876543210 "<400-character text>"`. Expected: no
    row at first (the framework waits for part 2). Then send only part 2 with the same reference:
    `./sms-pdu.py --send --ref 77 --drop 1 --drop 3 …` with the same text → one complete row. A
    part that never comes is released by the framework after its timeout (days, OEM-specific), not by Dak.
29. **Port-addressed data SMS**: `./sms-pdu.py --send --port 2948 --hex 0106FF +919876543210` (a WAP-style port)
    and `./sms-pdu.py --send --port 16001:9200 +919876543210 "text on a port"`. Expected: no inbox row and no
    notification (data SMS go to `DATA_SMS_RECEIVED` receivers, and Dak registers none); no crash in
    `adb logcat -s DakTelephony`. `./sms-pdu.py --send --hex DEADBEEF +919876543210` (8-bit, no port): whatever
    the framework delivers must not crash Dak (a placeholder is P2 item 21).
30. **National language shift tables**: `./sms-pdu.py --send --nls hi +919876543210 "नमस्ते, आपका OTP 482913
    है।"` and `./sms-pdu.py --send --nls bn +8801712345678 "আমি ভালো আছি, OTP 1234"`. Expected: the text shows
    exactly as sent, and the OTP is detected. If the emulator image has the tables turned off, the framework may
    show garbage instead: note the image and API level, since receive-side decoding is the framework's.
31. **Delivery report PDUs**: `./sms-pdu.py --status-report --mr 42 --st 00 +919876543210` prints an
    SMS-STATUS-REPORT (00 delivered, 20–3F still trying, 40–7F failed). The emulator console cannot inject status
    reports, so use it as a fixture: `SmsMessage.createFromPdu(bytes, "3gpp")` in a Robolectric test of
    `SmsStatusProcessor` / `DeliveryStatus`.

## Known limitations going in
- SMS Organizer import is heuristic until tested with a real backup file.
- Premium features show as locked; that's expected.
