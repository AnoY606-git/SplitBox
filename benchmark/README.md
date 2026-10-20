<!-- Copyright (C) 2026 AnoY606-git and SplitBox contributors
     SPDX-License-Identifier: GPL-3.0-only -->
[English](../docs/en/BENCHMARKING.md) · **Русский**

# Измерения SplitBox

Модуль `:benchmark` использует `com.android.test` и официальный
`androidx.benchmark:benchmark-macro-junit4:1.4.1`, включая `BaselineProfileRule`.
Бинарные fixtures принадлежат проекту: assets тестового APK читаются из
`core/src/test/resources`, без второй копии в исходниках.
AndroidX Benchmark, UIAutomator и AndroidX Test распространяются по Apache-2.0;
они подключены как инструменты тестирования и не входят в production APK.
Версии этих зависимостей указаны в `benchmark/build.gradle.kts`.

Оба тестовых варианта приложения имеют ID `dev.splitbox.app.benchmark`, подпись
локальным Android debug key, `debuggable=false`, `testOnly=true` и отдельный
profileable-манифест. Они перезаписывают друг друга при установке и могут
сосуществовать с обычным `dev.splitbox.app`. Release-манифест не меняется.
`profileinstaller:1.4.1` явно подключён только к этим вариантам; обычный release
уже получает ProfileInstaller 1.4.0 транзитивно. Наличие библиотеки не доказывает
включение собственных правил SplitBox в профиль APK. Target APK и release
не запрашивают INTERNET. Отдельный test runner APK получает INTERNET транзитивно
от AndroidX Benchmark для localhost-соединения Perfetto.

| Вариант | Приложение | Тесты |
| --- | --- | --- |
| `benchmark` | R8 и shrinkResources как в release; дополнительная Activity только для UI-наборов | `SplitBoxMacrobenchmark` |
| `profileGeneration` | Без R8/shrinkResources; имена классов пригодны для генерации профиля; без синтетической Activity | `SplitBoxBaselineProfile` |

В AGP 8.13.2 конфигурации `*TestedApks` получают атрибуты Kotlin platform
`androidJvm` и JVM environment `android` явно. Это сохраняет Android-варианты
транзитивных Compose-зависимостей при вычислении connected test data; обычные
compile/runtime classpaths и production dependencies не меняются.

## Сценарии и метрики

Перед каждым тестом данные только тестового пакета очищаются через `pm clear`
**до** `measureRepeated` и компиляции AndroidX. После компиляции shell-очистка не
выполняется: она может удалить ART profiles. `importApk` выполняет повторные
холодные импорты: первый начинается с пустых данных, последующие заменяют предыдущий
READY через штатный `importUri`. Перед ними сохранены предыдущий READY и его кэш;
между итерациями кэш не сбрасывается, удаление прежних каталогов не является
условием сценария. Исходные состояния первой и следующих итераций различаются.
Очистка кэша через настройки не входит в этот сценарий.

Запускать на отдельном устройстве: это намеренно удаляет настройки, journal,
историю и временные файлы тестового пакета. Установку приложений сценарии не запускают.

| Тест | Данные и действие | Измерение |
| --- | --- | --- |
| `coldStart` | Пустые данные, холодный запуск MainActivity | `StartupTimingMetric` |
| `settingsHistoryBack` | Главная → Настройки → пустая История → Установка | `FrameTimingMetric` |
| `importApk` | Одинаковые байты `application.apk` под именами `application-ITER.apk` через FileProvider и настоящий ACTION_VIEW; ожидание нового точного имени, готового выбора 1 APK и доступной установки | `StartupTimingMetric`, `FrameTimingMetric`, отдельный JSON с `uriDispatchToObservedReady` |
| `splitList50`, `splitList500`, `splitList4096` | Соответственно 50/500/4096 APK-записей, включая base; фильтр выбранных, 6 прокруток вниз, отключение строки, 3 прокрутки вверх | `FrameTimingMetric` |

Настройки/история открываются настоящей нижней навигацией. Возврат к установке
использует эту же навигацию: системный Back в текущем приложении завершает Activity.

Импорт включает настоящий copy/parser/signatures/SHA по настройкам по умолчанию.
Файл и исходный URI grant готовятся в `setupBlock`. AndroidX при `StartupMode.COLD`
выполняет force-stop после этого блока, отзывая grant. Поэтому runner повторно
выдаёт read grant от UID владельца FileProvider в начале измеряемого блока, после
force-stop и до запуска observed timer и Activity. Этот короткий вызов входит в
Perfetto capture; копирование fixture остаётся вне измеряемого блока. Проверка
готовности сначала ждёт точное имя нового файла, затем включённую установку и
summary одного APK; прежний READY или ошибка не считаются новым импортом.
StartupTimingMetric измеряет первый кадр Activity, а не полное время импорта.
Frame timings также не являются
измерением parser latency или скорости копирования.

Дополнительный артефакт `splitbox-import-observed-ready-{none,partial}-TIMESTAMP.json`
сохраняет `elapsedRealtimeNanos()` от вызова `startActivityAndWait(ACTION_VIEW)` до
наблюдения нового имени файла, включённой кнопки установки и summary одного выбранного
APK. Это полное wall-clock наблюдение из runner: dispatch команды, startup, обработка URI,
copy/parser/signatures/SHA, сохранение/валидация выбора, отрисовка и накладные расходы
UIAutomator/IPC/ожиданий включены. Оно не измеряет parser CPU или чистую скорость
копирования и не является `timeToFullDisplayMs`. Повторная выдача grant происходит
до начала этого таймера. Копирование fixture и запись JSON происходят вне
измеряемого блока. При повторном импорте штатная замена прежнего READY входит
в измеряемый путь.

В `samples` находятся только успешные наблюдения `{iteration, durationNs}`; ошибки
записываются отдельно в `failures` без time-to-READY. `completed=false` отмечает
незавершённый тест; такой запуск не используют для сравнения. JSON также содержит
compilation, expectedIterations, SHA-256 исходного payload `fixtures/application.apk`,
шаблон имён `application-{iteration}.apk` (итерации с нуля), состояния `empty` для
первой итерации и `prior-ready-persisted` для следующих, `cacheResetBetweenIterations=false`,
fingerprint/ABI/API/model/fontScale и явное указание observation overhead. Имя меняется,
байты и SHA-256 остаются теми же. Файл пишется после `measureRepeated`, включая случай
ошибки, в `additionalTestOutputDir` либо external media dir runner APK и
сообщается host через instrumentation `additionalTestOutputFile_*`. Для выводов о
самих byte counts и SHA нужны соответствующие unit tests. Первый импорт и повторную
замену READY анализируют отдельно: агрегат этой смешанной серии не является
универсальным p95 импорта или доказательством ускорения физического телефона.

Большие списки подставляют только синтетическую metadata в production
`InstallScreen`, без создания APK, обхода parser или изменения ViewModel.
Отключение части обновляет Compose-состояние в тестовой Activity, не измеряет
production checkpoint/validation и не является интеграционным тестом выбора.
Callback установки выбрасывает ошибку до обращения к backend. Результаты этих
трёх тестов характеризуют UI, не обработку архива с таким числом APK.

По умолчанию каждый Macrobenchmark выполняет 5 повторов с
`CompilationMode.None()` — без ahead-of-time компиляции. Режим задаётся
`-Psplitbox.benchmarkCompilation=None` (по умолчанию) или `Partial`.
`Partial` означает `CompilationMode.Partial(BaselineProfileMode.Require, warmupIterations=0)`:
AndroidX должен успешно установить упакованный в target APK профиль; при ошибке
нет fallback на None. Другие значения отклоняются. Сам `Require` не доказывает
наличие собственных app-правил: зависимости тоже могут содержать baseline profiles.
Перед сравнением нужно проверить профиль, полученный реальным production journey,
и его включение в target APK.

Число повторов задаётся `-Psplitbox.benchmarkIterations=10`; оно должно быть
положительным. Для сравнения None/Partial используют **один и тот же собранный APK**
с интегрированным сгенерированным профилем и одинаковые устройство, Android,
ориентацию, font scale, режим питания, число повторов и данные, включая одинаковую
последовательность первого пустого импорта и последующих замен READY. Для исследований
first draw отдельно учитывают восстановление journal: `coldStart` начинается с
пустых данных, последующие итерации `importApk` сохраняют предыдущий READY.

## Запуск

Нужны JDK 17, SDK/build-tools 36 и ADB-устройство API 34+. Эмулятор допустим для
проверки сценариев и генерации профиля, но его числа нельзя выдавать за физическое
устройство. Не использовать рабочий пользовательский Android-контейнер.
Обычная сборка через проектный wrapper:

```sh
bash scripts/gradle.sh :app:assembleBenchmark :benchmark:assembleBenchmark \
  :app:assembleProfileGeneration :benchmark:assembleProfileGeneration
```

На выделенном устройстве; Gradle явно ограничивается нужным serial:

```sh
bash scripts/gradle.sh :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL -Psplitbox.benchmarkIterations=5
bash scripts/gradle.sh :benchmark:connectedProfileGenerationAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL
```

Macrobenchmark по умолчанию отклоняет эмулятор. Только для functional smoke run
разрешено явно добавить
`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`.
Такой запуск нужно пометить в отчёте; не подавлять другие ошибки и не публиковать
его как доказательство ускорения. Для конкретного теста можно использовать
`-Pandroid.testInstrumentationRunnerArguments.class=dev.splitbox.benchmark.SplitBoxMacrobenchmark#coldStart`.
Ручная установка тестового target APK требует `adb install -t -r`.

Профиль генерируется `BaselineProfileRule` из настоящих startup/navigation/import
journeys. Синтетический набор в генерации не участвует. Ручного списка методов или
заранее выдуманного профиля в репозитории нет. Правила из выводимых test artifacts
надо проверить и интегрировать в `app/src/main/baseline-prof.txt` отдельным изменением
после успешного device-run. Исходный профиль не копировать из minified benchmark.
После интеграции нужны отдельная сборка release и сравнение compilation modes;
Macrobenchmark-тесты поддерживают оба режима. Соберите target APK один раз после
интеграции проверенного профиля, сохраните его SHA-256 и выполните обе серии:

```sh
bash scripts/gradle.sh :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL -Psplitbox.benchmarkCompilation=None
bash scripts/gradle.sh :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL -Psplitbox.benchmarkCompilation=Partial
```

Сохраняйте результаты каждого запуска отдельно: AGP может заменить connected report
следующей серией. Не пересобирайте target из другого исходного состояния между
сериями; сравните SHA-256 использованного APK. Эмуляторные числа описывают этот AVD,
а не ускорение физического телефона.

APK находятся в `app/build/outputs/apk/{benchmark,profileGeneration}/` и
`benchmark/build/outputs/apk/{benchmark,profileGeneration}/`. Connected reports —
`benchmark/build/reports/androidTests/connected/`, XML —
`benchmark/build/outputs/androidTest-results/connected/`. AndroidX выводит пути JSON,
Perfetto traces и `*-baseline-prof.txt` в instrumentation output; AGP собирает
additional test output под `benchmark/build/outputs/connected_android_test_additional_output/`.
Сохранять оригинальные артефакты и logcat, модель/ABI/API, fingerprint, font scale,
итерации, compilation mode, commit, dataset SHA-256 и сведения о подавлении EMULATOR.
Отсутствующие physical ARM/Magisk/16 KiB измерения указывать как непроверенные.

Официальные основания настройки:
[Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview),
[создание Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile),
[ограничения генерации профиля](https://developer.android.com/topic/performance/baselineprofiles/overview),
[AndroidX Benchmark 1.4.1](https://developer.android.com/jetpack/androidx/releases/benchmark#1.4.1),
[сравнение compilation modes](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile),
[определения метрик](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics).
