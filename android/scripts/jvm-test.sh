#!/usr/bin/env bash
# Runs the pure-Kotlin modules' tests without the Android SDK or Google Maven
# (useful in sandboxes where only Maven Central / the Gradle plugin portal are reachable).
# Usage: scripts/jvm-test.sh [gradle args...]   (default: test)
#   Coverage: scripts/jvm-test.sh koverXmlReportCoverage koverHtmlReportCoverage
#   then  scripts/coverage-summary.py --root-report "$DAK_JVM_HARNESS_DIR/build/reports/kover/reportCoverage.xml" --jvm-only
#   (the harness dir defaults to build/jvm-harness; module reports land in <module>/build/reports/kover/)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="${DAK_JVM_HARNESS_DIR:-$here/build/jvm-harness}"
mkdir -p "$work"
modules=(core-model mms-pdu premium-api classify finance automations search backup settings-registry)
{
  echo 'pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }'
  echo 'dependencyResolutionManagement {'
  echo '  repositories { mavenCentral() }'
  echo "  versionCatalogs { create(\"libs\") { from(files(\"$here/gradle/libs.versions.toml\")) } }"
  echo '}'
  echo 'rootProject.name = "dak-jvm"'
  for m in "${modules[@]}"; do
    echo "include(\":$m\"); project(\":$m\").projectDir = file(\"$here/$m\")"
  done
} > "$work/settings.gradle.kts"
# Reuse the real root build (Kover coverage config included), minus the plugins that need Google Maven.
grep -vE 'libs\.plugins\.(android|ksp|hilt|kotlin\.compose)' "$here/build.gradle.kts" > "$work/build.gradle.kts"
gradle_bin="${GRADLE:-gradle}"
cd "$work"
if [ "$#" -eq 0 ]; then set -- test; fi
exec "$gradle_bin" --no-daemon -q "$@"
