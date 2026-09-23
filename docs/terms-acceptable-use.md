# Acceptable use

Dak is a personal messaging app. Broadcast lists, scheduled sends, forwarding and automations are there to help
you talk to people you know. This page is shown in the app (Broadcast lists → info, and from the "Use broadcasts
with care" sheet) without any network access; the in-app copy is the `bc_aup_body` string and must be kept in step
with this file. When the wording of the broadcast terms changes materially, bump `BroadcastTerms.VERSION` so users
are asked to accept again.

## No spam

You must not use Dak to send spam:

- unsolicited commercial or promotional messages;
- messages to people who have not agreed to hear from you;
- messages to numbers bought, scraped or collected from others.

## Telecom rules

- **India.** The TRAI *Telecom Commercial Communications Customer Preference Regulations, 2018* (TCCCPR) forbid
  unsolicited commercial communication from unregistered senders, including personal ("10-digit") numbers.
  Recipients can report such messages by SMS or call to **1909** or in the DND app. Operators must act on complaints
  and can warn, restrict usage, disconnect and blacklist the number.
- **Everywhere.** Operators throttle, block or suspend numbers that send in bulk, and many countries have anti-spam
  laws. Businesses must use a registered bulk-SMS provider, not a personal SIM.

## Dak's limits

- At most **50 people per broadcast** and **100 broadcast messages in any 24 hours**; these hard caps cannot be
  raised in settings.
- Copies go out in spaced-out batches (10 every 10 minutes, and never more than Android's 30 per 30 minutes).
- Broadcasts are one-shot: send now or at one chosen time. There are no recurring broadcasts, and automation rules
  can never start one.
- Premium-rate numbers, short codes, toll-free/service numbers and alphanumeric sender ids are never broadcast to.
  Blocked numbers, duplicates and your own numbers are skipped.
- Messages that look promotional (links plus offer / sale / discount / "click" wording, in English, Hindi or
  Hinglish) and large sends need an extra "These people know me and expect this message" confirmation.

## Charges

Your operator's SMS charges apply to every message you send, including each copy of a broadcast and each part of a
long message. International recipients and sending while roaming can cost more; Dak warns before such sends.

## Responsibility

You are responsible for the messages you send. Misuse can lead to your number being disconnected by your operator.
