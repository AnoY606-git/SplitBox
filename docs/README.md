# Путеводитель разработчика

SplitBox 1.1 — Android-приложение с отдельным JVM-ядром. Для первой задачи достаточно собрать debug, пройти путь импорта собственного fixture и определить слой изменения по таблице ниже.

## Порядок знакомства

1. [BUILDING](BUILDING.md): инструменты, открытие в Android Studio и первая сборка.
2. [ARCHITECTURE](ARCHITECTURE.md): движение данных, способы установки и recovery-инварианты.
3. [TESTING](TESTING.md): автоматические проверки, fixtures и сценарии на устройстве.
4. [RELEASING](RELEASING.md): версия, ключ подписи, CI и состав поставки.
5. [VERIFICATION](VERIFICATION.md): выполненные проверки текущей версии и их пределы.
6. [benchmark/README](../benchmark/README.md): варианты измерений, реальные и синтетические наборы, запуск на выделенном устройстве.

## Где искать код

| Задача | Точка входа |
| --- | --- |
| Intent/SAF и Android lifecycle | [MainActivity](../app/src/main/java/dev/splitbox/app/MainActivity.kt) |
| Состояние операции, import/install/cancel | [SplitBoxViewModel](../app/src/main/java/dev/splitbox/app/SplitBoxViewModel.kt) |
| Вкладки, тема, окно о приложении | [SplitBoxApp](../app/src/main/java/dev/splitbox/app/ui/SplitBoxApp.kt) |
| Предпросмотр и список splits | [InstallScreen](../app/src/main/java/dev/splitbox/app/ui/InstallScreen.kt) |
| Настройки/история | [SettingsScreen](../app/src/main/java/dev/splitbox/app/ui/SettingsScreen.kt), [HistoryScreen](../app/src/main/java/dev/splitbox/app/ui/HistoryScreen.kt) |
| Сведения о версии и разрешениях | [PackageInspectorDialog](../app/src/main/java/dev/splitbox/app/ui/PackageInspectorDialog.kt), [PackageInspectionLoader](../app/src/main/java/dev/splitbox/app/inspector/PackageInspectionLoader.kt), [PermissionSnapshot](../app/src/main/java/dev/splitbox/app/data/PermissionSnapshot.kt) |
| Фильтры и отчёт истории | [HistoryPresentation](../app/src/main/java/dev/splitbox/app/ui/HistoryPresentation.kt) |
| Модели UI и настроек | [Models](../app/src/main/java/dev/splitbox/app/model/Models.kt) |
| Копирование URI, подписи и staging | [PackageStager](../app/src/main/java/dev/splitbox/app/data/PackageStager.kt), [SourceCopier](../app/src/main/java/dev/splitbox/app/data/SourceCopier.kt) |
| Импорт нескольких APK | [ImportSelection](../app/src/main/java/dev/splitbox/app/data/ImportSelection.kt), [MultiApkStager](../app/src/main/java/dev/splitbox/app/data/MultiApkStager.kt), [PackageImporter](../app/src/main/java/dev/splitbox/app/data/PackageImporter.kt), [StagedApkPreparer](../core/src/main/kotlin/dev/splitbox/core/StagedApkPreparer.kt) |
| Индекс размера кэша | [CacheSizeIndex](../app/src/main/java/dev/splitbox/app/data/CacheSizeIndex.kt) |
| Очередь сохранения и проверка выбора | [RevisionedSaveQueue](../app/src/main/java/dev/splitbox/app/data/RevisionedSaveQueue.kt), [SelectionValidator](../app/src/main/java/dev/splitbox/app/data/SelectionValidator.kt) |
| Форматы архивов и совместимость | [ArchivePreparer](../core/src/main/kotlin/dev/splitbox/core/ArchivePreparer.kt), [SplitSelector](../core/src/main/kotlin/dev/splitbox/core/SplitSelector.kt) |
| InstallBackend и выбор способа | [InstallBackend](../app/src/main/java/dev/splitbox/app/installer/InstallBackend.kt) |
| System journal и восстановление | [InstallJournal](../app/src/main/java/dev/splitbox/app/data/InstallJournal.kt), [SystemSessionRecovery](../app/src/main/java/dev/splitbox/app/data/SystemSessionRecovery.kt) |
| Root/Shizuku: UUID, фазы и доказанный результат | [PrivilegedOperationStore](../app/src/main/java/dev/splitbox/app/data/PrivilegedOperationStore.kt), [PrivilegedOperationTracker](../app/src/main/java/dev/splitbox/app/data/PrivilegedOperationTracker.kt) |
| Возврат из permission/OBB export | [ReadyStateRecovery](../app/src/main/java/dev/splitbox/app/data/ReadyStateRecovery.kt) |
| READY checkpoint | [PreparedSnapshot](../app/src/main/java/dev/splitbox/app/data/PreparedSnapshot.kt) |
| История и настройки на диске | [Repositories](../app/src/main/java/dev/splitbox/app/data/Repositories.kt) |

Фактические типы и сигнатуры находятся в исходниках. Документация объясняет контракты и причины решений, не дублирует все data classes.

## Как внести первое изменение

Найдите потребителя состояния и ответственную реализацию. Например, новая настройка проходит через `AppSettings` → repository → SettingsScreen → потребляющий backend/ViewModel; один переключатель без работающего поведения не завершает задачу. Для нового формата начинайте с core и маленького собственного fixture, затем проверяйте Android-границу. Общую подготовку не копируйте в каждый backend.

Измените минимальный нужный слой, воспроизведите поведение тестом и запустите проверки из [CONTRIBUTING](../CONTRIBUTING.md). Изменения установки дополнительно требуют соответствующего устройства: JVM-тест не подтверждает разрешение Magisk или работу OEM Package Manager.

## Что хранится в репозитории

`app/`, `core/`, `fixture/`, `benchmark/`, Wrapper, `scripts/`, CI, документация и лицензии. Пять бинарных тестовых пакетов и Wrapper JAR обязательны. SDK/JDK, кэши, module build outputs, keystores, local.properties и `dist/` — локальные файлы, исключённые из Git. Закрытых сервисов и production signing files для сборки не требуется.
