#!/usr/bin/env bash
# Runs the pure-Kotlin modules' tests without the Android SDK or Google Maven
# (useful in sandboxes where only Maven Central / the Gradle plugin portal are reachable).
# Usage: scripts/jvm-test.sh [gradle args...]   (default: test)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="${DAK_JVM_HARNESS_DIR:-$here/build/jvm-harness}"
mkdir -p "$work"
modules=(core-model premium-api classify finance automations search backup settings-registry)
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
cat > "$work/build.gradle.kts" <<'KTS'
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
KTS
gradle_bin="${GRADLE:-gradle}"
cd "$work"
if [ "$#" -eq 0 ]; then set -- test; fi
exec "$gradle_bin" --no-daemon -q "$@"
