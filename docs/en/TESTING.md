**English** · [Русский](../TESTING.md) · [Developer guide](README.md)

# Testing

## Automated checks

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :fixture:assembleDebug
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check_repository.py --apk app/build/outputs/apk/release/app-release-unsigned.apk
```

With the local bootstrap, replace `./gradlew` with `bash scripts/gradle.sh`. Run Gradle sequentially. Reports are generated under each module's `build/reports` and `build/test-results` and are not tracked by Git. Recorded release results are in [Verification](VERIFICATION.md); they are distinct from checks on subsequent unreleased source changes.

| Suite | Coverage |
| --- | --- |
| `core:test` | ZIP traversal/duplicates/CRC/limits, AXML, targeting/required splits, real Android fixtures; raw source reuse only when owned by the operation |
| `app:testDebugUnitTest` | Streaming SHA/byte limit/cancel, cache index, save revisions and stale validation, PM protocol/timeout/OBB, System recovery/late callbacks/ACK, Root/Shizuku UUID/phases, uncertain outcomes and session ownership |
| Python release tools | Source inventory, fixtures, exclusion of keys/caches, symlinks, credential patterns, repeatable ZIP, versionCode, version 1.0 packaging regression, and no stale APK in the manifest |
| `app:lintDebug` | Android/Compose/resource checks; warnings are assessed separately from errors |
| `check_repository.py --apk` | Package/version/SDK/permissions, legal assets, CRC, ZIP/ELF 16 KiB alignment |

## Regression contracts

Test the side-effect boundary as well as the final value. Confirmation of an old revision must not hide a newer failure; invalidation waits for an already running write before deleting its directory; `flush()` cannot skip an unsaved selection. A slow cache scan must not delay recovery. A stale CPU validation completion cannot enable installation for a new set.

For System, persist WRITING before APK writes, commit state before `session.commit`, and permission/confirmation before Android handoff. The receiver must await durable storage. Retain exact current/retained session correlation checks and ACK after attempting to open UI. For Root/Shizuku, check cancellation after create but before ID return, commit timeout/transport loss, every persisted phase, corrupted records, and rejection of clearing another UUID. Repeated recovery must not execute shell or commit; UNKNOWN remains `success=null` in history. Explicit new import archives the previous UNKNOWN when `saveHistory` is enabled, without duplicate UUIDs.

## Project-owned fixtures

Five small files in `core/src/test/resources/fixtures/` contain an APK, bundletool APKS, and flat APKS/APKM/XAPK. Their source is module `fixture`, package `dev.splitbox.fixture`, version 1.0. This is an open test app without user data, with roughly 128 KiB of containers in total. Core integration tests require these files.

```bash
python3 scripts/generate_fixtures.py
./gradlew :core:test
```

The Linux helper uses a pinned bundletool and the local Android debug key. Public certificates within fixtures are allowed; the private key is not published. Generation can change binary hashes because of signing. To check without overwriting reference files, use `--output-dir` with a separate local directory.

## Large-package regression

`LargeArchiveTest` uses a real binary AndroidManifest from the project fixture: an APK with 6,000 resources, ZIP64 with 65,536 entries, corrupted EOCD/ZIP64 extra/local headers, traversal/duplicates, wrong CRC, metadata/extraction/APK-count limits, and cancellation with partial-output cleanup. A sparse file with an entry of 8 GiB + 123 bytes tests 64-bit addressing but **does not replace fully reading 8 GiB of data**. App tests check 9 GiB sizes in the PM protocol, timeout calculation, storage reserve, settings migration, progress throttling, cancellation, and retention of a package with OBB.

A generator supports real I/O testing. It uses the project's fixture, AAPT2/zipalign/apksigner from build-tools 36.0.0, and the Android debug key. It installs no apps and refuses to overwrite an existing directory. Allow roughly 25 GiB free host storage; keep outputs on disk rather than RAM/tmpfs.

```bash
python3 scripts/generate_large_fixture.py \
  --output-dir .toolchain/large-fixtures \
  --key .toolchain/android-user/debug.keystore \
  --compressed-bundle --obb-gib 5
```

Defaults create an APK with 6,000 extra entries and a 400 MiB payload, six feature APKs with 1,536 MiB payloads each, and `large.apks` slightly over 9 GiB. The outer container is ZIP64; each inner APK is ordinary ZIP with a verified debug signature. Optional outputs include `compressed.apks` with the same APKs and XAPK with 5 GiB OBB for cancellation, recovery, and ZIP64 export tests. `dataset.json` records sizes, ZIP-entry counts, total extracted bytes, and SHA-256. Payload consists of zero bytes in STORED entries: all bytes pass through writing/reading/CRC/signing. This is synthetic load, not a game with real asset packs. ZIP64 container support does not override Android's own APK restrictions.

On a dedicated Android 14+ device with sufficient space, check:

1. Import and System-install `many-entries.apk`; baseline 1.1 should reproduce the 4,096-entry failure.
2. Import all seven parts of `large.apks`; verify actual installed splits and `installerPackageName`, separately for System and Shizuku.
3. Cancel copying and extraction; no temporary multi-gigabyte directory should remain once cancellation completes.
4. Insufficient space for the extra Android session: a clear error before writing; prepared package can be retried after freeing storage.
5. System-install XAPK with OBB, restart the process, and verify export remains available. Root/Shizuku OBB cancellation after commit preserves APK success. Cancelling after a partial temporary file appears removes only the operation's temporary file; existing OBB remains unchanged. Fully verify exports by reading all ZIP entries with CRC, including an entry larger than 4 GiB.
6. Controls at 320 dp with 200% font: cancellation remains reachable after scrolling and counters refer to the current operation.

## Device scenarios

| Scenario | Expected behavior |
| --- | --- |
| SAF/VIEW/SEND/SEND_MULTIPLE, known and unknown size | Source is read with a byte limit; cancellation leaves unrelated files alone |
| Multiple raw APKs through SAF and SEND_MULTIPLE | One package/version, one base; shared byte limit, no temporary ZIP; identical display names cannot overwrite files |
| Set containing another package/version, duplicate split, or nested archive | Clear error before backend invocation; only the operation's working directory is cleaned |
| Cold restore without source.bin | Every staged APK and manual selection survives; permission roundtrip and install use persisted base |
| Split search and selected-only | Filters do not change selection/total size; a new package resets them |
| History: search + status + backend | UNKNOWN is neither failure nor success; reset restores the full list; copy report uses only the previewed record |
| Inspector: newer/equal/lower version, hidden package | Compare Long versionCode; unavailable information is not reported as proof that no app is installed |
| Permissions: different selected splits, old checkpoint | Combine only selected APK declarations; incompleteness is explicit; declarations are not described as granted permissions |
| Inspector closed or package replaced before response | Old results cannot overwrite new ones; PackageManager access does not block main |
| System permission roundtrip / denial | Returning from settings does not duplicate installation; denial is explained |
| Root grant/deny/revoke | Use a real root manager; never abandon another session or hide errors |
| Shizuku permission/UserService/Binder loss | Explicit service start and access grant; connection loss yields the correct outcome |
| Process death between persisted COMMITTED and System commit | Unsealed session is abandoned; interrupted result appears; next operation is available |
| Process death after System commit | Recovery does not abandon a sealed session; confirmed result or UNKNOWN, without another commit |
| System permission denied → switch backend or cancel → cold restore | READY and exact selection survive; no duplicate prompt/install |
| Process death in each Root/Shizuku phase | Proven persisted outcome or neutral UNKNOWN; no automatic su/commit or duplicate history |
| Fast toggles, ENOSPC, save retry | Failure remains visible; last confirmed selection restores; install waits for persistence and current validation |
| New import after UNKNOWN | With history enabled, previous operation is archived; save failure remains visible |
| Rotation, tabs, large font, TalkBack | Main actions remain accessible and state meaning is retained |
| ENOSPC, deleted cache, corrupt archive | No false success; choosing a new file remains possible |
| Selection validation/save finishes during OBB export | Pending/error flags are current after ZIP success, failure, or cancellation; no endless waiting |
| OBB export from UNKNOWN and cancellation | UNKNOWN and journal survive; only export is cancelled, not the installation session |
| OBB export/copy failure | Verify package ownership; auxiliary transfer failure does not mask APK success |
| ARM64/ARM32 base + ARM32 feature on a dual-architecture device | Automatic selection uses common ARM32; disjoint sets are rejected |
| versionName references a string resource | Import succeeds; numeric version and package name are retained |

The matrix requires API 34/35/36, physical ARM64, real Magisk/KernelSU, and Shizuku. Static 16 KiB alignment does not replace testing on a 16 KiB runtime. AOSP `su 0` does not establish compatibility with Magisk `su -c`. Explicitly mark untested scenarios.

Target every ADB action at an explicit serial on a dedicated test device. Verify identity first; the serial alone does not establish that it is the intended instance:

```bash
adb -s DEVICE_SERIAL get-state
adb -s DEVICE_SERIAL shell getprop ro.product.model
adb -s DEVICE_SERIAL shell getprop ro.build.fingerprint
adb -s DEVICE_SERIAL shell getprop ro.product.cpu.abi
adb -s DEVICE_SERIAL shell getprop ro.build.version.sdk
```

Compare model/fingerprint/ABI/API to the dedicated device before installation, `pm clear`, or process termination. Do not pick the first connected device or use a working personal Android container. With a different ADB server port, pass the same `-P PORT` in every command.

The emulator-only helper requires `--serial` or `SPLITBOX_TEST_SERIAL` and also checks `ro.kernel.qemu=1`:

```bash
python3 scripts/device_qa.py --serial emulator-5554 snapshot home
python3 scripts/device_qa.py --serial emulator-5554 tap 'Choose file'
```

Use the label matching the emulator's current interface language. ADB executable/port: `SPLITBOX_ADB`, `--adb-port`/`SPLITBOX_ADB_PORT`; default port is 5037. Screenshots/XML remain in an ignored local directory.

## Performance and Baseline Profile

The separate `:benchmark` module and its [guide](BENCHMARKING.md) define dependency versions, scenarios, compilation mode, iterations, and original JSON/Perfetto paths. Target variants `benchmark` and `profileGeneration` use suffixed ID `dev.splitbox.app.benchmark`, `testOnly=true`, and a profileable manifest. They replace each other, but not the normal app. Release and target do not request INTERNET; the separate runner APK receives INTERNET for localhost Perfetto from AndroidX Benchmark.

After checking identity, explicitly constrain Gradle to the same serial:

```bash
bash scripts/gradle.sh :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL -Psplitbox.benchmarkIterations=5
bash scripts/gradle.sh :benchmark:connectedProfileGenerationAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL
```

Scenarios call `pm clear` only for the test target package and require a dedicated device. Macrobenchmark rejects emulators by default. Only functional smoke runs may explicitly use `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`; record it and do not suppress other errors.

`importApk` uses the project's real APK and ACTION_VIEW/FileProvider through production copy/parser/signatures/SHA. `splitList50/500/4096` insert synthetic metadata into the actual `InstallScreen` and measure list/filter/scrolling plus local Compose state in a test Activity. They do not create hundreds of APKs or test parsing, production checkpoint/validation, or backend. `StartupTimingMetric` measures the Activity's first frame, not URI → READY latency or copy throughput. BaselineProfileRule uses real startup/navigation/import journeys without synthetic lists; generated output needs separate review and integration into release.

A separate `splitbox-import-observed-ready-{none,partial}-TIMESTAMP.json` records the full observed import path. ACTION_VIEW dispatch to enabled install includes Activity startup, file handling, save/validation, rendering, and UIAutomator overhead. Successful samples are separate from failures; incomplete runs have `completed=false`. This is runner observation, not pure parsing/copy time. [Benchmarks](BENCHMARKING.md) documents format and location.

For comparisons, record device, ROM/fingerprint, ABI/API, power, orientation, font scale, compilation mode, iterations, commit, and dataset SHA-256. Keep original traces/JSON/logcat. p50/p95, peak staging, bytes read/write, and cancellation latency require corresponding separate measurements. SHA/byte-count unit tests establish correctness and copied volume, not phone speedups.

Emulators support functional scenarios and profile generation; their timings are not physical ARM performance. A benchmark module does not establish speedups, Magisk/KernelSU support, or 16 KiB runtime behavior. Record available devices and untested scenarios in [Verification](VERIFICATION.md). This document describes methodology, not completed-run results.

## Localization and access (1.2)

Before release, run `MessageEnvelopeTest`, `LocalizationResourcesTest`, `HistoryPresentationTest`, and `AccessCoordinatorTest` with the full JVM suite. Check matching EN/RU keys and format arguments, nested/native messages, malformed envelopes, and size limits. Access checks must cover no System prompts, deduplication, denial and explicit retry, resuming after an active operation, Shizuku death/replacement, old callbacks, and old Root results.

On a disposable project-owned AVD, exercise EN/RU/System at 320 dp with fontScale 1/2. Capture UI hierarchy/screenshots through `device_qa.py`; verify file selection, tabs, language selection, and access controls are reachable. Test changing language through system settings and in-app settings, cold start, prepared-package recovery, localized report copying, and updates over the previous APK. Language changes must neither alter `LocaleManager.systemLocales` nor repeat installation. Real Shizuku is needed for permission request/denial/grant and service-loss checks; AOSP `su` does not establish Magisk/KernelSU behavior.

The language/size matrix and safe external-settings navigation were informed by the author's open [Open-TimeManager UI matrix](https://github.com/AnoY606-git/Open-TimeManager/blob/main/app/src/androidTest/kotlin/dev/opentimemanager/app/ui/AdaptiveLocalizedUiTest.kt) and [PlatformIntegration](https://github.com/AnoY606-git/Open-TimeManager/blob/main/app/src/main/kotlin/dev/opentimemanager/app/ui/PlatformIntegration.kt). Code was not copied; SplitBox changes are tested against its own scenarios.

## Settings presentation after 1.2 (unreleased)

The source reorganizes the same settings into Installation, App, and Files and data. Verify inline Root/Shizuku states and retry actions, and the current-value rows/radio dialogs for language, theme, and package-size limit. At 320 dp and 200% font, all options and dialog actions must remain reachable. Check that active-operation restrictions also apply to an already open package-size dialog. These are required checks for the unreleased presentation change; historical 1.2 results do not certify this UI.
