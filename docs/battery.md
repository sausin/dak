# Battery budget

Dak runs as the default SMS app, so the platform wakes it for every message. That part can't be avoided.
Everything Dak adds on top has to be close to free. SMS Organizer regularly triggered Android's "app is using
battery" warnings. Play Vitals flags three bad behaviours: *excessive wakeups* (more than 10 wakeup alarms an
hour), *stuck partial wake locks* (held 1 h or more in the background) and *excessive background Wi-Fi scans*.
Android also flags frequent jobs and long background CPU. This page lists every source of background work, with
its cost and the rule it must follow.

Reference load: a **heavy user** with 300 SMS/day, of which 60 are OTPs (about 40 read automatically by an app
through SMS Retriever / WebOTP, so "consumed").

## Rules

1. **No wakeup alarms of our own.** WorkManager uses JobScheduler on API 23+, and our minSdk is 26, so it sets no
   alarms. The only `RTC_WAKEUP` alarms are scheduled sends the user asked for (exact when the user allowed exact
   alarms).
2. **No wake locks held by us.** Receivers use `goAsync()`. Longer work runs in WorkManager, which holds the
   job's wake lock for exactly the job's duration. `WAKE_LOCK` is in the manifest only because WorkManager needs it.
3. **No foreground services**, except the one the platform requires: an expedited MMS download runs as
   WorkManager's `SystemForegroundService` (`dataSync`) below API 31.
4. **One job per purpose, never one job per message.** Pending per-message work goes into a queue that ONE unique
   job drains (see OTP auto-delete below).
5. **Housekeeping goes into the daily maintenance job.** Do not add a periodic worker: contribute a
   `MaintenanceTask` (below).
6. **Process start must be cheap.** Nearly every incoming SMS starts a process, so `Application.onCreate`
   and `IndexSync.start()` must not scan, rebuild or reschedule. They may only run cheap checks.
7. **Heavy init happens once per process, lazily, off the main thread.** This covers the classifier JSON
   (`ClassifierAssets`), the index database (Keystore + SQLCipher open deferred to the first query) and settings
   (preloaded on IO).
8. **Observers only react while the process is alive** and are coalesced. They never keep the process alive.

## Every background trigger

Frequencies are per day for the heavy user. "Wakeup" means the system has to run us when we were not already
running. Work that happens during an incoming-SMS broadcast piggybacks on a wakeup the platform makes anyway.

| # | Trigger | Kind | Before (per day) | After (per day) | Cost per run | Verdict |
|---|---|---|---|---|---|---|
| 1 | `SMS_DELIVER` → provider write → notify / index / automations | platform broadcast, `goAsync` + 7 s budget | 300 | 300 | provider insert, classify (under 1 ms), a few index writes | Required. Ingestion cost is unchanged. See rows 7 and 8 for what the process start no longer does. |
| 2 | `WAP_PUSH_DELIVER` → `MmsDownloadWorker` (expedited, 5 attempts with backoff) | platform broadcast + expedited job | per MMS | per MMS | one network download | Required. The foreground service below API 31 is the platform's requirement. No network constraint on purpose (the MMS APN works with mobile data off). |
| 3 | OTP auto-delete, 24 h after arrival | job | **60 one-shot jobs** (one per OTP) | **about 10–15 sweeps**, shared with row 4 | batch `moveToBin` | **Fixed.** One unique job `dak-otp-sweep` is armed for the nearest deadline. A 24 h delete may run 45 min early or 15 min late, so OTPs from the same hour are deleted in one run. |
| 4 | Consumed-OTP delete, 10 min after arrival | job | included in the 60 above, one job each | about 25–35 (usually while the screen is still on) | batch `moveToBin` | **Fixed.** Same sweep job. Never early (a retrying app can still read the code), at most 3 min late, so OTPs close together share a run. |
| 5 | Provider ContentObserver → incremental reconcile | in-process only | about 600 runs (300 ms debounce, 1–2 provider changes per SMS), each walking the whole SMS table for a count | about 300 runs (3 s coalescing). The table-count walk runs at most once per 30 min | indexed `id > max` query, refresh of 20 recent rows | **Fixed.** Coalesced in `IndexSync`. Never keeps the process alive. |
| 6 | Periodic provider reconcile (full deletion scan over all keys) | periodic job | 4 (every 6 h, battery not low) | folded into daily maintenance (row 9). The deletion scan is skipped when provider and index counts match | O(all messages) when it runs | **Fixed.** |
| 7 | App-signature table (consumed-OTP detection) | at process start + when a hash is unknown | a package scan at **every process start** (about 150) + up to once a minute when a hash is unknown | built once per install. Refreshed incrementally by maintenance. An unknown hash refreshes at most once a minute, and a hash that stays unknown is not retried for 6 h (remembered across processes) | `getInstalledPackages` + SHA-256 for changed packages only | **Fixed** in `AppSignatureRegistry`. The notifier now uses the same persisted table (`NotifierIndexLookups`, no refresh on this path); its old per-process rebuild (`ConsumedOtpDetector`) is deleted. |
| 8 | `IndexSync.start()` scheduling | at process start | 2 periodic-work enqueues per process start (about 150 WorkManager DB writes) | one SharedPreferences read | – | **Fixed** (`MaintenanceScheduler` spec-version flag). |
| 9 | Bin purge + audit trim | periodic job | 1 (daily, no constraints) | **1 maintenance job for everything** (daily, 6 h flex, **device idle + battery not low**) | a few deletes | **Fixed.** A catch-up run needing only battery-not-low happens if idle never came for 3 days. |
| 10 | Stage-2 backfill / re-index | one-shot unique job | until done | until done | batches of 500, cursor saved after each batch | OK. Never periodic, stops once done. NOW = no constraints (the user's explicit choice), WHEN_CHARGING = charging, TONIGHT = 01:00–05:00, idle + battery not low. |
| 11 | Encrypted backup | periodic job, only when a destination is set | 1 (network + battery not low) | 1, **now only while charging** | encrypt + upload | **Tightened.** Returns at once when no backup is due (weekly / manual). |
| 12 | Send retries / rate-limited bulk sends | one-shot per message | only after failures (max 5 attempts, 30 s … 30 min backoff) | same | one send | OK: bounded, and only happens on failure. |
| 13 | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` → `OutboxRecoveryWorker` | manifest receiver → 1 job | 1 per boot / update | same | a few provider queries | OK: the minimal work. OTP sweeps and maintenance survive reboots inside WorkManager, so no re-arming is needed. |
| 14 | Scheduled sends: exact alarm + WorkManager fallback; `BOOT` / `TIME_SET` / `TIMEZONE_CHANGED` → re-arm | alarm + job | per scheduled send; re-arm on each clock change | same | index DB open + alarms | Deferred to the automations owner (see below). |
| 15 | Settings first read | main thread | `runBlocking` disk read at the first activity | preloaded on IO when the DI graph is built; a read that races the load just waits for it | a small file | **Fixed** (`DataStorePersistenceAdapter`). |
| 16 | Index DB open (Keystore unwrap + SQLCipher) | first query | at the first injection, possibly **on main** (ViewModel) | at the first query, on Room's executor | 5–50 ms | **Fixed** (`IndexDatabaseFactory` deferred open helper). |
| 17 | Classifier JSON (templates 7 KB + model 16 KB) | per process | parsed **twice** (index enricher + notification classifier) | parsed once (`ClassifierAssets`) | a few ms | **Fixed.** |
| 18 | Relative-time ticker in lists | UI | every 60 s while visible | same | recomposition | OK: foreground only. |
| 19 | SIM changes | `OnSubscriptionsChangedListener` | event-driven | same | – | OK: no polling. |

### Before / after: jobs we schedule ourselves (heavy user)

| | Before | After |
|---|---|---|
| OTP deletes | 60 jobs (up to 120 with churn) | about 35–45 sweeps. Most are consumed-OTP sweeps a few minutes after the user just used the code, so the device is usually awake anyway |
| Periodic | 4 reconcile + 1 purge + 1 backup = 6 | 1 maintenance (idle) + 1 backup (charging) |
| Package scans | about 150 (every process start) + misses | at most 1 (maintenance) + rare misses |
| Reconcile runs (in-process) | about 600, each walking the whole table | about 300 cheap ones, at most 48 table counts |
| **Total scheduled jobs / day** | **about 66–126** | **about 37–47**, of which only the OTP sweeps can wake an idle device, and those follow user activity |

Wakeup alarms owned by Dak: 0 before and 0 after (not counting user-scheduled sends).

## The maintenance job (`app.dak.index.maintenance`)

This is ONE unique periodic job, `dak-maintenance`: daily with a 6 h flex window, constraints device idle and
battery not low. It runs every task in the multibound `Set<MaintenanceTask>` in `order`, each isolated:

```kotlin
interface MaintenanceTask {
    val name: String
    val order: Int get() = 100   // built-ins use 0-99
    suspend fun run()
}

// In any SingletonComponent Hilt module:
@Binds @IntoSet abstract fun birthdays(impl: BirthdayRescheduleTask): MaintenanceTask
```

| Order | Task | What it does |
|---|---|---|
| 10 | `BinPurgeTask` | Purges expired bin entries (OTP 1 day, others 30) and trims the audit log |
| 20 | `ProviderReconcileTask` | Safety-net full reconcile (missed changes and deletions) |
| 30 | `SignatureRefreshTask` | Incremental app-hash refresh (only changed packages) |
| 100+ | features | e.g. automations' daily housekeeping (expired rules, forwarding status, birthday re-scan), template refresh when OTA fetching lands |

Guidance for tasks: keep each one short (well under a minute) and idempotent. The job can be stopped when the
device leaves idle, and the remaining tasks then run the next day. Tasks that need a precise time (a birthday
wish at 09:00) should only re-arm their own alarm here. `MaintenanceScheduler.runSoon()` triggers a pass on demand.

## OTP auto-delete (`app.dak.index.otp`)

- `OtpLifecycle.onIndexed` adds an entry to `OtpDeleteQueue`: a small SharedPreferences file with one line per
  pending delete. The list is at most a day's OTPs, needs no schema change and survives an index rebuild.
- `OtpSweepPlan` (pure, unit-tested) gives each entry a tolerance window. Consumed OTPs: `[deleteAt, +3 min]`.
  24 h deletes: `[-45 min, +15 min]`. The single job is armed for the most urgent *deadline*. When it runs, it
  deletes every entry whose window has opened, in one `moveToBin` per `DeletedBy`, then re-arms.
- `cancel(key)` (copy / star) removes the entry and is main-safe. Starred rows are re-checked at sweep time. A
  policy switched off after queuing wins, so the message stays.

## Debug activity log

Debug builds keep per-day counters (`BackgroundActivityLog`) of process starts, indexed incoming messages,
incremental reconciles, OTP sweeps, maintenance runs and backfill runs. They are shown at the bottom of the
notification self-test screen. Release builds write nothing. On a heavy day, expect roughly 300 `incoming-indexed`,
300 or fewer `reconcile-incremental`, about 40 `otp-sweep-job` and 1 `maintenance-job`.

## Deferred (owned by other areas; do these after the current wave lands)

1. **`app/automation/ScheduledSendReceiver`**:
   - `TIME_SET` / `TIMEZONE_CHANGED` / `BOOT_COMPLETED` call `rearmPending()`, which opens the index DB even
     when nothing is scheduled. Keep a SharedPreferences "has pending sends" flag and return early when it is
     false.
   - A due send currently has three wakeup sources: the exact alarm, the per-send WorkManager fallback, and the
     extra run-now job enqueued by the receiver. Enqueue the run-now job only if `runDue()` fails.
2. **`DailyHousekeeping`** (automations): it piggybacks on existing wakeups, which is good. It should also
   contribute a `MaintenanceTask` that calls `runIfDue()`, so it still runs on days without messages. It should
   not get its own periodic worker.
3. **`core-telephony` `TelephonyProviderReader.totalMessageCount()`** walks whole cursors to count. It is now
   called at most once per 30 min. A `COUNT(*)`-style query would be cheaper where the provider supports it.
