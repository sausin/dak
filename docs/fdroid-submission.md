# F-Droid submission checklist

F-Droid is the first store for Dak; Google Play follows ([play-submission.md](play-submission.md)). F-Droid builds
the app itself from this repository's source, so most of this file is about making that build clean and repeatable.
Re-read the current [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/),
[Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/) and
[Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/) pages before submitting; items marked
**verify** are our reading and must be checked against the live text.

## 1. Inclusion policy audit

| Requirement | State | Evidence |
|---|---|---|
| FOSS licence, source public | MIT, `LICENSE` | |
| No proprietary dependencies (Google Play Services, Firebase, Crashlytics, ML Kit, Play Billing, Play Core) | None in the dependency graph | `gradle/libs.versions.toml`; `scripts/check-offline-baseline.sh` fails the build on Firebase / ML Kit / network clients outside `src/premium` |
| All dependencies are FOSS | AndroidX, Compose, Kotlin/kotlinx, Hilt/Dagger (Apache 2.0), libphonenumber (Apache 2.0), Coil (Apache 2.0), SQLCipher Community Edition (BSD-style) | From Google's Maven and Maven Central |
| No binaries in the source tree | Only `android/gradle/wrapper/gradle-wrapper.jar` (F-Droid uses its own Gradle) and `android/app/debug.keystore` (the public throwaway debug key; the recipe removes it) | `find . -name '*.jar' -o -name '*.so' -o -name '*.keystore'` |
| Bundled ML model built from source | Yes: `classify/.../model-weights.json` is a Naive Bayes table regenerated from the in-repo seed corpus by `GenerateModelWeightsTest` (`android/classify/README.md`) | No opaque model files |
| No tracking, no ads | None; free tier has no network code of its own (MMS goes through the platform `MmsService`) | [privacy-policy.md](privacy-policy.md), `check-offline-baseline.sh` |
| Anti-features | **None expected.** Not `NonFreeNet` (no server), not `Tracking`, not `Ads`, not `NonFreeDep`. `INTERNET` is only there for carrier MMS | |
| No dependency-metadata signing block | `dependenciesInfo { includeInApk = false }` in `app/build.gradle.kts` (F-Droid's scanner rejects the encrypted block) | |
| Version readable from source | `versionCode` / `versionName` are literals in `app/build.gradle.kts`; CI fails a `v*` tag that does not match | `.github/workflows/android.yml` "Resolve version" |
| Which flavour | `free` only. `premium` (`app.dak.premium`) is not submitted to F-Droid | |

Native code: SQLCipher ships prebuilt `libsqlcipher.so` inside its Maven AAR. F-Droid generally accepts FOSS
libraries from Maven Central, but a reviewer may ask for it to be built from source (**verify** in the merge
request; other apps in the F-Droid repo use `net.zetetic:sqlcipher-android` from Maven). Its 4.19.0 libraries are
16 KB page aligned (checked with `readelf -l`: every `LOAD` segment is aligned to `0x4000`).

## 2. Store listing (fastlane)

F-Droid (and Play, through fastlane `supply`, if we automate it later) reads `fastlane/metadata/android/<locale>/`:

| File | Limit | State |
|---|---|---|
| `title.txt` | 30 (Play) / 50 (F-Droid) | Done |
| `short_description.txt` | 80 | Done |
| `full_description.txt` | 4000 | Done |
| `changelogs/<versionCode>.txt` | 500 | `1.txt` done; add one per release, **before tagging** (CI fails the tag otherwise) |
| `images/icon.png` | 512×512 PNG | Done (rendered from the adaptive-icon layers) |
| `images/featureGraphic.png` | 1024×500 PNG | Done |
| `images/phoneScreenshots/*.png` | 2–8 | **To do** (see below) |

`android/scripts/check-store-metadata.py` checks these limits in CI. F-Droid ignores the listing's HTML, so keep
`full_description.txt` plain text with `•` bullets.

Screenshots (light theme, English, dummy data only: no real numbers, names, OTPs or bank messages; the
`scripts/sms-pdu.py` tool or an emulator's SMS console can inject fictional messages):

- [ ] Inbox with the category tabs, OTP "Copy code" chip visible
- [ ] OTP notification (big code, "Copied")
- [ ] Passbook grouped by instrument
- [ ] Fake-credit scam warning in a thread
- [ ] Link-safety second-tap sheet
- [ ] Search with `amount:>500` chips
- [ ] Settings → Privacy hub

## 3. Build recipe (fdroiddata merge request)

File this as `metadata/app.dak.yml` in a merge request to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) (fork, add the file, run `fdroid lint app.dak` and
`fdroid build -v -l app.dak` locally or let the MR pipeline do it):

```yaml
Categories:
  - Phone & SMS
License: MIT
AuthorName: Saurabh
SourceCode: https://github.com/sausin/dak
IssueTracker: https://github.com/sausin/dak/issues
Changelog: https://github.com/sausin/dak/releases

AutoName: Dak

RepoType: git
Repo: https://github.com/sausin/dak.git

Builds:
  - versionName: 0.1.0
    versionCode: 1
    commit: v0.1.0
    subdir: android/app
    gradle:
      - free
    rm:
      - android/app/debug.keystore

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.1.0
CurrentVersionCode: 1
```

Notes:

- `subdir: android/app` because the Gradle root is `android/`; F-Droid finds `versionCode` / `versionName` in
  `android/app/build.gradle.kts` for `UpdateCheckMode: Tags`.
- `gradle: [free]` builds `assembleFreeRelease`, unsigned; F-Droid signs it (or, once reproducible, ships ours).
- `rm` drops the committed debug key; only debug builds read it, so the release build is unaffected.
- Toolchain: AGP 9.4, Gradle 9.7.1, Kotlin 2.4, JDK 17, compileSdk 37. **Verify** the buildserver's default JDK
  and that it installs platform 37 on demand; if not, add `sdkmanager` lines under `prebuild`/`sudo`.
- Leave `AllowedAPKSigningKeys` / `Binaries` out of the first MR; add them in §4 once reproducibility is proven.

## 4. Reproducible builds and signing (recommended)

Without this, F-Droid signs Dak with **F-Droid's** key and Play with **Google's or ours**: users cannot switch
between an F-Droid install and a Play install without uninstalling (and losing the index; messages themselves
stay in the system SMS store). With a reproducible build, F-Droid verifies that its build matches our signed APK
and ships **our** signature, so both stores install over each other.

1. Create one release keystore offline (`keytool -genkeypair -keyalg RSA -keysize 4096 -validity 10000`); back it
   up in two places. Put it in the `DAK_KEYSTORE_*` secrets; CI then signs the tag's APKs and the AAB.
2. Tag `v0.1.0`. CI attaches `dak-0.1.0-free-release.apk` to the GitHub release.
3. Check that F-Droid's unsigned build matches: `fdroid build app.dak:1` locally, then
   `apksigcopier compare dak-0.1.0-free-release.apk <fdroid-built>.apk` (**verify** the current tool name). Common
   differences: JDK vendor/version, build path, `baseline.prof`, the `version-control-info.textproto` AGP writes.
4. Add to the recipe:
   ```yaml
   Binaries: https://github.com/sausin/dak/releases/download/v%v/dak-%v-free-release.apk
   AllowedAPKSigningKeys: <sha256 of the signing certificate, lowercase hex, no colons>
   ```
   (`apksigner verify --print-certs dak-0.1.0-free-release.apk` prints it.)
5. For Play, use **the same key as the app signing key** in Play App Signing ("Use a different app signing key" →
   upload the key with the PEPK tool) and a separate upload key. Then Play, F-Droid and GitHub builds all carry one
   signature. Decide this **before** the first Play upload: Play's app signing key cannot be changed later except by
   a key upgrade that older Android versions do not honour.

Android developer verification (**verify** current dates): Google now requires apps installed on certified
devices, sideloaded and F-Droid included, to be registered to a verified developer in some countries, with wider
rollout announced. Registration is per package name and signing key. Registering `app.dak` with the key above
(through the Play Console when the app is created) covers the F-Droid build too, but only if F-Droid ships our
signature (step 4).

## 5. Before opening the merge request

- [ ] Privacy-policy placeholders replaced (`dak.example` URL, `privacy@dak.example`, effective date, who "we"
      are); see [play-submission.md §1](play-submission.md#1-before-you-start). The in-app policy is shown to F-Droid
      users too.
- [ ] Phone screenshots added (§2).
- [ ] `versionCode` / `versionName` bumped in `app/build.gradle.kts` if needed, changelog added, tag pushed, CI green.
- [ ] Signed release APK installed on a phone and the P0 items of [device-test-plan.md](device-test-plan.md) pass.
- [ ] `fdroid lint app.dak` and `fdroid build -v -l app.dak` pass on the recipe (§3).
- [ ] README "Status" updated with the F-Droid link once the MR is merged and the app is published (takes a few days
      after merge).
