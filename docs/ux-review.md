# UX review: reachability, gestures, feedback, accessibility

Scope: every screen under `android/app/src/main/kotlin/app/dak/ui`. The audience is "messages that matter": people
who get a lot of OTPs and bank alerts, many on mid-range 6.5"+ phones used with one hand. Findings are ranked by
impact × effort. The last section lists what this pass built and what it left for later.

## How the screens were judged

- **Reachability.** On a tall phone the bottom third of the screen is easy to reach with the thumb; the top corners
  are the hardest. Primary actions belong at the bottom (FAB, bottom bar, bottom sheets). Top-bar actions are for
  secondary things.
- **Gestures.** One vocabulary across the app: swipe a list row to act on it, long-press to select or open actions,
  double-tap for a quick copy, swipe a bubble to reply. Every gesture also needs a visible or accessible alternative.
- **Feedback.** A haptic tick when a gesture commits. A snackbar with Undo for anything destructive or anything that
  moves an item out of view. A visible "Copied" state after a copy.
- **Predictive back.** `android:enableOnBackInvokedCallback` is on. Use `BackHandler` only while there is local state
  to unwind (a selection, an open tray), and let sheets and dialogs handle back themselves.
- **Accessibility.** Touch targets of at least 48dp. A `contentDescription` on every icon-only button. One merged
  TalkBack node per list row. Headings on titles. Live regions for transient confirmations. Custom actions wherever a
  gesture is the only way to do something. Colours come from theme tokens, never literals.

## Findings by screen (before this pass)

| Screen | Findings |
| --- | --- |
| Inbox | The search entry and a 9-item overflow sat in the top bar: the most-used entry point was in the hardest zone. Swipe actions were hardcoded. Long-press opened a small dropdown, and multi-select only existed to fold conversations together. Swipes had no TalkBack alternative. The unread badge read as a bare number. There was no fast path to an OTP without opening the thread. |
| Conversation | Message actions were a dropdown anchored to the bubble, frequent and destructive actions mixed without order. No reply gesture, no quick copy for codes or amounts, no way back to the newest message after scrolling up. The Enter key could never send. The attachment remove button was 28dp and the bubble-colour swatches 32dp. |
| Composer | The send button (52dp), SIM chip and attach button are already in the thumb zone. With the tray open, back left the screen instead of closing the tray. No sentence capitalisation. |
| Search | The search field and filter/sort/save actions sit at the top. Acceptable, because the keyboard is up while searching and the field is where focus lands. The filter sheet is a bottom sheet (good). |
| Bin | Restore and delete-forever are 48dp icon buttons with labels. Delete-forever and empty-bin confirm first (correct for irreversible actions). "Empty bin" is a top-bar text button, which is fine because it is rare. |
| Blocked | The add FAB is at the bottom (good). Unblock uses a confirm dialog; a snackbar undo would be lighter. |
| Backup / Self-test / Onboarding | Primary buttons are in the content flow, mostly low on the screen. Onboarding's `BackHandler` is gated on the step, which is compatible with predictive back. |
| Automations / Forwarding | Extended FABs for create (good). Editors are bottom sheets (good). Row switches sit inside clickable rows, which gives TalkBack two stops per row. |
| Birthdays | Settings are switches in rows, the template editor is a bottom sheet. Same double-stop issue on switch rows. |
| Fraud help | The call-helpline buttons are large and in the content flow; the remove action confirms. Fine. |
| Sender groups / Notification channels | Destructive actions have undo (sender groups) or a confirm (channel reset). Channel rows put a `Switch` inside a clickable row. |
| Settings | Toggle rows were `clickable(role = Switch)` plus a `Switch` with its own click handler: two TalkBack nodes and no "on/off" state on the row. The "Advanced" expander announced neither a heading nor its expanded state. |
| Global | `DakTopAppBar` titles and `EmptyState` titles were not marked as headings. Haptics were only the default long-press tick from `combinedClickable`. Snackbars are polite live regions already (M3 `SnackbarHost`), so they are the right vehicle for confirmations. |

## Prioritised wins (impact × effort)

| # | Win | Impact | Effort | Status |
| --- | --- | --- | --- | --- |
| 1 | Inline "Copy code" chip on fresh OTP rows in the inbox | Very high (the core use case: code without opening the thread) | S | Done |
| 2 | Bottom bar in the inbox: places sheet, search pill, compose FAB | High (one-handed reach) | S | Done |
| 3 | Configurable swipe actions with haptics, undo and TalkBack custom actions | High | S | Done |
| 4 | General multi-select with a bottom action bar | High | M | Done |
| 5 | Long-press message bottom sheet, ordered by frequency, plus message details | High | S | Done |
| 6 | Double-tap a bubble to copy its code or amount | High for bank and OTP users | S | Done |
| 7 | Swipe a bubble to reply (quote), with the OTP masked | Medium | M | Done |
| 8 | Jump-to-latest FAB | Medium | S | Done |
| 9 | Enter-to-send option, sentence capitalisation, back closes the tray | Medium | S | Done |
| 10 | A11y: toggle rows, headings, expander state, 48dp targets, live-region "Copied" | Medium (large for TalkBack users) | S | Done |
| 11 | Mark as unread (swipe or selection action) | Medium | M: needs `ProviderWriter.markUnread` and an index DAO change outside `:app` | Deferred |
| 12 | Hide the inbox bottom bar on scroll, keep the FAB | Low–medium | S | Deferred |
| 13 | Undo snackbar instead of a confirm dialog for unblocking and automation or forwarding deletes | Medium | S per screen | Deferred |
| 14 | `toggleable` rows on the Automations, Forwarding, Birthdays and Notification-channel switch rows | Medium for TalkBack | S | Deferred (other agents own those screens) |
| 15 | Font scale 200%: inbox row meta chips wrap into a `FlowRow`, selection-bar labels allow two lines | Medium | S | Deferred (needs on-device check) |
| 16 | Shared-element / predictive-back transitions between inbox row and thread | Low–medium | M (needs Navigation 2.8 `SharedTransitionLayout` wiring in `DakNavHost`) | Deferred |

## What was implemented

### Settings (`:settings-registry`, unit-tested)

- `categoriesSpam.swipeRight` (default archive) and `categoriesSpam.swipeLeft` (default delete to bin). Options:
  archive, delete, mark as read, pin/unpin, nothing. The values are in `SwipeActions`.
- `categoriesSpam.inboxOtpCopy` (default on): the inbox "Copy code" chip.
- `simsSending.enterToSend` (default off): the keyboard's action key sends.
- All four rows are in `DakSettings.all` and found by settings search ("swipe", "enter", "otp"). Tests are in
  `SwipeActionsTest`.

### Inbox (`ui/inbox`)

- **Bottom bar** (`InboxBottomBar.kt`): menu button → `PlacesSheet` (Passbook, Bin, Automations, Forwarding,
  Birthdays, Sender groups, Blocked, Backup, Settings, as 56dp rows with icons), a search pill, and the compose FAB
  docked in the M3 `BottomAppBar`. There is no top bar outside selection mode; the tabs sit under the status bar.
- **Swipes** (`InboxSwipe.kt`): the two settings map to physical left and right, and are mirrored for RTL. "Nothing"
  disables that direction. Each commit gives a haptic tick. The background shows the action's icon and label
  ("Unarchive" and "Unpin" when those apply). Archive, delete and pin show a snackbar with Undo; mark-as-read shows a
  confirmation. The swipe state reads the latest action through `rememberUpdatedState`, which also fixes a
  stale-closure risk in the unkeyed paged list.
- **Multi-select** (`InboxSelection.kt`): long-press selects, then taps toggle. The selected row's avatar becomes a
  check mark. The top bar shows close and the count (a polite live region). The bottom action bar has Archive,
  Delete, Read and Pin, plus More (Mute, Fold together, Select all loaded). Labels follow the selection ("Unarchive"
  when everything selected is archived). Bulk archive, delete and pin undo as one step (delete uses a single
  recycle-bin receipt). Back and predictive back leave selection mode first. Changing the tab or SIM clears the
  selection. Swipes are off while selecting.
- **OTP chip** (`InboxOtpChip.kt`): shown for enriched OTP-category rows whose newest message is incoming and less
  than 10 minutes old. The code is taken from the row preview with the on-device `OtpExtractor`; no extra query runs.
  A 30-second tick hides the chip when it expires. Tapping copies the code as a sensitive clip, gives a light haptic
  tick and shows "Copied" for 2 seconds (announced as a polite live region). It also cancels that OTP's auto-delete,
  like copying inside the thread (`InboxViewModel.onOtpCopied`). The chip has a 48dp touch target.
- **Accessibility**: every row exposes Archive, Delete, Mark as read, Pin and Mute as TalkBack custom actions, has
  click and long-click labels (Open, Select / Deselect) and a selected state, and the unread badge reads
  "3 unread messages". The badge also grows to fit "99+".

### Conversation (`ui/conversation`)

- **Message sheet** (`MessageActionsSheet.kt`): a bottom sheet replaces the dropdown. It shows a 3-line preview,
  then Copy text, Copy code, Copy amount, Reply, Forward, Star, Retry, Delete, Report fraud, Report spam (only where
  1909 applies) and Message details. Destructive rows are tinted with the error colour. `MessageInfoDialog` shows
  from/to, exact time, SIM, SMS/MMS, status and category with confidence.
- **Double-tap to copy** (`QuickCopy.kt`, `BubbleGestures.kt`): copies the OTP code, or else the transaction amount
  as a plain number that pastes cleanly into a payment app. It gives a haptic tick and a "Code copied" or "Amount
  copied" snackbar. A bubble with nothing to copy has no double-tap handler, so its single taps are not delayed.
- **Swipe to reply** (`SwipeToReply.kt`): drag a bubble towards the end edge. A reply icon fades in, a haptic tick
  marks the threshold, and releasing inserts a one-line `> quote` at the top of the draft and focuses the composer.
  Any OTP in the quote is masked (`••••`), so a reply can never send a code back out. The gesture is off where you
  cannot reply (alphanumeric or merged senders, composer disabled). Only horizontal drags are consumed, so list
  scrolling and the system back gesture still work.
- **TalkBack**: bubbles expose "Reply" and "Copy code" / "Copy amount" custom actions and a "Message actions"
  long-click label.
- **Jump to latest** (`JumpToLatest.kt`): a small FAB above the composer once you are more than 2 bubbles from the
  newest. It animates for short distances and snaps for long ones.
- **Composer**: optional Enter-to-send (`ImeAction.Send` with `KeyboardActions`), sentence capitalisation, back
  closes an open attachment tray, the attachment remove target is 40dp inside the thumbnail with a visible scrim,
  and the new-message screen honours Enter-to-send too.
- The bubble-colour swatches are 48dp `selectable` targets with a radio-button role.

### Global accessibility

- `SettingRow` toggle rows use `Modifier.toggleable(role = Switch)` with a non-interactive `Switch`, so TalkBack
  stops once per row and reads "on/off".
- The "Advanced" expander in a settings group is a heading and announces "Expanded" or "Collapsed".
- `DakTopAppBar` and `EmptyState` titles are headings, and the places sheet has a heading.

### Coordination notes

- `MessageBubble.kt` changed minimally: `BubbleDecor.canReply`, two default methods on `BubbleActions`
  (`onReply`, `onDoubleTap`), a `SwipeToReply` wrapper around the bubble `Surface`, and semantics plus `onDoubleClick`
  on its modifier. The bubble text composable is untouched, so entity spans can be added at the text call site.
- `InboxFoldSelection.kt` was replaced by `InboxSelection.kt`. Folding is now the More → "Fold together" action of
  the general selection.

## Deferred, with the reason

- **Mark as unread**: `ConversationRepository` and `ProviderWriter` have no unread write path. Adding one touches
  `:core-index` (DAO) and `:core-telephony` (provider `read = 0`). When it lands, add `InboxSwipeAction.MARK_UNREAD`
  and a "Unread" selection action.
- **Bottom bar hide-on-scroll** and **200% font-scale** refinements need an on-device pass to tune thresholds and
  wrapping.
- **Toggle-row semantics on other screens** (Automations, Forwarding, Birthdays, Notification channels) and
  **undo instead of confirm** for unblock, automation delete and forwarding delete: small edits in screens other
  agents own. Recommended as a follow-up sweep.
- **Shared-element transitions** between an inbox row and the thread header: needs `SharedTransitionLayout` in
  `DakNavHost`. Navigation 2.8's default cross-fade already works with predictive back.
