# Fake credit alerts and "sent by mistake" scams

Scammers send an SMS that looks like a bank credit alert, then ask the victim to "return" money that never
arrived. This document sets out the threat model, the signals Dak trusts, and what Dak does with a flagged
message. The detector is `app.dak.classify.scam.FakeCreditDetector` in `:classify`. It is a set of on-device rules
with no network, model or cloud call, so it runs in the free tier.

## The scam flow

1. **Bait.** The victim gets a message that looks like a bank alert, for example
   `Your A/c XX1234 credited with Rs 25,000.00 on 12-09-26 by IMPS. Avl Bal Rs 25,340.50 -SBI`. It usually
   comes from a 10-digit mobile number, a long code, or a lookalike sender name.
2. **Hook.** Within minutes or hours the scammer calls or texts, often from a second number: "I sent ₹25,000 to
   your number by mistake, it was for my mother's hospital, please send it back on 98xxxxxxxx / xyz@ybl". The
   message may be in Hindi or Hinglish ("galti se bhej diya, wapas kar do").
3. **Payment.** The victim checks only the SMS, not the bank app, and sends real money by UPI. Sometimes the
   victim approves a collect request instead, which pays the scammer.
4. **Repeat.** A victim who paid is marked as an easy target, and more "mistakes" follow.

### Variants

| Variant | What it looks like |
| --- | --- |
| Fake credit from a mobile number | A bank-style alert from `+91 98xxxxxxxx`, signed `-SBI` or `HDFC Bank`. |
| Header lookalike | A sender like `HDFC-BANK`, `SBIBANK` or `ICICI-ALERT`: alphanumeric, but not DLT-shaped, and not in the bank table. |
| Header in the body | A numeric sender whose text starts with `[VM-HDFCBK]` to borrow a real header's look. |
| UPI collect trick | "You have received ₹4,999 cashback, tap to accept / enter UPI PIN to receive". Approving a collect request, or entering a PIN, pays money out. A PIN is never needed to receive. |
| Fake refund | "Your refund of ₹3,450 is pending, click to receive", often with a shortened link. |
| Fake salary credit | "Your salary of ₹42,500 has been credited", aimed at people waiting for pay. |
| Credit to an unknown account | The alert quotes an account mask the user doesn't have, or a bank the user doesn't bank with. |
| Screenshot over WhatsApp | A photo of a "successful payment" screen. Dak can't see this. The advice in the banner covers it. |
| Fake debit alert | "₹9,999 debited, if not you call 98xxxxxxxx". This pushes the victim to call a fake "helpline" that asks for an OTP or a screen-sharing app. |

## Reliable signals in India

- **DLT headers.** Under TRAI's TCCCPR 2018 rules, commercial SMS must come from a header registered on the
  DLT platform, such as `VM-HDFCBK`, `AX-SBIINB-S` or `JD-ICICIB-T`. The 2-letter prefix names the telemarketer or
  operator. The optional suffix gives the route: `-S` service, `-T` transactional, `-P` promotional, `-G`
  government. Headers are bound to the entity that registered them.
- **Banks never send transaction alerts from 10-digit personal numbers.** Some banks use long codes for inbound
  services, such as missed-call balance or OTP replies. Those messages don't have the shape of a credit alert.
- **Bank alerts never come on the promotional route.** A credit alert with a `-P` suffix is advertising, not an
  alert.
- **The masked account number should match an account the user has.** Dak's ledger knows the user's genuine
  accounts, with institution and visible digits, from past alerts sent by verified headers.
- **Banks never ask you to call a mobile number, pay money back, or enter a PIN to receive.** Real alerts include
  toll-free numbers (`1800…`, `1860…`) and, for UPI credits, the payer's VPA. Neither is a signal on its own.
- **Real credit alerts carry an available balance consistent with history.** *Not used yet*; see Limitations.

## Scoring

Each signal found adds its weight to a score. A score of 60 or more is **LIKELY_SCAM**. A score from 30 to 59 is
**SUSPICIOUS**. Below 30 the result is **NONE**. If the sender is a saved contact, the total is halved: contacts
can be compromised, so the score is reduced, not set to zero. Weights live in
`ScamReason` (`classify/.../scam/ScamModels.kt`).

| Signal | Weight | Region |
| --- | --- | --- |
| Credit alert from a phone number | 45 | IN |
| Debit alert from a phone number | 30 | IN |
| Phone-number sender names a bank or wallet | +25 | IN |
| Non-DLT alphanumeric sender that looks like a bank header | 60 | IN |
| Unknown DLT header or short code claiming a bank | 30 | IN |
| Real bank header without the DLT prefix | 20 | IN |
| Credit alert on the promotional route (`-P`) | 30 | IN |
| Money alert from a sender that is neither a known bank nor a contact | 20 | outside IN |
| Known non-bank brand claiming a bank's credit (non-DLT sender) | 45 | all |
| Account mask matches none of the user's accounts at that bank | 25 | all |
| User has known accounts, but none at the claimed bank | 15 | all |
| "By mistake / please return / galti se / wapas kar do / वापस" with money context | 40 | all |
| Mobile number in the alert | 30 | IN; elsewhere only next to a return request |
| UPI ID or payment link next to a return request | 15 | all |
| Link in a money message from an unverified sender | 15 | all |
| "Enter UPI PIN to receive" | 60 | all |
| Collect or approve phrasing presented as incoming money | 40 | all |
| Follow-up within 48 h of an unverified credit (same amount, or already flagged) | 45 | all |
| Return request within 48 h of a genuine credit of the same amount | 30, capped at SUSPICIOUS | all |

**Verified senders are never flagged.** In India, a verified sender is a DLT header that the template bundle
lists for a bank or wallet and that is not on the promotional route. Outside India, any sender name in the bank
table counts. Genuine alerts routinely name other banks ("from HDFC Bank a/c"), show VPAs, mention refunds, and
announce reversals of amounts "wrongly credited". Scoring those would cause false positives, and a registered
header can't be sent domestically by anyone else.

**Region.** India's DLT rules apply only when the receiving SIM's region is `IN`, which is the default until the
region profile is wired in. Elsewhere, banks legitimately use long codes, short codes and bare alphanumeric names.
There only the generic signals apply: an unknown sender plus a credit plus return urgency, payment handles,
PIN or collect bait, a known non-bank brand claiming a bank, and follow-ups.

## What Dak does

- **Warn, never hide.** A flagged message stays in place, fully readable. It is never moved to spam or deleted.
- **Notification.** The notifier runs before the index, so it calls the detector directly with the sender table
  and a saved-contact lookup. Known accounts and history aren't available at that point. A LIKELY_SCAM message
  gets a warning notification instead of a transaction or conversation one:
  - title: "⚠ Possible fake credit alert · <sender>"
  - text: "Check your bank app before sending any money back"
  - actions: **Report**, which opens the fraud-help screen for the message, and **Block**
  - it goes on the "Other" channel, never the Transactions channel
  - messages classified as spam stay silent spam
- **Index labels.** The enricher stores the verdict in the existing `labels` column, so no schema change is
  needed:
  - `scam:likely-fake-credit` or `scam:suspicious`
  - `scam-reason:<code>` for each reason
  - `scam-claims:<institution>`
  - The helpers are in `ScamLabels`.
- **Ledger.** Rows labelled `scam:likely-fake-credit` never create ledger entries or move balances:
  `LedgerRepository` filters them out when it builds entries. The parsed amount is still kept for display.
  SUSPICIOUS messages still reach the ledger, so there are fewer false negatives in the user's money view. A
  follow-up can't add money anyway.
- **Conversation.** A banner above the message shows:
  - the level
  - the bank the message claims to be from
  - up to three reasons in plain language
  - the advice "Verify in your bank app before you send any money back"
  - an explanation that the sender's bank reverses a genuine mistake
  - buttons: Report fraud, Block sender, Not a scam
- **Not a scam.** This stores the message key outside the index (`ScamOverrides`), so the decision survives a
  re-index or rebuild. It then re-enriches the message. The warning labels are replaced by
  `scam:user-dismissed`, the message is never flagged again, and a genuine credit re-enters the ledger.
- **Inbox.** A conversation with a flagged message from the last 30 days shows a red "Possible scam" chip.
- **Setting.** Settings → Categories and spam → "Warn about fake credit alerts" is on by default. Turning it off
  hides the notification style, banner and chip. Labels are still stored, and likely fakes still never count
  toward balances.

## Limitations and residual risk

- **Spoofed headers.** International SMS gateways and SIM-box routes can sometimes deliver a message with a real
  header name. Indian operators are supposed to block or re-tag unregistered international traffic, but
  enforcement varies. A spoofed `VM-HDFCBK-S` alert with no return request or other signal will pass. The
  unprefixed-header rule (`HDFCBK` without a prefix) catches only the crudest cases, at low weight.
- **New or small banks.** Banks missing from the template bundle's sender table are still recognised as a claim.
  `BankNames` covers about 24 families, including Union Bank, Bank of India, Federal Bank and others not in the
  bundle. Their real DLT headers aren't verified, though. An alert from an unknown DLT header that names a
  different bank scores 30 (SUSPICIOUS). If the header contains the bank's own token, it isn't scored.
- **Genuine long-code senders.** A real credit alert from a long code that names a bank would be flagged
  LIKELY_SCAM. By TRAI rules this shouldn't happen. OTP and balance replies from long codes have no credit
  wording, so they aren't flagged.
- **Balance consistency** is not checked yet. A future signal: the stated "Avl Bal" doesn't follow from the last
  known balance of that account.
- **Order of arrival.** Follow-up detection looks back 48 h from the follow-up. The first backfill runs newest to
  oldest, so a follow-up may be indexed before its bait. Live messages arrive in order. At notification time,
  follow-ups are judged on their own wording: a return request alone is SUSPICIOUS, which gets the normal
  notification.
- **Voice calls and WhatsApp** are out of scope. The banner's advice is written to cover them: verify in the bank
  app, and send nothing back.
- **Language coverage.** English, Hinglish and Hindi (Devanagari) phrasing. Other Indian languages are covered
  only by the sender and structure signals.
- **Automations** receive the raw incoming message and are not yet told about the verdict. A rule like "forward
  bank credits" could forward a fake one.
