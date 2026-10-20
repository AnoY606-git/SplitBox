**English** · [Русский](../README.md) · [Project overview](../../README.md)

# Developer guide

SplitBox 1.2 is an Android app with a separate JVM core. For your first task, build debug, import a project-owned fixture, and use the table below to locate the layer responsible for your change.

## Reading order

1. [Building](BUILDING.md): tools, opening the project in Android Studio, and the first build.
2. [Architecture](ARCHITECTURE.md): data flow, installation methods, and recovery invariants.
3. [Testing](TESTING.md): automated checks, fixtures, and device scenarios.
4. [Releasing](RELEASING.md): version, signing key, CI, and distribution contents.
5. [Verification](VERIFICATION.md): completed checks for the current version and their limits.
6. [Benchmarks](BENCHMARKING.md): measurement variants, real and synthetic datasets, and dedicated-device runs.

## Code entry points

| Task | Entry point |
| --- | --- |
| Intents/SAF and Android lifecycle | [MainActivity](../../app/src/main/java/dev/splitbox/app/MainActivity.kt) |
| Operation state, import/install/cancel | [SplitBoxViewModel](../../app/src/main/java/dev/splitbox/app/SplitBoxViewModel.kt) |
| Tabs, theme, About dialog | [SplitBoxApp](../../app/src/main/java/dev/splitbox/app/ui/SplitBoxApp.kt) |
| Preview and split list | [InstallScreen](../../app/src/main/java/dev/splitbox/app/ui/InstallScreen.kt) |
| Settings/history | [SettingsScreen](../../app/src/main/java/dev/splitbox/app/ui/SettingsScreen.kt), [HistoryScreen](../../app/src/main/java/dev/splitbox/app/ui/HistoryScreen.kt) |
| Version and permission information | [PackageInspectorDialog](../../app/src/main/java/dev/splitbox/app/ui/PackageInspectorDialog.kt), [PackageInspectionLoader](../../app/src/main/java/dev/splitbox/app/inspector/PackageInspectionLoader.kt), [PermissionSnapshot](../../app/src/main/java/dev/splitbox/app/data/PermissionSnapshot.kt) |
| History filters and report | [HistoryPresentation](../../app/src/main/java/dev/splitbox/app/ui/HistoryPresentation.kt) |
| Translations and persisted messages | [MessageEnvelope](../../core/src/main/kotlin/dev/splitbox/core/MessageEnvelope.kt), [LocalizedText](../../app/src/main/java/dev/splitbox/app/ui/LocalizedText.kt), [string catalogs](../../app/src/main/res/values/strings.xml) |
| Automatic Root/Shizuku checks | [AccessCoordinator](../../app/src/main/java/dev/splitbox/app/installer/AccessCoordinator.kt) |
| UI and settings models | [Models](../../app/src/main/java/dev/splitbox/app/model/Models.kt) |
| URI copying, signatures, and staging | [PackageStager](../../app/src/main/java/dev/splitbox/app/data/PackageStager.kt), [SourceCopier](../../app/src/main/java/dev/splitbox/app/data/SourceCopier.kt) |
| Multiple APK import | [ImportSelection](../../app/src/main/java/dev/splitbox/app/data/ImportSelection.kt), [MultiApkStager](../../app/src/main/java/dev/splitbox/app/data/MultiApkStager.kt), [PackageImporter](../../app/src/main/java/dev/splitbox/app/data/PackageImporter.kt), [StagedApkPreparer](../../core/src/main/kotlin/dev/splitbox/core/StagedApkPreparer.kt) |
| Large files, storage budget, retry before session creation | [PackageSizePolicy](../../app/src/main/java/dev/splitbox/app/data/PackageSizePolicy.kt), [StoragePreflightRecovery](../../app/src/main/java/dev/splitbox/app/data/StoragePreflightRecovery.kt), [TransferProgress](../../app/src/main/java/dev/splitbox/app/installer/TransferProgress.kt) |
| ZIP/ZIP64 and index bounds | [ZipStructure](../../core/src/main/kotlin/dev/splitbox/core/ZipStructure.kt), [SafeArchiveExtractor](../../core/src/main/kotlin/dev/splitbox/core/SafeArchiveExtractor.kt) |
| Cache size index | [CacheSizeIndex](../../app/src/main/java/dev/splitbox/app/data/CacheSizeIndex.kt) |
| Save queue and selection checks | [RevisionedSaveQueue](../../app/src/main/java/dev/splitbox/app/data/RevisionedSaveQueue.kt), [SelectionValidator](../../app/src/main/java/dev/splitbox/app/data/SelectionValidator.kt) |
| Archive formats and compatibility | [ArchivePreparer](../../core/src/main/kotlin/dev/splitbox/core/ArchivePreparer.kt), [SplitSelector](../../core/src/main/kotlin/dev/splitbox/core/SplitSelector.kt) |
| InstallBackend and method selection | [InstallBackend](../../app/src/main/java/dev/splitbox/app/installer/InstallBackend.kt) |
| System journal and recovery | [InstallJournal](../../app/src/main/java/dev/splitbox/app/data/InstallJournal.kt), [SystemSessionRecovery](../../app/src/main/java/dev/splitbox/app/data/SystemSessionRecovery.kt) |
| Root/Shizuku: UUID, phases, and proven outcomes | [PrivilegedOperationStore](../../app/src/main/java/dev/splitbox/app/data/PrivilegedOperationStore.kt), [PrivilegedOperationTracker](../../app/src/main/java/dev/splitbox/app/data/PrivilegedOperationTracker.kt) |
| Returning from permission settings/OBB export | [ReadyStateRecovery](../../app/src/main/java/dev/splitbox/app/data/ReadyStateRecovery.kt) |
| READY checkpoint | [PreparedSnapshot](../../app/src/main/java/dev/splitbox/app/data/PreparedSnapshot.kt) |
| History and settings on disk | [Repositories](../../app/src/main/java/dev/splitbox/app/data/Repositories.kt) |

Source code defines the actual types and signatures. Documentation explains contracts and decisions rather than reproducing every data class.

## Making your first change

Find the state consumer and the implementation responsible for it. For example, a new setting passes through `AppSettings` → repository → SettingsScreen → consuming backend/ViewModel; a toggle without working behavior does not finish the task. For a new format, start with core and a small project-owned fixture, then test the Android boundary. Do not duplicate shared preparation in each backend.

Change the smallest necessary layer, capture the behavior in a test, and run the checks in [Contributing](CONTRIBUTING.md). Installation changes also require the corresponding device: a JVM test cannot establish Magisk permission behavior or OEM Package Manager compatibility.

## Repository contents

`app/`, `core/`, `fixture/`, `benchmark/`, Wrapper, `scripts/`, CI, documentation, and licenses. Five binary test packages and the Wrapper JAR are required. SDK/JDK, caches, module build outputs, keystores, `local.properties`, and `dist/` are local files excluded from Git. Building requires no private services or production signing files.

Contribution rules are in [Contributing](CONTRIBUTING.md), vulnerability reporting in [Security](SECURITY.md), and author/redistribution information in [Authors](AUTHORS.md) and [Attribution](ATTRIBUTION.md). The original [LICENSE](../../LICENSE), [NOTICE](../../NOTICE), and [third-party notices](../../THIRD_PARTY_NOTICES.md) remain authoritative.
