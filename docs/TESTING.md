# Тестирование

## Автоматические проверки

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :fixture:assembleDebug
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check_repository.py --apk app/build/outputs/apk/release/app-release-unsigned.apk
```

При локальном bootstrap используйте `bash scripts/gradle.sh` вместо `./gradlew`. Запускайте Gradle последовательно. Отчёты генерируются в module `build/reports` и `build/test-results`, не хранятся в Git. Фактический результат текущей версии — [VERIFICATION](VERIFICATION.md).

| Набор | Что проверяет |
| --- | --- |
| `core:test` | ZIP traversal/duplicates/CRC/лимиты, AXML, targeting/required splits, настоящие Android fixtures; повторное использование только принадлежащего операции raw source |
| `app:testDebugUnitTest` | Потоковый SHA/byte limit/cancel, индекс кэша, ревизии сохранения и stale validation, PM protocol/timeout/OBB, System recovery/поздние callbacks/ACK, UUID и фазы Root/Shizuku, неизвестный результат и ownership session |
| Python release tools | Состав исходников, fixtures, исключение ключей/кэшей, symlinks, credential patterns, повторяемость ZIP, versionCode, упаковка версии 1.0 и отсутствие stale APK в manifest |
| `app:lintDebug` | Android/Compose/resource checks; все предупреждения оцениваются отдельно от ошибок |
| `check_repository.py --apk` | Package/version/SDK/permissions, legal assets, CRC, ZIP/ELF 16 KiB alignment |

## Контракты для регрессии

Проверяйте не только итоговое значение, но и границу побочного действия. Подтверждение старой ревизии не должно скрывать ошибку новой; invalidation ждёт уже начатую запись до удаления каталога; `flush()` не пропускает незаписанный выбор. Медленный scan кэша не должен задерживать recovery. При CPU-проверке устаревшее завершение не включает install для нового набора.

Для System сохраняйте WRITING до записи APK, состояние commit до `session.commit`, permission/confirmation до Android handoff. Receiver должен дождаться durable записи. Сохраняйте тесты exact-current/retained correlation и ACK после попытки открытия UI. Для Root/Shizuku проверьте cancel после create до возврата ID, timeout/transport loss на commit, чтение каждой фазы, повреждённую запись и запрет удаления чужого UUID. Повторное восстановление не запускает shell или commit; UNKNOWN остаётся `success=null` в истории. Явный новый импорт архивирует предыдущий UNKNOWN при `saveHistory`, без дубликатов UUID.

## Собственные fixtures

Пять небольших файлов в `core/src/test/resources/fixtures/` — APK, bundletool APKS и плоские APKS/APKM/XAPK. Их исходник — модуль `fixture`, package `dev.splitbox.fixture`, версия 1.0. Это открытое тестовое приложение без пользовательских данных, около 128 KiB контейнеров суммарно. Файлы обязательны для core integration tests.

```bash
python3 scripts/generate_fixtures.py
./gradlew :core:test
```

Linux helper использует закреплённый bundletool и локальный Android debug key. Публичные сертификаты в fixtures допустимы; приватный key не публикуется. Генерация может менять binary hashes из-за подписи. Для проверки без перезаписи эталонов задайте `--output-dir` с отдельным локальным каталогом.

## Проверки на устройстве

| Сценарий | Что должно произойти |
| --- | --- |
| SAF/VIEW/SEND/SEND_MULTIPLE, известный и неизвестный размер | Источник читается с ограничением объёма; отмена не оставляет чужих файлов |
| Несколько raw APK через SAF и SEND_MULTIPLE | Один пакет/версия, один base; общий byte limit, без временного ZIP; одинаковые имена не перезаписывают файлы |
| Набор с другим пакетом, версией, дубликатом split или вложенным архивом | Понятная ошибка до вызова backend, очищен только рабочий каталог операции |
| Cold restore набора без source.bin | Все staged APK и ручной выбор доступны; permission roundtrip и установка используют сохранённый base |
| Поиск splits и selected-only | Фильтры не меняют выбранные APK/общий размер; при новом пакете очищаются |
| История: поиск + статус + backend | UNKNOWN не попадает в ошибки/успех; reset возвращает полный список; отчёт копирует только просмотренную запись |
| Сведения: новая/равная/пониженная версия, скрытый пакет | Сравнивается Long versionCode; недоступность не выдаётся за отсутствие установленного приложения |
| Разрешения: разные выбранные splits, старый checkpoint | Объединяются только объявления выбранных APK; неполнота явно показана, список не объявляется выданными правами |
| Диалог сведений закрыт или пакет заменён до ответа | Старый результат не публикуется поверх нового; чтение PackageManager не блокирует main |
| System permission roundtrip / отказ | Возврат из настроек не дублирует установку; отказ понятен пользователю |
| Root grant/deny/revoke | Настоящий root manager; чужая session не abandon, ошибки не маскируются |
| Shizuku permission/UserService/binder loss | Запуск и выдача доступа явные; потеря соединения даёт корректный исход |
| Process death между записью COMMITTED и вызовом System commit | Незапечатанная сессия отменена, виден прерванный результат, следующая операция доступна |
| Process death после System commit | Запечатанная сессия не отменяется восстановлением; подтверждённый результат либо UNKNOWN, без повторного commit |
| Отказ в System permission → смена backend или отмена → cold restore | Указатель READY и точный выбор частей сохранены; нет повторного запроса/установки |
| Process death в каждой фазе Root/Shizuku | Доказанный сохранённый итог либо нейтральный UNKNOWN; нет автоматического su/commit и дубликата истории |
| Быстрые переключения, ENOSPC и повтор сохранения | Виден сбой; восстановлен последний подтверждённый выбор; установка ждёт записи и актуальной проверки |
| Новый импорт после UNKNOWN | При включённой истории старая операция архивирована, ошибка записи не скрыта |
| Rotation, вкладки, крупный шрифт, TalkBack | Основные действия доступны, смысл состояния не теряется |
| ENOSPC, удалённый cache, повреждённый архив | Нет ложного success; остаётся возможность выбрать новый файл |
| Проверка/сохранение частей завершаются во время экспорта OBB | При успехе, ошибке и отмене ZIP актуальны pending/error-флаги; нет вечного ожидания |
| Экспорт OBB из UNKNOWN и его отмена | UNKNOWN и журнал остаются; отменяется только экспорт, сессия установки не затрагивается |
| OBB export/copy failure | Принадлежность пакету проверена; APK success не маскируется ошибкой дополнительного переноса |
| Base ARM64/ARM32 + feature ARM32 на двухархитектурном устройстве | Автовыбор выбирает общий ARM32; непересекающиеся наборы отклоняются |
| versionName ссылается на строковый ресурс | Импорт разрешён, числовая версия и имя пакета сохранены |

Нужны API 34/35/36, физический ARM64, реальные Magisk/KernelSU и Shizuku. Статический 16 KiB alignment не заменяет runtime на 16 KiB устройстве. AOSP `su 0` не подтверждает совместимость с Magisk `su -c`. Неиспытанные сценарии отмечайте явно.

Все ADB-действия направляйте явным serial на отдельное тестовое устройство. Сначала сверьте identity; значение serial само по себе не подтверждает, что это нужный экземпляр:

```bash
adb -s DEVICE_SERIAL get-state
adb -s DEVICE_SERIAL shell getprop ro.product.model
adb -s DEVICE_SERIAL shell getprop ro.build.fingerprint
adb -s DEVICE_SERIAL shell getprop ro.product.cpu.abi
adb -s DEVICE_SERIAL shell getprop ro.build.version.sdk
```

Сверьте модель/fingerprint/ABI/API с выделенным для проверки устройством до установки, `pm clear` или завершения процесса. Не выбирайте первое подключённое устройство и не используйте рабочий Android-контейнер. При другом ADB server port передавайте одинаковый `-P PORT` во всех командах.

Emulator-only helper требует `--serial` либо `SPLITBOX_TEST_SERIAL` и дополнительно проверяет `ro.kernel.qemu=1`:

```bash
python3 scripts/device_qa.py --serial emulator-5554 snapshot home
python3 scripts/device_qa.py --serial emulator-5554 tap 'Выбрать файл'
```

ADB executable/port: `SPLITBOX_ADB`, `--adb-port`/`SPLITBOX_ADB_PORT`; по умолчанию 5037. Screenshots/XML остаются в игнорируемом локальном каталоге.

## Производительность и Baseline Profile

Отдельный модуль `:benchmark` и его [README](../benchmark/README.md) задают версии зависимостей, сценарии, compilation mode, число повторов и пути исходных JSON/Perfetto artifacts. Target-варианты `benchmark` и `profileGeneration` имеют suffixed ID `dev.splitbox.app.benchmark`, `testOnly=true` и profileable-манифест. Они заменяют друг друга, но не обычное приложение. Release и target не запрашивают INTERNET; отдельный runner APK получает INTERNET для localhost Perfetto от AndroidX Benchmark.

После проверки identity явно ограничьте Gradle тем же serial:

```bash
bash scripts/gradle.sh :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL -Psplitbox.benchmarkIterations=5
bash scripts/gradle.sh :benchmark:connectedProfileGenerationAndroidTest \
  -Pandroid.injected.device.serial=DEVICE_SERIAL
```

Сценарии вызывают `pm clear` только тестового target-пакета, поэтому требуют выделенного устройства. Macrobenchmark по умолчанию отвергает эмулятор; только для functional smoke допустим явный аргумент `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`. Сохраните его в отчёте и не подавляйте другие ошибки.

`importApk` использует собственный настоящий APK и ACTION_VIEW/FileProvider, проходит production copy/parser/signatures/SHA. `splitList50/500/4096` подставляют синтетическую metadata в настоящий `InstallScreen`; они измеряют список/фильтр/прокрутку и локальное Compose-состояние тестовой Activity. Они не создают сотни APK и не проверяют parser, production checkpoint/validation или backend. `StartupTimingMetric` показывает первый кадр Activity; это не отдельная latency URI → READY и не скорость копирования. BaselineProfileRule использует настоящие startup/navigation/import journeys без синтетического списка; полученный профиль требует отдельной проверки и интеграции в release.

Для полного наблюдаемого пути импорта сохраняется отдельный JSON `splitbox-import-observed-ready-{none,partial}-TIMESTAMP.json`. Интервал от отправки ACTION_VIEW до доступной установки включает запуск Activity, обработку файла, сохранение/валидацию, отрисовку и накладные расходы UIAutomator. Успешные samples отделены от failures, незавершённый запуск помечен `completed=false`. Это наблюдение из тестового runner, а не чистое время parser или копирования; формат и расположение артефакта описаны в [benchmark/README](../benchmark/README.md).

Для сравнения фиксируйте устройство, ROM/fingerprint, ABI/API, питание, ориентацию, font scale, compilation mode, iterations, commit и SHA-256 dataset. Сохраняйте оригинальные traces/JSON/logcat; для p50/p95, peak staging, bytes read/write и задержки отмены нужны соответствующие отдельные измерения. Unit-тест SHA/byte counts подтверждает корректность и объём копирования, а не ускорение телефона.

Эмулятор подходит для функциональных сценариев и генерации профиля, его timings не являются производительностью физического ARM. Наличие benchmark-модуля не подтверждает ускорение, работу Magisk/KernelSU или runtime 16 KiB. Указывайте фактически доступные устройства и неиспытанные сценарии в [VERIFICATION](VERIFICATION.md); этот документ описывает методику, а не результаты выполненного прогона.
