**English** · [Русский](../../CONTRIBUTING.md) · [Developer guide](README.md)

# Contributing

Start with the [developer guide](README.md) and [build instructions](BUILDING.md). Changes to SplitBox's own code are distributed under GPL-3.0-only; preserve applicable copyright/NOTICE and dependency licenses. Project authorship is documented in [Authors](AUTHORS.md).

## Code change rules

- Keep `core` Kotlin/JVM without Android imports. UI sends actions to ViewModel; each backend implements one installation method.
- Select System/Root/Shizuku explicitly. Failure of one method does not authorize silently elevating privileges.
- Do not weaken path, CRC, limit, metadata, or signature checks to improve speed.
- Key lifecycle invariants are listed in [Architecture](ARCHITECTURE.md). UNKNOWN does not become success, and commit is not retried automatically.
- The app operates locally, without ads, analytics, accounts, or INTERNET permission.
- Write explanatory comments, KDoc/docstrings, and project-authored messages in Russian. Preserve API identifiers, format keys, commands, and external protocol responses in their original form. Do not translate legal Copyright/SPDX notices or canonical license texts. English documentation is maintained alongside its Russian source.

For a fix, first reproduce the defect with a behavioral check. Tests should catch regressions rather than mirror implementation. For UI changes, check themes, large fonts, TalkBack, and action accessibility. Support performance changes with measurements.

## Before a pull request

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check_repository.py
```

Run Gradle sequentially in one checkout. Choose additional device scenarios from [Testing](TESTING.md). Use small project-owned fixtures; the binary fixtures in `core/src/test/resources/fixtures/` are required by tests and tracked in Git.

Describe the problem, resulting behavior, and completed checks in the PR. A bug report should include SplitBox version, Android/ROM/device model, installation method, package format and size, steps, and error text. Remove personal URIs/paths and keys before submitting. Report vulnerabilities according to [Security](SECURITY.md).
