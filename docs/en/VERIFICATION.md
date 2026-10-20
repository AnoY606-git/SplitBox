**English** · [Русский](../VERIFICATION.md) · [Developer guide](README.md)

# Verification record

Checks of the 1.2.1 release APK appear first with its exact SHA-256. Historical results for published APKs, including 1.2, and Settings development before the version increase remain separate below. Old hashes and Android results are not assigned to the new artifact.

# SplitBox 1.2.1 verification

Date: **20 October 2026**. Checked signed release APK: versionName **1.2.1**, versionCode **8**, minSdk **34** / target SDK **36**, SHA-256 **`bb3e39c1e789bb25938933d8070c374187b448cb050df16d239d1ff9494d3af8`**. Android results in this section belong to this APK.

## Automated checks

The local Gradle run completed successfully in **2 min 7 s**. **276 JVM tests** passed: core **100**, app **176**, with **0 failures/errors/skipped**. Separately, **13 Python release-tool tests** passed. Lint debug/release reported **0 errors / 11 previous warnings** each.

## Signed-APK update on Android

A project-owned dedicated AOSP API 34 x86_64 AVD successfully updated from the public 1.2 APK, SHA-256 **`96bffd38db29e6bd0da8ac6374c7a6b3f52800ce94d3796b06737866ba3b0c3f`**, to the signed 1.2.1 APK identified above through `adb install -r`. The permanent publisher certificate was retained; app data was not deleted.

The Russian app locale survived the update. Theme `DARK`, selected in the old 1.2 build, remained selected and appeared in the new Settings interface. The installed package flags did not contain `DEBUGGABLE`. Crash buffer was empty after the check.

## Version 1.2.1 limits

This checks updating and retaining preferences on the identified AOSP emulator. No new full installation scenarios through System/Root/Shizuku, large APK/APKS/OBB runs, TalkBack, physical ARM devices, or Android 15/16 checks were performed in this run. The 200% font interface checks below used the same UI code before the version increase and remain historical; they do not count as a repeated test of the final 1.2.1 APK.

---

# Historical record: unreleased Settings presentation changes before 1.2.1

Date: **20 October 2026**. This section covers source changes after published 1.2 and before the version increase for 1.2.1. At the time of these checks, the version remained **1.2 / versionCode 7**; no new release had yet been created or published. These results are retained as historical evidence and do not count as checks of the 1.2.1 release APK. Historical published-APK results remain separate below.

## Automated checks and local APK

Tasks `:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintRelease`, `:app:assembleDebug`, and `:app:assembleRelease` completed successfully offline with Kotlin compiler execution strategy `in-process`. **276 JVM tests** passed: core **100**, app **176**, with **0 failures/errors/skipped**. **13 Python release-tool tests** passed. A new source-ZIP regression test first reproduced exclusion of `README.ru.md`, then passed after the fix. The source archive now includes the Russian README.

Lint debug/release: **0 errors / 11 existing warnings** each: `UseKtx` × 6, `UsableSpace` × 4, `ObsoleteSdkInt` × 1. Independent review of the current change finished with no open findings in the reviewed area; this is a limited conclusion rather than a guarantee that every defect is absent.

Local signed release APK: versionName **1.2**, versionCode **7**, SHA-256 **`f7a80c2350b569f7715a7cc603cf5446804d0ea775b558d8590e80f5bceee5a0`**. This artifact is **unpublished** and does not replace the existing v1.2 release APK. Runtime scenarios below used a debug APK, rather than this signed release APK.

## Android interface checks

A project-owned dedicated AOSP API 34 x86_64 AVD used 720×1440 px, density 360, and **320 dp** width. Russian and English Settings screens and actual actions were checked:

| Scenario | Observed result |
| --- | --- |
| Language | The language dialog actually switched the interface to Russian |
| Theme, 200% font | Theme options and the close action remained accessible. Dark theme was selected; cold restart retained `DARK` |
| Size limit, Russian interface, 200% font | Choices precede the explanation; scrolling reaches 64 GiB. After selection and cold restart, reading preferences confirmed `maxExpandedGiB=64` |
| Stability | Crash buffer was empty after the runtime scenarios |

The size dialog's explanation was already scrollable before this change. Placing choices before it improves access to selection at large font sizes; it does not fix a previously absent scrolling capability.

## Limits of these historical checks

TalkBack, physical ARM devices, real Root installation, and new large-package installation runs were not repeated in this work. Debug-APK interface checks do not constitute a new signed-release runtime test or testing of every installation method. Earlier large APKS/OBB results remain attached to the older builds identified below.

---

# SplitBox 1.2 verification

Date: **20 October 2026**. Shipped build: versionName **1.2**, versionCode **7**, minSdk 34 / target SDK 36. Release tag: [v1.2](https://github.com/AnoY606-git/SplitBox/releases/tag/v1.2). Earlier versions retain separate results below; their Android runs are not presented as repeated 1.2 testing.

## Automated checks and review

The full local run of `:core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :fixture:assembleDebug` completed **BUILD SUCCESSFUL**. **276 JVM tests** passed: core **100**, app **176**, with **0 failures/errors/skipped**. Separately, **12 Python tests** of release tools and source-inventory checks passed.

Both **653-string** catalogs are checked for matching keys/format arguments, whitelist, and allowed formats. Tests cover a persisted error in EN/RU, literal external data, structural URI redaction, message depth, and UTF-8 budget. Fourteen `AccessCoordinatorTest` scenarios cover mode selection, denial/explicit retry, pending-request deduplication, mode changes, late replies, Binder death/replacement, active installation, and an old Root result despite cancellation.

Regressions were observed before fixes: duplicate Shizuku requests on retry/mode change, a late dead-service callback, delayed Binder-death delivery, double report resolution, `StackOverflowError` with 32 multibyte arguments, lost initial `INSTALL_FAILED_INVALID_APK` during truncation, and native PM output interpreted as a project message. Fixes passed separate RED→GREEN checks and independent review. An additional runtime test found the old date format after changing the language of an open report; record selection by ID and an explicit formatter locale fixed it. No confirmed open P1/P2 remained in the reviewed area; this is a limited review conclusion, not a promise that every defect is absent.

Lint debug/release: **0 errors / 11 warnings** each: `UseKtx` × 6, `ObsoleteSdkInt` × 1, `UsableSpace` × 4. These concern APIs/style and conservative available-space accounting; they were not suppressed.

## APK and signing

Final R8 APK: **3,213,084 bytes**, SHA-256 **`96bffd38db29e6bd0da8ac6374c7a6b3f52800ce94d3796b06737866ba3b0c3f`**. Publisher certificate is unchanged: `276541bb10833dc482bdf54f4760498633a38316b13a29901b84cfdb3a316478`, RSA 4096, v2 signature. `apksigner verify` and APK audit passed for package/version/SDK, permissions, CRC, ZIP/ELF alignment, and legal assets. The shipped app is not debuggable, has no INTERNET permission, and adds no runtime dependencies.

## Android runtime

A disposable, dedicated AOSP API 34 x86_64 AVD used fingerprint `Android/sdk_phone64_x86_64/emu64x:14/UE1A.230829.036.A1/11228894:userdebug/test-keys`. Screen: 720×1440 px at 360 dpi, **320 dp** wide. Neither personal Waydroid nor physical devices were used. Test APK: project-owned `dev.splitbox.fixture`, **10,661 bytes**.

The final APK with the hash above was checked for:

| Scenario | Observed result |
| --- | --- |
| Update and state | Same-certificate update over the preceding build without data deletion; history/settings available. The update chain included public 1.1.1 |
| System | Real SAF import, unknown-sources permission settings, and system confirmation. App language changed to RU during confirmation; install succeeded with `installerPackageName=dev.splitbox.app` |
| Shizuku | Real project APK installation through official Shizuku 13.6.0, shell UID 2000. Success confirmed; Package Manager reports `initiatingPackageName=com.android.shell` |
| Automatic access | Selecting Root without a manual check button produced the appropriate unavailable state on AOSP. Selecting Shizuku automatically reflected the active permission; mode did not change by itself |
| EN/RU and large font | Prepared package survived EN↔RU. Summary/install action visible in both languages at fontScale 2.0; Shizuku install started from that action. Labels did not overlap bottom navigation |
| Report | Open report retained its selected record on language change and localized fields/date format. Preview/copy used the same resolved text |

Intermediate 1.2 builds additionally checked automatic native Shizuku permission requests on mode selection, denial without another prompt after Home/resume, explicit retry, and grant. Stopping the service removed authorized status; restarting and returning restored it. “Open Shizuku” opened the official manager. Checks included system/in-app language selection, native return to system language, cold prepared-package recovery, and main-screen actions at 320 dp/200%. Crash buffer was empty after runtime runs.

## Version 1.2 limits

Physical ARM64, Android 15/16, and real Magisk/KernelSU were not tested; AOSP Root status does not establish phone root-manager compatibility. Real 9 GiB APKS and 5 GiB OBB were not regenerated for this release. Corresponding **1.1.1** runs remain below; large-file/ZIP64/OBB JVM regressions also passed in 1.2. No measured speedup or percentage coverage of all possible faults is claimed.

Old plain-text history/recovery messages retain their original language; new structured messages are translated when displayed. External app/file names and native Android diagnostics are not translated. Excessive details are bounded while preserving a useful prefix and an explicit truncation marker.

---

# SplitBox 1.1.1 verification

Date: **19 October 2026**. Signed build: versionName **1.1.1**, versionCode **6**, minSdk 34 / target SDK 36. Tag: [v1.1.1](https://github.com/AnoY606-git/SplitBox/releases/tag/v1.1.1). Older results are archived below.

## Automated checks and review

The full run of `:core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease` completed **BUILD SUCCESSFUL**. **245 JVM tests** passed (core 91, app 154), **0 failures/errors/skipped**, plus **12 Python release-tool tests**. Debug and fixture had been built in the combined run before the final focused insufficient-storage fix.

New regressions first reproduced the APK resource limit, ZIP64 rejection, trust in incorrect EOCD entry counts, cancellation converted to ordinary failure, Shizuku size/fixed-timeout limits, lost cleanable-cache/OBB checkpoints, and deletion of prepared packages after insufficient storage. All suites passed after fixes. The 8 GiB + 123-byte sparse check is distinct from full-payload Android processing.

Independent core/app review checked cleanup, recovery, and READY restoration after insufficient storage. A real shell test reproduced an OBB temporary file left after SIGKILL/interrupt; the fix passed five separate tests preserving an existing OBB and unrelated temporary file, including early cancellation and deletion failure. A real central-directory boundary test accepted 32 MiB and rejected 32 MiB + 1 byte. No confirmed open P1/P2 remained in the reviewed change; this limited review does not guarantee all defects are absent.

Lint debug/release: **0 errors / 10 warnings** each: `UseKtx` × 5, `ObsoleteSdkInt` × 1, `UsableSpace` × 4. New `UsableSpace` warnings recommend accounting for system-clearable cache through `StorageManager`; current policy conservatively uses already available space without promising to clear other apps' cache. Warnings were not suppressed.

## Signing and distribution

Final APK: **2,767,324 bytes**, SHA-256 `52774d925275daba5ff6a7fd8432743ed6673f20d51065ce3b2cf224595d7592`. Publisher certificate unchanged: `276541bb10833dc482bdf54f4760498633a38316b13a29901b84cfdb3a316478`. Updating over signed 1.1 passed. `apksigner verify` and APK audit checked package/version/SDK, permissions, CRC, ZIP/ELF alignment, and legal assets. INTERNET is absent.

## Android runtime

Dedicated AOSP API 34 x86_64 AVD, fingerprint `Android/sdk_phone64_x86_64/emu64x:14/UE1A.230829.036.A1/11228894:userdebug/test-keys`. Personal Waydroid was not used. Data came from the project's open fixture rather than third-party games.

System/Shizuku 9 GiB APKS runs used signed 1.1.1 APK SHA-256 `17377ef9a32d61325fe560c0348622bbd8900f93e2540881e95513fe11522f81`. After an additional OBB cleanup fix, the final APK identified above passed the full automated suite and repeated Android APK installation with 5 GiB OBB transfer/cancellation. This distinguishes the tested large-APK path from the final cleanup change.

| Check | Evidence |
| --- | --- |
| APK 420,372,498 bytes, 6,008 ZIP entries | Published 1.1 reproduced “ZIP contains more than 4096 entries”; fixed build imported, verified signatures, and installed through System. Android confirmed `installerPackageName=dev.splitbox.app` |
| APKS 9,663,740,480 bytes, 7 APKs | Full input/extraction/signature verification completed; 7/7 APKs selected, totaling 9,663,739,642 bytes. Set restored after updating SplitBox and cold start |
| System: 9 GiB APKS installation | Signed 1.1.1 created a 9,663,739,642-byte session. After confirmation, Android installed `base,payload0…payload5`, with SplitBox as installer. Temporary package cleaned; packages directory fell to 8 KiB of service data |
| Shizuku: 9 GiB installation | Official Shizuku 13.6.0, shell UID 2000, separate UserService/PFD transfer. Real session 1919088055, 9,663,739,642 bytes, completed with Android status=1. All seven APK writes and temporary-set cleanup passed. AVD restarted with 4 GiB RAM for this run |
| Insufficient storage before session | At 7 GiB free, error appeared before backend invocation; source/extracted set (about 18 GiB) remained cached. After freeing storage and cold start, all seven selections restored |
| Real process death at commit | Memory-constrained emulator killed SplitBox with LOW_MEMORY during Android processing. Relaunch restored system confirmation for session 1513211313 without reimport/write/commit |
| Cancellation during extraction | Compressed container with the same 9 GiB was cancelled in ANALYZING after 1,259,544 KiB temporary data appeared. UI returned to IDLE; packages directory fell to 8 KiB |
| 5 GiB OBB: cancellation and export after cold start | Cancelling auxiliary transfer preserved proven APK success; package/OBB restored after process restart. SAF export read all 5,368,709,120 bytes. Full ZIP CRC verification passed; size and CRC `193838c3` matched the original |
| Shizuku: 5 GiB OBB on final APK | Full transfer completed without warning, final size 5,368,709,120 bytes. A later install was cancelled with temp size 20,709,376 bytes: temp removed, old OBB size/inode/mtime unchanged. R8 UI showed the Russian cancellation/package-retained message |
| Settings migration | Old saved default 2 GiB became Auto with an explanation; 9 GiB APKS imported without manually raising the limit |
| 320 dp / 200% font | Narrow backend-status column was found and fixed. Import cancellation reachable after scrolling; READY install action remained reachable |

Main APKS contains base plus six signed feature APKs with 1,536 MiB payloads each. Payload is zero-filled STORED data; every byte was actually written and read. APKS SHA-256: `852d1ffe66737f8c94e7a8e7671b756df5c027ece8a2d1b65f9d00afdbabb08c`. This is functional large-file testing, not measurement of game-installation speed.

## Limits

Physical ARM64, Android 15/16, Magisk/KernelSU, and 16 KiB runtime were not tested. Root protocol, sizes, timeouts, and cancellation semantics have JVM coverage; AOSP `su 0` is not presented as a root-manager test. A blocked SAF provider or third-party verifier can be interrupted between available read/APK-check boundaries, without guaranteed immediate cancellation. Export failure may leave an incomplete ZIP at the user-selected destination.

ZIP64 container support does not override Android restrictions on inner APKs. Index/metadata and available-storage budgets remain. Methodology/generator: [Testing](TESTING.md); decisions/tradeoffs: [Architecture](ARCHITECTURE.md).

<details>
<summary>Archive: published 1.1 and 1.0 verification</summary>

# SplitBox 1.1 verification

Date: **14 October 2026**. Version **1.1**, `versionCode 5`, Android 14+ (minSdk 34 / target SDK 36). Public tag: [v1.1](https://github.com/AnoY606-git/SplitBox/releases/tag/v1.1).

## Automated checks

Full local run: `:core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :fixture:assembleDebug :app:compileBenchmarkKotlin :app:compileProfileGenerationKotlin`, **BUILD SUCCESSFUL**. **203 JVM tests** (core 77, app 126), **12 Python tests**, **0 failures/errors/skipped**. Lint debug/release: **0 errors / 5 existing warnings** each (`UseKtx` × 4, `ObsoleteSdkInt` × 1). No new dependencies or Android permissions.

New-feature checks cover shared staging limits, identical names, ownership/symlinks, cancellation, selection/search, intersected history filters, reports, Long versions, SDK permission declarations, incomplete legacy data, and cancelled inspector requests. Independent review and Android QA found and helped fix three regressions: canonical `/data/user/0` → `/data/data`, valid nonpositive `maxSdkVersion`, and a permission name colliding with a service list key. The first two had failing regression tests; the last used a real APK declaring permission `search`.

## Signed APK

- Size: **2,734,556 bytes** (about 2.61 MiB).
- SHA-256: `9d32b341dc00e32c15a79cd4a21a7dfcd1959e46e2781ded74bb40735fe409e7`.
- Unchanged publisher certificate: SHA-256 `276541bb10833dc482bdf54f4760498633a38316b13a29901b84cfdb3a316478`, `CN=AnoY606-git, O=SplitBox`, RSA 4096, APK Signature Scheme v2.
- `apksigner verify`/APK audit checked package/version/SDK, exact permissions, ZIP CRC, alignment, and legal assets. INTERNET and MANAGE_EXTERNAL_STORAGE absent.

## Android 14 application checks

Dedicated AOSP API 34 x86_64 AVD `splitbox-1-1-api34`, KVM, 2 CPU / 2,048 MiB RAM, 1080×1920 / density 420. Personal Waydroid was not used. Public 1.0 build 4 was initially installed; a real successful System installation and history record were created. Same-certificate update to signed 1.1 preserved data/history. Fixes used an R8 release APK with the permanent certificate. Final crash buffer was empty; Package Manager confirmed versionName 1.1 / versionCode 5.

| Scenario | Result |
| --- | --- |
| Multiple SAF selection | “Choose multiple APKs” selected base/density; full set prepared and installed |
| Multiple APKs from an external app | Base/density with identical displayName and repeated URI produced exactly two APKs without overwrite |
| ClipData only | ACTION_SEND_MULTIPLE without EXTRA_STREAM imported both APKs |
| Cold set recovery | Force-stop preserved both APKs/selection; no single-source SHA-256 claimed for a set |
| Different apps in one set | Rejected during preparation before backend invocation |
| Split search + selected-only | `xxhdpi` showed 1/2 while pinned install counter remained 2/2 |
| System | Real base/density installation confirmed by Package Manager |
| Shizuku | Shizuku 13.6.0.r1086, shell UID, explicit grant: real base/density installation completed and history persisted |
| Package information | Incoming versions 3/2/1 with installed 2 shown as update/same/lower; permission search, SDK 23/maxSdk 32; maxSdk 0/-1 also survived cold restore |
| Boundary permission name | APK declaring `search` displayed alongside search UI without duplicate-key crash |
| History | Real Android cancellation produced a failure; status/method filters, empty result, and reset worked |
| Large font | At 150%, install, inspector scroll/close, and history filters remained available; reset to 100% afterward |
| Report | All 293 preview/copied characters exactly matched when pasted through Android clipboard |

Checks used project-owned small APKs and a separate testOnly sender outside release/source archives. Real `PermissionSnapshot` also passed cold recovery of known declarations; nullable legacy data is covered by JVM models and codec review.

## Limits

Real Magisk/KernelSU, physical ARM, Android 15/16, OEM/work profiles, multi-gigabyte packages, and 16 KiB runtime were not tested in this release. Existing Root protocol checks do not replace a phone root manager. No new performance benchmark was run; earlier numbers below remain historical. Reduced copying follows from the streaming set path without an intermediate ZIP, rather than a measured universal speedup.

Publication CI status is in [Actions](https://github.com/AnoY606-git/SplitBox/actions/workflows/ci.yml). Reproduction: [Testing](TESTING.md); contracts: [Architecture](ARCHITECTURE.md).

<details>
<summary>Archive: SplitBox 1.0 verification</summary>

# SplitBox 1.0 verification

## Build 4: fixes after audit

Date: **14 October 2026**. [v1.0-build4](https://github.com/AnoY606-git/SplitBox/releases/tag/v1.0-build4): versionName **1.0**, versionCode **4**, minSdk 34 / target SDK 36. Original `v1.0` is build 3, documented separately below.

The full local run of `:core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :fixture:assembleDebug` completed **BUILD SUCCESSFUL**. **173 JVM tests** (core 65, app 108), **12 Python tests**, **0 failures/errors/skipped**. Lint debug/release: **0 errors / 5 warnings** each (`UseKtx` × 4, `ObsoleteSdkInt` × 1).

Added 29 regression/boundary tests. Before fixes, new scenarios reproduced six core failures, ten recovery failures, and a separate export-cancellation routing failure. Checks cover common module ABI, resource versionName, actual System session state, atomic READY return, save failure/retry, and current queue/validator results after export success/failure/cancel. They use production helpers and real queues with a controlled coroutine scheduler.

Signed R8 APK: **2,668,988 bytes**, SHA-256 `32d3768435818d03a2144698c86e01451e2697fc089b00d64b4b9ff763ab8ded`. Same publisher certificate SHA-256 `276541bb10833dc482bdf54f4760498633a38316b13a29901b84cfdb3a316478`. Signature/APK audit passed for package/version/SDK, permissions, CRC, alignment, and legal assets. INTERNET absent.

Dedicated AOSP API 34 x86_64 AVD `splitbox-fixes-api34` checked:

- A real unsealed `PackageInstaller.Session`, created by temporary instrumentation with persisted `COMMITTED`: after process termination and fixed debug launch, interrupted result appeared; Android API confirmed session removal/journal release. This injected failure-point state rather than randomly killing a narrow timing window.
- An AAPT2-built APK with resource `versionName`: Android accepted it; SplitBox imported it, retained package/versionCode, and indicated that a resolved display version was unavailable.
- Final debug build 4: System permission denial → Root selection → process termination → launch. Package, selected APK, and ROOT restored without executing a Root command.
- Normal signed update through `adb install -r` from build 3 to 4 passed; Package Manager confirmed `versionCode=4`, `versionName=1.0`.
- Final signed R8 APK: SAF resource-fixture import, permission, System confirmation, and real fixture update passed. Android confirmed `versionName=1.2-resource`, `installerPackageName=dev.splitbox.app`; empty crash buffer.

Export save/validator completion races and cancellation were covered by deterministic coroutine tests; the narrow timing was not reproduced on-device. Temporary instrumentation/fixtures are outside release/source archives. Personal Waydroid was not used. Earlier measurements are not a new build 4 benchmark; real Magisk/KernelSU, physical ARM, and Android 15/16 were not retested.

<details>
<summary>Archive: published build 3 and earlier measurements</summary>

Date: **14 October 2026**. Package `dev.splitbox.app`, versionName **1.0**, versionCode **3**, minSdk 34 / target SDK 36. Author: [AnoY606-git](https://github.com/AnoY606-git).

## Automated checks

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :fixture:assembleDebug
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check_repository.py
```

Final run: **BUILD SUCCESSFUL**, **144 JVM tests** (core 50, app 94), **0 failures/errors/skipped**, **12 Python tests**. Lint debug/release: **0 errors / 5 warnings** each, `UseKtx` × 4 / `ObsoleteSdkInt` × 1, unsuppressed.

JVM checks cover archives, metadata/targeting, project Android fixtures, PM protocol/ambiguous commit responses, timeout, OBB, cancellation session ownership, System correlation/ACK, save queues, selection generations, cache index, and privileged-result recovery. Python checks cover source inventory, excluded keys/caches, symlinks, versions, and source-only packaging.

## APK

Public R8 `SplitBox-1.0.apk` used the permanent publisher key, **2,668,988 bytes**. JVM tests, debug/release lint, and build were repeated with this signing configuration before publication.

- APK SHA-256: `8d33d38cc7b91cdf16f0680d5dedd7c1f2ffb8a0ff59583635bd56be876aa9d1`.
- Certificate SHA-256: `276541bb10833dc482bdf54f4760498633a38316b13a29901b84cfdb3a316478`; `CN=AnoY606-git, O=SplitBox`, RSA 4096, APK Signature Scheme v2.
- `apksigner verify` and APK audit passed: applicationId/versions/SDK, exact three permissions, ZIP CRC, ZIP/ELF 16 KiB alignment, legal assets.
- INTERNET and MANAGE_EXTERNAL_STORAGE absent. No synthetic benchmark Activity in release; normal release without credentials remained unsigned.
- Signing key, SDK/JDK, caches, and build outputs excluded from sources. Future public updates require the permanent certificate. Earlier `-dev.apk` used a different signature and cannot receive a normal update from this APK.

## Android 14 functional checks

Dedicated AOSP API 34 x86_64 AVD `splitbox-roadmap-api34`, KVM, 2 CPU / 2,048 MiB RAM, 1080×1920 / density 420. Main failure/recovery scenarios used production debug `dev.splitbox.app`; benchmarks used a separate package. Personal Waydroid was untouched.

Public APK `8d33d38c…` separately passed installation, first launch, raw APK SAF import, permission, System confirmation, and real `dev.splitbox.fixture` installation on clean AOSP API 34 x86_64 AVD `splitbox-publisher-api34`. Package Manager confirmed the fixture; no `AndroidRuntime FATAL EXCEPTION`.

A preliminary development-certificate R8 APK (`9782b4a2…`) also updated same-certificate debug, then passed raw SAF import, normal cleanup 11.3 KB → 0 B → IDLE, and real Shizuku XAPK installation with matching OBB bytes. The benchmark APK separately passed manual cleanup after MainActivity recreation through NEW_TASK/CLEAR_TASK with unchanged PID. This was not performance measurement.

| Scenario | Actual result |
| --- | --- |
| Raw APK through SAF | Project `application.apk` imported; digest matched independent SHA-256; private `source.bin` reused without a second payload copy |
| System permission/install | Android Settings roundtrip, PackageInstaller confirmation, successful fixture installation |
| Process death in System CONFIRMATION | Cold launch opened persisted confirmation; source remained available |
| Process death during permission request | After grant, cold MAIN awaited current selection validation and opened one confirmation; cancellation produced known failure |
| Manual selection write failure | In a test 10-split APKS, a directory replacing `checkpoint.tmp` caused a real write error: new selection visible, install blocked, last confirmed checkpoint retained |
| Save retry | After fixing the write failure, exact selection saved/restored on cold launch; tested with two and three selected APKs |
| Tab state | Actual `roadmapqa` search text survived History → Settings → History exactly |
| 200% font | Pinned retry action reachable without scrolling, unobstructed by navigation; save retry/cold restore passed |
| Journal failure before Android handoff | Read-only test SharedPreferences directory caused visible error, no handoff/crash; restoring writes and retry persisted READY/exact selection |
| History clear failure | Same write failure left the error visible and data intact; process continued |
| Shizuku XAPK/OBB | Shizuku 13.6.0.r1086, shell UID/UserService; real fixture install succeeded and `Android/obb/dev.splitbox.fixture` bytes matched |
| Shared Root/Shizuku journal cold recovery | Valid private records with ROOT backend in CREATING, WRITING, COMMITTING, UNKNOWN, COMMITTED, and FINISHED success/failure: all seven cases produced expected UI and one history entry per UUID |

Phase testing injected persisted records rather than killing real Magisk in each phase. CREATING/WRITING/COMMITTING/UNKNOWN retained `success=null`; UI claimed neither success nor failure, and recovery started no new UserService. UNKNOWN help required explicit action rather than opening on every onResume. Controlled filesystem write failures do not replace a physical-device ENOSPC test.

The 10-split container used all APKs from the project's bundletool fixture without `toc.pb`; this tests manual selection/persistence, not a representative large commercial package. Compatible density splits were checked; incompatible languages remained locked.

## Measurements

`BaselineProfileRule.productionJourneys` passed on this AVD using real startup/settings/history/import, without synthetic Activity. Fifteen iterations produced 46,487 rules, including 1,072 lines containing SplitBox classes (5,486,994 bytes). Final logcat still reported `Unstable profiles`; default `strictStability=false` permits success without stabilization. This was not called a stable profile. The custom profile **was not integrated into release 1.0**; production contained normal dependency profiles. Generator behavior: [BaselineProfileRule API](https://developer.android.com/reference/kotlin/androidx/benchmark/macro/junit4/BaselineProfileRule).

Five independent `CompilationMode.None()` scenarios passed three iterations each on that AVD. Target APK SHA-256: `47cdc6176de284de58d84152b30f0f8fb759fccf851ec1e54b615228054cd657`, R8, profileable/testOnly, no custom profile. Font scale 1, portrait, SwiftShader, CPU frequency not fixed. EMULATOR explicitly suppressed; no other AndroidX checks suppressed.

| Scenario | Emulator reference values |
| --- | --- |
| Cold startup | TTID median 857.12 ms, min 822.88 / max 947.58 ms |
| Settings → history → install | FrameDurationCpuMs P50 19.80 / P95 77.31 ms |
| 50-record list | FrameDurationCpuMs P50 9.14 / P95 26.30 ms |
| 500-record list | FrameDurationCpuMs P50 9.26 / P95 20.86 ms |
| 4,096-record list | FrameDurationCpuMs P50 9.14 / P95 22.71 ms |

Frame percentiles come from AndroidX `sampledMetrics` across frames in three iterations, not whole-operation durations. Three launches do not establish reliable startup p95, so min/median/max are given. Lists use synthetic metadata/local Compose state; these numbers do not measure parsing/persistence of thousands of real APKs.

Cold import additionally passed three iterations each in None and `Partial(BaselineProfileMode.Require, warmupIterations=0)` on the same target hash. Both JSON files had `completed=true`, three successful observations, and empty `failures`. APK was not rebuilt between series. Partial also passed three cold starts: TTID min 815.58 / median 815.86 / max 824.06 ms. Require accepted the packaged dependency profile; no custom SplitBox rules were added.

| Import: URI dispatch → observed READY | None, ms | Partial, ms |
| --- | --- | --- |
| Iteration 0: empty data | 2384.80 | 2448.25 |
| Iteration 1: previous READY persisted | 2434.15 | 2393.78 |
| Iteration 2: previous READY persisted | 2399.01 | 2662.13 |

Source bytes were identical, fixture SHA-256 `648f5089d3ecd1ec9815acc0a0d3ae116c45cb3f60c5ae5edcfa9fe5bf4e924e`. Names followed `application-{iteration}.apk` so old READY could not satisfy new-import readiness. Read grant was refreshed after cold force-stop. Timer includes dispatch/startup, import, and UIAutomator observation overhead; it is not parser CPU time. Cache was not cleared between iterations: three private working directories actually remained after None. Normal cleanup was checked separately. This mixes first import and repeat replacements rather than universal import p95. Short emulator series establish neither Partial speedups nor phone improvements. Method, datasets, and commands: [Benchmarks](BENCHMARKING.md).

## Limits

Real Magisk/KernelSU installation, physical ARM, Android 15/16 runtime, OEM/work profiles, multi-gigabyte stress, and 16 KiB runtime were not tested in build 3. Static APK/ELF 16 KiB alignment does not prove runtime on that kernel. At 200% font, visual inspection was performed; speech/full TalkBack device flow was not. Stage/heading semantics were inspected in code.

Each GitHub run's status is in [Actions](https://github.com/AnoY606-git/SplitBox/actions/workflows/ci.yml). Local checks and emulator numbers do not replace real-device tests. [Testing](TESTING.md) describes reproduction; [Architecture](ARCHITECTURE.md) describes persistence/recovery contracts and UNKNOWN without automatic recommit.

</details>

</details>

</details>
