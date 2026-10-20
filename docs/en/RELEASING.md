**English** · [Русский](../RELEASING.md) · [Developer guide](README.md)

# Releasing

## Version and checks

`gradle.properties` is the single source of the version. The current app uses `versionName 1.2.1`, `versionCode 8`. Increase `versionCode` for every released update. `versionName` supports `major.minor`, `major.minor.patch`, and prerelease suffixes. Update CHANGELOG and run the command in [Testing](TESTING.md) from a clean checkout.

CI (`.github/workflows/ci.yml`) runs source checks, Python/JVM tests, lint, debug/unsigned release and fixture builds, checks APK policy, and retains results for 14 days. Actions are pinned to commit SHAs, permissions are limited to `contents:read`, and checkout does not persist credentials. CI does not publish a Release or receive production signing secrets.

## The shipped application

Publish the ordinary `release` with ID `dev.splitbox.app`. `benchmark`/`profileGeneration` are separate `testOnly` profileable variants with ID `dev.splitbox.app.benchmark` and a development signature; their APKs and test runner do not replace the release. Release must not contain synthetic Activities or test hooks. Check the merged manifest/APK as well as the source manifest: release and benchmark target do not request INTERNET; the separate runner needs INTERNET for AndroidX Benchmark's localhost Perfetto connection.

If a Baseline Profile was generated, retain the original `BaselineProfileRule` output and run conditions. Inspect the profile before integration, then rebuild release and compare compilation modes separately. Test variants alone do not mean a profile ships or a speedup has been demonstrated. See [Benchmarks](BENCHMARKING.md).

## Signing

A release without credentials remains unsigned; the debug signer is not substituted automatically. Set all four variables together for a publisher release:

| Variable | Purpose |
| --- | --- |
| `SPLITBOX_KEYSTORE_PATH` | Permanent keystore path outside the published sources |
| `SPLITBOX_KEYSTORE_PASSWORD` | Keystore password |
| `SPLITBOX_KEY_ALIAS` | Alias |
| `SPLITBOX_KEY_PASSWORD` | Key password |

`SPLITBOX_KEYSTORE_TYPE` is optional and defaults to JKS; explicitly specify PKCS12 when needed. Partial configuration or a missing keystore stops Gradle without printing passwords. Supply passwords through the environment or a secret manager; locally, `read -r -s` avoids writing them into shell history. Remove password variables from the environment after the build.

```bash
./gradlew :app:assembleRelease
python3 scripts/package.py --apk app/build/outputs/apk/release/app-release.apk --output-dir dist/publish-1.2.1
(cd dist/publish-1.2.1 && sha256sum -c SHA256SUMS)
```

A local preview using an Android Debug certificate requires explicit `--allow-development-key`; its filename gains a `-dev` suffix. This is not the permanent publisher signature. Android rejects a normal update when the certificate changes: plan the transition, and do not delete the app or its data automatically. Keep one permanent certificate for later public releases and back it up separately.

## Sources and distribution contents

```bash
python3 scripts/check_repository.py
python3 scripts/package.py --source-only --output-dir dist/source-1.2.1
python3 scripts/package.py --apk app/build/outputs/apk/release/app-release-unsigned.apk --unsigned --output-dir dist/unsigned-1.2.1
```

Packaging accepts an explicit APK and checks application ID, both versions, SDK, permissions, CRC, ZIP/ELF 16 KiB alignment, legal assets, and signature. An unsigned APK is marked `-unsigned` and must be signed before installation.

`SHA256SUMS` covers only artifacts from the current command: the source ZIP and, when an APK is explicitly supplied, that APK and `<APK filename>.signature.txt`. Stale files in the same directory are not included. Source ZIP includes Wrapper/JAR, CI, sources, and five binary fixtures; SDKs, keys, caches, logs, and host metadata are excluded. ZIP entries are normalized for repeatable source packaging; bit-for-bit reproducible APK builds are not claimed.

Use a separate output directory for each release. Public distribution includes the signed APK, corresponding sources, signature record, SHA256SUMS, and notes describing verified scenarios and limitations. Retain LICENSE/NOTICE, attribution, and dependency notices. Separately test installation and updating on a device before publication. Distinguish real APK fixtures from synthetic UI datasets, and emulator smoke checks from physical-device measurements. Do not claim physical ARM/Magisk/16 KiB runtime support has been tested, or performance gains, without a corresponding run and original results. Version `1.2.1` / code `8`, GPL-3.0-only, and AnoY606-git attribution must agree across APK, sources, and documentation.

## Repository and tags

The public repository is [AnoY606-git/SplitBox](https://github.com/AnoY606-git/SplitBox). Use an ordinary writable checkout for development:

```bash
git clone https://github.com/AnoY606-git/SplitBox.git
cd SplitBox
git status --short
```

Use your own Git identity and a separate branch. After checks pass, tag `vVERSION` must point to the same commit used to package the sources. Publish APK, source ZIP, signature record, and SHA256SUMS together with changes and limitations. Never upload a keystore or signing secrets to the repository, Release assets, or CI artifacts. Creating a tag/Release is a separate action; the packaging script does not do it.

The update retaining `versionName 1.0` with new `versionCode 4` was published under the separate tag `v1.0-build4`. Existing tags and old release files are not replaced. Use a new unique tag for later builds and state the build number explicitly in the release title.

Release 1.1 uses tag `v1.1`, `versionCode 5`, and files `SplitBox-1.1.apk`, `SplitBox-1.1-source.zip`, `SplitBox-1.1.apk.signature.txt`, and `SHA256SUMS`. Updating from 1.0 retains the permanent publisher certificate.

Release 1.1.1 uses tag `v1.1.1`, `versionCode 6`, and files `SplitBox-1.1.1.apk`, `SplitBox-1.1.1-source.zip`, `SplitBox-1.1.1.apk.signature.txt`, and `SHA256SUMS`. APK uses the same certificate; sources and tag refer to the same commit.

Release 1.2 uses tag `v1.2`, `versionCode 7`, and files `SplitBox-1.2.apk`, `SplitBox-1.2-source.zip`, `SplitBox-1.2.apk.signature.txt`, and `SHA256SUMS`. Before publication, test Russian and English interfaces, language selection, and automatic checks for the selected Root/Shizuku mode.

Release 1.2.1 uses tag `v1.2.1`, `versionCode 8`, and files `SplitBox-1.2.1.apk`, `SplitBox-1.2.1-source.zip`, `SplitBox-1.2.1.apk.signature.txt`, and `SHA256SUMS`. Retain the permanent publisher certificate for updates from 1.2. Earlier build and debug-APK checks in [Verification](VERIFICATION.md) remain historical; record the new release APK's own results, SHA-256, and limits.
