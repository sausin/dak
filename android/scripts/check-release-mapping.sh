#!/usr/bin/env bash
# Release smoke check: R8 really ran on the release build, and the names Dak persists survived it
# (see app/proguard-rules.pro and docs/release.md).
# Usage: scripts/check-release-mapping.sh [variant...]   (default: freeRelease)
set -euo pipefail
cd "$(dirname "$0")/.."

variants=("$@")
[ ${#variants[@]} -gt 0 ] || variants=(freeRelease)

# Classes whose names must not be obfuscated: persisted ActionSpec kinds and WorkManager workers.
must_keep=(
  'app.dak.automations.rule.ActionSpec$ForwardSms'
  'app.dak.automations.rule.ActionSpec$ScheduleReply'
  'app.dak.automations.rule.ActionSpec$Webhook'
  'app.dak.automation.ScheduledSendWorker'
  'app.dak.backup.BackupWorker'
  'app.dak.index.sync.BackfillWorker'
  'app.dak.telephony.send.SendRetryWorker'
)

status=0
for variant in "${variants[@]}"; do
  mapping="app/build/outputs/mapping/$variant/mapping.txt"
  if [ ! -s "$mapping" ]; then
    echo "::error::$variant: no R8 mapping at $mapping (is isMinifyEnabled on for release?)"
    status=1
    continue
  fi
  # Class lines look like "original.Name -> obfuscated.Name:".
  listed=$(grep -cE '^app\.dak\.[^ ]+ -> [^ ]+:$' "$mapping" || true)
  obfuscated=$(awk '/^app\.dak\.[^ ]+ -> [^ ]+:$/ { to = $3; sub(/:$/, "", to); if ($1 != to) n++ } END { print n + 0 }' "$mapping")
  if [ "$obfuscated" -eq 0 ]; then
    echo "::error::$variant: no app.dak class was renamed ($listed listed); R8 obfuscation did not run"
    status=1
  fi
  for cls in "${must_keep[@]}"; do
    line=$(grep -F -- "$cls -> " "$mapping" | grep -E '^[^ ]' | head -1 || true)
    if [ -z "$line" ]; then
      # R8 lists every class it emits; a missing one was removed as unused, so its name cannot be persisted.
      echo "::warning::$variant: $cls not in the mapping (removed as unused?)"
    elif [ "$line" != "$cls -> $cls:" ]; then
      echo "::error::$variant: $cls was renamed ($line); it is persisted by name, fix app/proguard-rules.pro"
      status=1
    fi
  done
  echo "$variant: R8 mapping checked ($obfuscated of $listed app.dak classes obfuscated, persisted names kept)"
done
exit $status
