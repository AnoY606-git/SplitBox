**English** · [Русский](../BUILDING.md) · [Developer guide](README.md)

# Building

## Tools

| Tool | Version |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| Android SDK | platform 36, build-tools 36.0.0, platform-tools |
| Python | 3 for release scripts; 3.12+ for the Linux bootstrap |

The app uses minSdk 34 / Android 14+ and compile/target SDK 36. `gradle.properties` is the single source of `versionName`/`versionCode`: currently `1.2` / `7`.

## With JDK and SDK already installed

Open the project root in Android Studio, or set `JAVA_HOME` and `ANDROID_HOME`. A local `local.properties` containing `sdk.dir` can replace the SDK environment variable.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :fixture:assembleDebug
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check_repository.py
```

On Windows, use `gradlew.bat`. The first build downloads Gradle and Maven dependencies; the app does not use the internet at runtime. Gradle Wrapper is included in the source, and its JAR and distribution checksum are checked.

## Local bootstrap for Linux

For Linux x86_64 with Python 3.12+ and curl:

```bash
python3 scripts/bootstrap.py --accept-licenses
bash scripts/gradle.sh :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

JDK and Android command-line tools are pinned by URL/SHA-256 in `gradle/toolchain.json`; SDK packages are installed through sdkmanager. Without `--accept-licenses`, developer SDK licenses are accepted interactively. Bootstrap writes to `.toolchain/`, leaves global tools alone, and preserves an existing `local.properties`.

`scripts/gradle.sh` runs the Wrapper. JDK precedence is `SPLITBOX_JAVA_HOME` → `JAVA_HOME` → local JDK → PATH. SDK precedence is `SPLITBOX_ANDROID_HOME` → standard SDK variables → `local.properties` → local SDK. Existing `ANDROID_USER_HOME`/`GRADLE_USER_HOME` values are retained; otherwise local directories under `.toolchain/` are used.

## Outputs

| Build | File |
| --- | --- |
| Debug, automatic Android debug key | `app/build/outputs/apk/debug/app-debug.apk` |
| Release without signing variables, R8/shrink | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Release with complete publisher credentials | `app/build/outputs/apk/release/app-release.apk` |

An unsigned APK must be signed before installation. See [Releasing](RELEASING.md) for the permanent key, development artifacts, and packaging.

## Measurement variants

Module `:benchmark` uses AndroidX Macrobenchmark/BaselineProfileRule 1.4.1. Build it with:

```bash
bash scripts/gradle.sh :app:assembleBenchmark :benchmark:assembleBenchmark \
  :app:assembleProfileGeneration :benchmark:assembleProfileGeneration
```

Both target variants use application ID `dev.splitbox.app.benchmark`, `testOnly=true`, `debuggable=false`, a profileable manifest, and a local debug signature. They can coexist with `dev.splitbox.app`, but replace each other. `benchmark` enables R8/shrink and contains a separate Activity for synthetic lists; `profileGeneration` preserves class names and excludes that Activity. Both explicitly use ProfileInstaller 1.4.1; ordinary release receives ProfileInstaller 1.4.0 transitively. This library's presence does not establish that custom SplitBox rules are included in the APK's profile.

Release and target APKs do not request INTERNET. The separate test runner APK receives INTERNET from AndroidX Benchmark for Perfetto's localhost connection; this permission is not added to the shipped app. Manual target installation requires `adb install -t -r`.

Run connected tests only on a dedicated device with explicit `-Pandroid.injected.device.serial=DEVICE_SERIAL`. Verify the device's identity first: scenarios clear the test package's data. Full commands, datasets, reports, and interpretation rules are in [Benchmarks](BENCHMARKING.md) and [Testing](TESTING.md). A Baseline Profile is not integrated into release merely because the module exists.

## Troubleshooting

- JDK/Unsupported class version: check `java -version` and JDK 17 in Android Studio's Gradle settings.
- SDK location not found: check environment variables/`local.properties`; that file is not tracked by Git.
- Licenses not accepted: run sdkmanager licenses, or bootstrap with explicit SDK license acceptance.
- Missing keystore: remove partially configured SPLITBOX signing variables for an unsigned build, or supply all four according to the release guide.
- Missing binary fixtures: restore `core/src/test/resources/fixtures/` from the sources. A blanket `*.apk` Git exclusion hides files needed by tests.

Do not run two Gradle builds concurrently in one checkout.
