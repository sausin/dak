# Testing and code coverage

All tests are JVM unit tests (`*/src/test`): plain JUnit for the pure-Kotlin modules, Robolectric for the
Android ones. Nothing here runs on a device or emulator; see [`device-test-plan.md`](device-test-plan.md) for that.

## Running

```sh
cd android
./gradlew test                                               # every module, every variant
./gradlew test koverXmlReportCoverage koverHtmlReportCoverage # same, plus coverage reports
python3 scripts/coverage-summary.py                          # table + floor check (needs the reports above)
```

Without the Android SDK (or without Google Maven), the pure-Kotlin modules still run, with coverage:

```sh
scripts/jvm-test.sh                                                  # tests only
scripts/jvm-test.sh koverXmlReportCoverage koverHtmlReportCoverage   # tests + coverage
python3 scripts/coverage-summary.py --jvm-only \
  --root-report build/jvm-harness/build/reports/kover/reportCoverage.xml
```

(`DAK_JVM_HARNESS_DIR` moves the harness out of `build/jvm-harness`; pass the matching `--root-report`.)

The adversarial SMS corpus ([`shared/adversarial/`](../shared/adversarial/README.md): scams per region with their
benign look-alikes, and robustness payloads aimed at the app) runs as part of `:classify:test`. To run it alone:
`./gradlew :classify:test --tests '*Adversarial*'` (or `scripts/jvm-test.sh :classify:test --tests '*Adversarial*'`).

## How coverage is measured

[Kover](https://github.com/Kotlin/kotlinx-kover) (Gradle plugin `org.jetbrains.kotlinx.kover`, version in
`android/gradle/libs.versions.toml`) instruments the unit-test JVMs. It is a build-time plugin only: nothing is
added to any runtime classpath or APK, so the free tier's offline baseline is unaffected.

Every module applies Kover and declares one report variant named `coverage`:

| Modules | `coverage` variant = | Why |
| --- | --- | --- |
| core-model, mms-pdu, premium-api, classify, finance, automations, search, backup, settings-registry | `jvm` (the `test` task) | Pure Kotlin |
| core-telephony, core-index | `debug` (`testDebugUnitTest`) | Release unit tests exercise the same code |
| app | `freeDebug` (`testFreeDebugUnitTest`) | Free and premium compile the same shared classes; two copies of a class cannot be merged, so one flavour is measured. Premium-only code (`app/src/premium`) is therefore not in the number. |

The root project merges every module's `coverage` variant. Reports:

- per module: `android/<module>/build/reports/kover/reportCoverage.xml` and `htmlCoverage/`
- merged: `android/build/reports/kover/reportCoverage.xml` and `htmlCoverage/`

A module's own report counts only that module's classes, covered by that module's tests. The merged report also
credits a class with coverage from other modules' tests (for example, `core-model` types exercised by
`classify`'s tests), so the total can be higher than a line-weighted average of the module numbers.

**Headline metric: line coverage.** Branch coverage is reported alongside it in the CI summary but is not gated.

## What is excluded

Configured once in `android/build.gradle.kts` for every module and the merged report:

- **Generated code**: Hilt/Dagger (`*_Factory`, `*_MembersInjector`, `Hilt_*`, `*_HiltModules*`,
  `*_HiltComponents*`, `*_GeneratedInjector`, `*_ComponentTreeDeps`, `dagger.*`, `hilt_aggregated_deps.*`, and
  anything annotated `@DaggerGenerated` / `@Generated`), Room `*_Impl`, `BuildConfig`, `R`/`Manifest`,
  Compose's `ComposableSingletons*`, and kotlinx.serialization `$$serializer` classes.
- **Compose UI functions**: every function annotated `@Composable` (and `@Preview`). Compose UI is not
  unit-tested here (no Compose UI tests, no screenshot tests), so counting it would only measure how much UI
  exists, not how well logic is tested. The exclusion is by annotation, not by package: the `ui.*` packages
  also hold ViewModels, formatters and state reducers, which are tested and stay in the metric. If Compose UI
  tests are added later, drop the `androidx.compose.runtime.Composable` entry so they count.

## CI gate and floors

`.github/workflows/android.yml` runs `./gradlew --continue test koverXmlReportCoverage koverHtmlReportCoverage`
(one test run), then `scripts/coverage-summary.py`, which:

1. writes a per-module line/branch table (and the merged total) to the job summary,
2. writes the badge JSON,
3. fails the job if any module's line coverage, or the merged total, is below its floor in
   [`android/coverage-floors.json`](../android/coverage-floors.json).

The HTML + XML reports are uploaded as the `coverage-report-<run>` artifact.

Floors are ratchets: set each to `floor(measured) - 1`, raise them when coverage rises, and only lower one with a
reason in the commit message. The JVM modules' floors were measured locally with `scripts/jvm-test.sh`; the
Android modules and the total start at 0 until the first CI run on `main` gives real numbers.

Measured at introduction (JVM modules, `scripts/jvm-test.sh`):

| Module | Line | Branch | Floor |
| --- | ---: | ---: | ---: |
| automations | 94.7% | 70.2% | 93 |
| backup | 93.2% | 64.5% | 92 |
| classify | 97.5% | 81.0% | 96 |
| core-model | 29.5% | 17.8% | 28 |
| finance | 97.3% | 81.4% | 96 |
| mms-pdu | 95.0% | 82.5% | 94 |
| premium-api | 86.5% | 84.2% | 85 |
| search | 97.0% | 81.9% | 96 |
| settings-registry | 99.3% | 82.7% | 98 |
| JVM modules merged | 95.8% | 75.1% | — |

`core-model` is low on its own because it is mostly data types exercised by other modules' tests (the merged
report credits that); its own floor keeps its own tests from shrinking.

## README badge

On pushes to `main` only, CI commits `coverage.json` (a [shields.io endpoint](https://shields.io/badges/endpoint-badge)
document: `{"schemaVersion":1,"label":"coverage","message":"NN.N%","color":...}`) to the orphan `badges` branch,
creating it on the first run. The README badge reads it through shields.io:

```
https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/sausin/dak/badges/coverage.json
```

The value is the merged total line coverage. Colours: ≥80 brightgreen, ≥70 green, ≥60 yellowgreen, ≥50 yellow,
≥40 orange, else red. Until the workflow has run once on `main`, the branch does not exist and the badge shows
an error/"invalid" placeholder. Pull-request runs never push anything; the badge step is also
`continue-on-error`, so a failed push loses one badge update, not the build. raw.githubusercontent.com and
shields.io both cache for a few minutes, so the badge lags a push slightly.
