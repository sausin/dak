#!/usr/bin/env bash
# Guard: the free tier must work fully offline with no AI/cloud integration at runtime.
# Fails if production code (outside src/premium) references network clients or binds a cloud classifier.
set -euo pipefail
cd "$(dirname "$0")/.."
pattern='HttpURLConnection|HttpsURLConnection|openConnection\(|okhttp3|retrofit2|io\.ktor|java\.net\.http|WebSocket|firebase|com\.google\.mlkit|com\.google\.ai|generativeai|anthropic|openai'
hits=$(grep -rnE "$pattern" --include=*.kt --include=*.kts --include=*.java \
  --exclude-dir=build --exclude-dir=test --exclude-dir=premium . \
  | grep -v 'scripts/check-offline-baseline.sh' || true)
bindings=$(grep -rnE '(@Binds|@Provides).*CloudClassifier|: CloudClassifier *=' --include=*.kt \
  --exclude-dir=build --exclude-dir=test --exclude-dir=premium . \
  | grep -v 'NoCloudClassifier' || true)
if [ -n "$hits$bindings" ]; then
  echo "Offline baseline violated: free/shared code must not depend on network or AI services at runtime."
  echo "Put such code behind a premium-api interface with a no-op default, implemented only in src/premium."
  printf '%s\n' "$hits" "$bindings"
  exit 1
fi
echo "Offline baseline OK: no network/AI runtime dependencies outside src/premium."
