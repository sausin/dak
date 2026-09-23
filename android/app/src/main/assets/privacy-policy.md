<!-- Source of truth. The app shows a copy: android/app/src/main/assets/privacy-policy.md (keep the two identical). -->
# Dak privacy policy

**Version 1, effective (date of first public release).** The latest version is at [the Dak website](https://dak.example/privacy). The app shows this same text offline in Settings → Privacy → Privacy policy.

## The short version

- Dak is a text messaging (SMS and MMS) app. It reads, sorts and stores your messages **on your phone**.
- In the free version, **nothing about your messages is sent to Dak or anyone else**, except the messages you choose to send through your mobile network.
- A few optional features would send some data off your phone (listed below). They are **off until you turn them on**, and each one asks for your permission first with a screen that says exactly what is sent, where, and why. You can turn each one off again at any time.
- Dak has **no ads, no analytics and no crash-reporting services**. We do not sell or share your data.
- You can **export** or **delete** everything Dak stores, from Settings → Privacy.

## Who we are

Dak ("we", "us") is responsible for this app. Contact: privacy@dak.example (placeholder until the company details are final). If you are in India, this is also the contact for our grievance officer; if you are in the EU or UK, for our data protection contact.

## What Dak handles on your phone

As your default SMS app, Dak works with the following. All of it stays on your phone unless a section below says otherwise.

- **Your text and picture messages.** Android keeps them in the phone's shared message store. Dak reads that store to show your conversations, writes new messages into it, and keeps its own encrypted index of them for fast search and sorting.
- **What Dak works out from your messages:** the category of each message (for example OTP, bank or spam), labels, one-time passwords, bank and card accounts and a ledger of transactions (the Passbook), scam warnings, and sender names. This lives in Dak's encrypted index.
- **Things you create in Dak:** settings, automation rules, scheduled messages, saved searches, broadcast lists, sender groupings, and your recycle bin (messages you deleted, kept for a short time so you can restore them).
- **Logs Dak keeps for you:** an automation run history (what each rule sent, to whom, or why it did not) and an activity log of automatic and destructive actions, so you can check what happened.
- **Contacts:** names and photos, to show who a message is from. Dak does not upload or change your contacts.
- **Phone and SIM details:** which SIMs you have and their numbers, to send from the right SIM.
- **Your consent records:** when you allowed or withdrew each optional feature below, and which version of its explanation you saw.

Dak's index and databases are encrypted, with keys held in your phone's secure key storage (Android Keystore). You can also lock Dak with your fingerprint, face or a PIN.

## What leaves your phone

### Things you do yourself

These go where you send them. Dak does not receive a copy.

- **Messages you send** go through your mobile network, like any SMS or MMS. Picture messages are sent and received through your carrier's MMS service using mobile data.
- **Forwarding, sharing and replies** you make, including automation rules you set up to forward messages by SMS to a person you choose, automatic replies, and WhatsApp "one-tap" forwarding (Dak opens WhatsApp with the text filled in; you press send).
- **Spam reports to 1909** (India): Dak prepares the complaint text; you review it and send it as an SMS.
- **Your location**, only when you tap "share location" in a message you are writing. Dak turns it into a map link inside that message. It is not kept or sent anywhere else.
- **Exports and backups** to a place you choose (a folder on your phone, or a storage app such as Google Drive or Dropbox). Backups are encrypted on your phone with your passphrase before they are saved; nobody, including us and your storage provider, can read them without your passphrase or recovery code. Exports ("Export my data", "Export messages") are not encrypted, because their purpose is to be readable by you and other apps; keep them somewhere safe.
- **Links** you tap open in your browser.

### Optional features that send data to a server (off by default)

Each of these asks for your permission first, on a screen that shows what is sent, to whom, why and for how long. Turn them off in Settings → Privacy → Data that leaves your phone; turning one off stops it immediately.

**Current status:** none of these is active in the current version of Dak. They are described here so you know what to expect; when one becomes available you will see its permission screen before anything is sent.

- **Jev cloud classification.** When Dak's on-phone classifier cannot sort a new incoming message, Jev can send the sender ID (a business header such as "VM-HDFCBK", or the phone number of a person) and a masked copy of the message, with numbers, amounts, card numbers, links, email addresses and likely names replaced by placeholders, to Dak's classification service for a second opinion. The service returns a category and does not keep the text. It is limited to a monthly number you set.
- **Webhooks** (premium). An automation rule you create can post the sender, an internal message ID and the message text (or your template of it) to a web address you enter. That address belongs to you or a service you chose; they decide how long they keep it.
- **Web and desktop relay** (premium). Messages you choose to relay are encrypted on your phone with a key shared only with your paired computer, and passed through Dak's relay server, which cannot read them. Encrypted items are deleted from the relay once delivered, and after 7 days at most.
- **AI-assisted search** (premium). Only the question you type (for example "how much did I spend on Swiggy last month") is sent to a language-model service, which turns it into search filters. Your messages are not sent; the search runs on your phone.

If you buy premium, Google Play handles the payment; Dak receives only confirmation of what you are entitled to, not your payment details.

### What Dak never does

- No advertising, analytics, tracking or crash-reporting code is included in the app.
- We do not sell, rent or trade personal data, and we do not use your messages to train AI models.

## Permissions and why Dak asks for them

- **SMS and MMS (send, receive, read):** to be your default messaging app. Android only lets Dak ask for these after you choose it as the default SMS app.
- **Notifications:** to tell you about new messages and OTPs.
- **Contacts:** to show names and photos instead of numbers.
- **Phone state and phone numbers:** to know your SIMs, send from the right one and label messages by SIM.
- **Approximate location:** only when you share your location in a message.
- **Internet and network state:** used only to download and send picture messages (MMS) through your carrier's mobile data connection. The free version contains no code that sends data to a server.
- **Run at startup, exact alarms, foreground service, keep awake, vibration:** to deliver scheduled messages on time, finish sending or downloading a message when the screen is off, and alert you.
- **List of installed apps:** to recognise when an OTP was used by an app on your phone (SMS Retriever check). This check happens on your phone; the list is not sent anywhere.

Dak does not ask Android to exempt it from battery optimisation directly. If messages arrive late on your phone, Dak explains how to change this yourself in your phone's battery settings.

## How long data is kept

- **Messages** stay in your phone's message store until you delete them.
- **Recycle bin:** deleted OTPs are purged after 1 day, other deleted messages after 30 days (you can empty it sooner).
- **Activity log:** 90 days.
- **Automation run history:** one year, and at least the newest 5,000 entries.
- **Search history:** the latest 50 searches.
- **Everything else Dak stores** (index, settings, rules, ledger, consent records) stays on your phone until you delete it, use "Delete my Dak data", clear the app's storage, or uninstall Dak.
- **Backups** stay in the folder you chose until you delete them there.
- **Servers:** the optional features above keep data only for the times stated on their permission screens.

## Your rights and choices

Wherever you live, you can:

- **See and take a copy of your data:** Settings → Privacy → Export my Dak data saves your settings, rules, run history, activity log, accounts and ledger, labels and consent records to a file you choose. To export your messages too, use Settings → Backup, data and privacy → Export.
- **Correct it:** edit or delete rules, labels, accounts and settings in the app.
- **Delete it:** Settings → Privacy → Delete my Dak data erases everything Dak stores on your phone. Your SMS and MMS stay in the phone's message store, because other apps share it; delete them in Dak or your phone's messaging settings if you want them gone.
- **Withdraw consent** for any optional feature, as easily as you gave it: Settings → Privacy → Data that leaves your phone.
- **Complain:** contact us first (details above). In India you may then approach the Data Protection Board of India; in the EU or UK, your data protection authority.

Because Dak keeps data on your phone, we usually hold nothing about you on our side. If you have used an optional server feature and want us to confirm or delete anything held there, contact us.

## Children

Dak is not directed at children under 13. The optional server features above are only for people aged 18 or over, and their permission screens ask you to confirm this.

## Changes to this policy

If we change what Dak does with your data, we will update this policy and its version number, and show the new version in the app. If an optional feature starts sending different data, you will be asked for permission again before it does.
