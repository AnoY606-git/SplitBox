<!-- Copyright (C) 2026 AnoY606-git and SplitBox contributors
     SPDX-License-Identifier: GPL-3.0-only -->

**English** · [Русский](../../benchmark/README.md) · [Developer guide](README.md)

# SplitBox benchmarks

Module `:benchmark` uses `com.android.test` and official `androidx.benchmark:benchmark-macro-junit4:1.4.1`, including `BaselineProfileRule`. Binary fixtures belong to the project: the test APK reads assets from `core/src/test/resources`, without a second copy in the source tree. AndroidX Benchmark, UIAutomator, and AndroidX Test use Apache-2.0 and are test tools outside the production APK. Dependency versions are in `benchmark/build.gradle.kts`.

Both test app variants use ID `dev.splitbox.app.benchmark`, a local Android debug signature, `debuggable=false`, `testOnly=true`, and a separate profileable manifest. They replace each other on installation and can coexist with ordinary `dev.splitbox.app`. The release manifest is unchanged. `profileinstaller:1.4.1` is explicitly included only for these variants; ordinary release receives ProfileInstaller 1.4.0 transitively. The library's presence does not prove that custom SplitBox rules are in the APK profile. Target APK and release do not request INTERNET. The separate test runner receives INTERNET transitively from AndroidX Benchmark for Perfetto's localhost connection.

| Variant | App | Tests |
| --- | --- | --- |
| `benchmark` | R8/shrinkResources as in release; extra Activity only for UI datasets | `SplitBoxMacrobenchmark` |
| `profileGeneration` | No R8/shrinkResources; class names suitable for profile generation; no synthetic Activity | `SplitBoxBaselineProfile` |

AGP 9.4.1 uses built-in Kotlin support for Android. The previous explicit `*TestedApks` attribute workaround has been removed; both app target variants and their test APKs assemble without it. Successful assembly does not replace connected test execution or establish a measured speedup.

## Scenarios and metrics

Before each test, only the test package's data is cleared with `pm clear`, **before** `measureRepeated` and AndroidX compilation. No shell clear runs after compilation because it could delete ART profiles. `importApk` performs repeated cold imports: the first starts with empty data; later iterations replace previous READY through ordinary `importUri`. Previous READY and cache remain before these iterations; cache is not reset, and deletion of previous directories is not a scenario precondition. Initial state differs between first and later iterations. Settings cache cleanup is outside this scenario.

Use a dedicated device: these tests intentionally delete the test package's settings, journal, history, and temporary files. Scenarios do not initiate app installation.

| Test | Data and action | Measurement |
| --- | --- | --- |
| `coldStart` | Empty data, cold MainActivity start | `StartupTimingMetric` |
| `settingsHistoryBack` | Home → Settings → empty History → Install | `FrameTimingMetric` |
| `importApk` | Identical `application.apk` bytes named `application-ITER.apk` through FileProvider and real ACTION_VIEW; wait for the exact new name, ready one-APK selection, and enabled install | `StartupTimingMetric`, `FrameTimingMetric`, separate `uriDispatchToObservedReady` JSON |
| `splitList50`, `splitList500`, `splitList4096` | 50/500/4,096 APK records including base; selected-only filter, six downward scrolls, deselect one row, three upward scrolls | `FrameTimingMetric` |

Settings/history use the actual bottom navigation. Returning to Install uses that navigation too; system Back currently finishes the Activity.

Import includes real copy/parser/signatures/SHA with default settings. The file and initial URI grant are prepared in `setupBlock`. AndroidX `StartupMode.COLD` force-stops the app afterward, revoking the grant. The runner therefore re-grants read access from the FileProvider owner's UID at the beginning of the measured block, after force-stop but before starting the observed timer and Activity. This short call is inside Perfetto capture; fixture copying is outside the measured block. Readiness first waits for the exact new filename, then enabled install and one-APK summary. Previous READY and errors do not count as new import. StartupTimingMetric measures the Activity's first frame rather than total import time. Frame timings do not measure parsing latency or copy throughput either.

The additional artifact `splitbox-import-observed-ready-{none,partial}-TIMESTAMP.json` records `elapsedRealtimeNanos()` from `startActivityAndWait(ACTION_VIEW)` until the new filename, enabled install action, and one-selected-APK summary are observed. This is complete wall-clock runner observation: command dispatch, startup, URI processing, copy/parser/signatures/SHA, save/validation, rendering, and UIAutomator/IPC/wait overhead are included. It is neither parser CPU time, pure copy throughput, nor `timeToFullDisplayMs`. Re-granting occurs before this timer; fixture copying and JSON writing occur outside the measured block. Ordinary replacement of previous READY is included in repeat-import measurements.

`samples` contains only successful `{iteration, durationNs}` observations; errors go into `failures` without time-to-READY. `completed=false` marks an unfinished test, which is excluded from comparison. JSON also includes compilation, expectedIterations, source `fixtures/application.apk` SHA-256, filename pattern `application-{iteration}.apk` with zero-based iterations, initial state `empty` then `prior-ready-persisted`, `cacheResetBetweenIterations=false`, fingerprint/ABI/API/model/fontScale, and explicit observation overhead. Names change while bytes and SHA-256 remain identical. The file is written after `measureRepeated`, including failures, into `additionalTestOutputDir` or the runner APK's external media directory and reported to the host through instrumentation `additionalTestOutputFile_*`. Byte counts and SHA correctness need corresponding unit tests. Analyze first import separately from later READY replacements: a mixed aggregate is not a universal import p95 or proof of physical-phone speedups.

Large lists inject only synthetic metadata into production `InstallScreen`, without creating APKs, traversing the parser, or changing ViewModel. Deselecting updates Compose state in the test Activity, so it neither measures production checkpoint/validation nor integration-tests selection. The installation callback throws before backend access. These three results characterize UI, not archive handling with that many APKs.

Each Macrobenchmark defaults to five iterations with `CompilationMode.None()`, without ahead-of-time compilation. Choose `-Psplitbox.benchmarkCompilation=None` (default) or `Partial`. `Partial` means `CompilationMode.Partial(BaselineProfileMode.Require, warmupIterations=0)`: AndroidX must install the target APK's packaged profile successfully, with no fallback to None. Other values are rejected. Require alone does not prove custom app rules exist because dependencies can contain baseline profiles. Before comparing, inspect a profile generated from actual production journeys and verify its inclusion in the target APK.

Set a positive iteration count with `-Psplitbox.benchmarkIterations=10`. Compare None/Partial using **the same built APK** with an integrated generated profile, the same device, Android, orientation, font scale, power mode, iteration count, and data, including the same sequence of first empty import and later READY replacements. For first-draw investigations, account for journal recovery separately: `coldStart` has empty data while later `importApk` iterations retain previous READY.

## Running

Use JDK 17, SDK/build-tools 36, and an ADB device running API 34+. Emulators can check scenarios and generate profiles, but their numbers must not be presented as physical-device results. Do not use a personal working Android container. Build through the project wrapper:

```sh
bash scripts/gradle.sh :app:assembleBenchmark :benchmark:assembleBenchmark \
  :app:assembleProfileGeneration :benchmark:assembleProfileGeneration
```

On a dedicated device, with Gradle explicitly constrained to its serial:

```sh
bash scripts/gradle.sh :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL -Psplitbox.benchmarkIterations=5
bash scripts/gradle.sh :benchmark:connectedProfileGenerationAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL
```

Macrobenchmark rejects emulators by default. Only functional smoke runs may explicitly add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`. Record that in the report; do not suppress other errors or publish it as speedup evidence. To select a specific test, use, for example, `-Pandroid.testInstrumentationRunnerArguments.class=dev.splitbox.benchmark.SplitBoxMacrobenchmark#coldStart`. Manual target installation requires `adb install -t -r`.

`BaselineProfileRule` generates a profile from actual startup/navigation/import journeys, without the synthetic dataset. The repository has no manually invented method list or profile. Inspect output rules and integrate them into `app/src/main/baseline-prof.txt` as a separate change after a successful device run. Do not copy a source profile from the minified benchmark. Integration requires a separate release build and compilation-mode comparison; Macrobenchmark supports both modes. Build the target once with the reviewed profile, retain its SHA-256, and run both series:

```sh
bash scripts/gradle.sh :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL -Psplitbox.benchmarkCompilation=None
bash scripts/gradle.sh :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL -Psplitbox.benchmarkCompilation=Partial
```

Save each run separately: AGP may replace the connected report with the next series. Do not rebuild the target from different sources between runs; compare APK SHA-256. Emulator numbers describe that AVD, not a physical phone's speedup.

APKs are in `app/build/outputs/apk/{benchmark,profileGeneration}/` and `benchmark/build/outputs/apk/{benchmark,profileGeneration}/`. Connected reports: `benchmark/build/reports/androidTests/connected/`; XML: `benchmark/build/outputs/androidTest-results/connected/`. AndroidX reports JSON, Perfetto trace, and `*-baseline-prof.txt` paths in instrumentation output; AGP collects additional output under `benchmark/build/outputs/connected_android_test_additional_output/`. Keep original artifacts/logcat, model/ABI/API, fingerprint, font scale, iterations, compilation mode, commit, dataset SHA-256, and EMULATOR suppression details. Mark absent physical ARM/Magisk/16 KiB measurements as untested.

Official references: [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview), [creating Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile), [profile-generation limitations](https://developer.android.com/topic/performance/baselineprofiles/overview), [AndroidX Benchmark 1.4.1](https://developer.android.com/jetpack/androidx/releases/benchmark#1.4.1), [comparing compilation modes](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile), and [metric definitions](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics).
