# Проверки SplitBox 1.1

Дата: **14 октября 2026**. Версия **1.1**, `versionCode 5`, Android 14+ (minSdk 34 / target SDK 36). Публичный тег: [v1.1](https://github.com/AnoY606-git/SplitBox/releases/tag/v1.1).

## Автоматические проверки

Полный локальный прогон `:core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :fixture:assembleDebug :app:compileBenchmarkKotlin :app:compileProfileGenerationKotlin` — **BUILD SUCCESSFUL**. Прошли **203 JVM-теста** (core 77, app 126) и **12 Python-тестов**, без failures/errors/skipped. Lint debug/release: **0 ошибок / 5 прежних предупреждений** в каждом варианте (`UseKtx` × 4, `ObsoleteSdkInt` × 1). Новых зависимостей и Android permissions нет.

Для новых функций добавлены проверки общих лимитов staging, одинаковых имён, принадлежности и symlinks, отмены, выбора/поиска, пересечения фильтров истории, отчёта, числовых версий Long, SDK-деклараций разрешений, неполных старых данных и отмены запросов инспектора. Независимое ревью и Android QA выявили и помогли устранить три регрессии: канонизация `/data/user/0` → `/data/data`, допустимые неположительные `maxSdkVersion` и совпадение имени разрешения со служебным ключом списка. Две первые воспроизводились падающими регрессионными тестами, последняя проверена настоящим APK с разрешением `search`.

## Подписанный APK

- Размер: **2 734 556 байт** (около 2,61 МиБ).
- SHA-256: `9d32b341dc00e32c15a79cd4a21a7dfcd1959e46e2781ded74bb40735fe409e7`.
- Сертификат издателя прежний: SHA-256 `276541bb10833dc482bdf54f4760498633a38316b13a29901b84cfdb3a316478`, `CN=AnoY606-git, O=SplitBox`, RSA 4096, APK Signature Scheme v2.
- `apksigner verify` и APK audit: package/version/SDK, точный набор разрешений, ZIP CRC, alignment и legal assets. INTERNET и MANAGE_EXTERNAL_STORAGE отсутствуют.

## Android 14: проверка приложения

Использован отдельный AOSP API 34 x86_64 AVD `splitbox-1-1-api34`, KVM, 2 CPU / 2048 MiB RAM, 1080×1920 / density 420. Личный Waydroid не использовался. Исходно установлен публичный APK 1.0 (сборка 4), создана реальная успешная System-установка и запись истории. Обновление на подписанную 1.1 прошло без удаления данных; история сохранилась. Исправления проверялись на R8 release APK с постоянным сертификатом. После финальных сценариев crash buffer пуст; Package Manager подтвердил versionName 1.1 / versionCode 5.

| Сценарий | Результат |
| --- | --- |
| Множественный выбор SAF | Через кнопку «Выбрать несколько APK» отмечены base и density, подготовлен и установлен весь набор |
| Несколько APK из внешнего приложения | Base + density с одинаковым displayName, повторный URI: подготовлены ровно 2 APK; файлы не перезаписывают друг друга |
| Только ClipData | ACTION_SEND_MULTIPLE без EXTRA_STREAM корректно импортирует оба APK |
| Холодное восстановление набора | После force-stop сохранены оба APK и выбор; SHA-256 единственного исходного файла для набора не заявляется |
| Разные приложения в наборе | Подготовка отклонена до установки, backend не запускается |
| Поиск частей + выбранные | Поиск `xxhdpi` показывает 1 из 2, закреплённый установочный счётчик остаётся 2 из 2 |
| System | Реальная установка base + density через Android подтверждена Package Manager |
| Shizuku | Shizuku 13.6.0.r1086, shell UID, явное разрешение: реальная установка base + density завершена, результат сохранён в истории |
| Сведения о пакете | Входные версии 3/2/1 при установленной 2 показаны как обновление / та же версия / версия ниже; поиск разрешений, SDK 23 и maxSdk 32; после cold restore сохранились также maxSdk 0/-1 |
| Граничное имя разрешения | APK с декларацией `search`: строка отображается одновременно с поиском, сбоя duplicate key нет |
| История | Реальная отмена Android дала отказ; фильтры статуса/способа, пустой результат и сброс работают |
| Крупный шрифт | При 150% доступны основная кнопка установки, прокрутка и закрытие инспектора, фильтры истории; после проверки масштаб возвращён к 100% |
| Отчёт | После предпросмотра и копирования весь текст (293 символа) точно совпал при вставке через Android clipboard |

Проверки используют собственные маленькие APK и отдельный testOnly sender; эти вспомогательные приложения не входят в релиз или архив исходников. Реальный `PermissionSnapshot` дополнительно проверен холодным восстановлением APK с известными декларациями; nullable старые данные покрыты JVM-моделью и ревью codec.

## Пределы проверки

Реальный Magisk/KernelSU, физический ARM, Android 15/16, OEM/work profiles, многогигабайтные пакеты и 16 KiB runtime не испытывались в этом выпуске. Root использует существующий проверяемый протокол, но проверка на AOSP не заменяет root manager на телефоне. Новый benchmark-прогон производительности не выполнялся; числа прежнего выпуска ниже остаются историческими. Уменьшение копирования набора APK следует из потокового пути без промежуточного ZIP и не объявляется измеренным ускорением на всех устройствах.

Статус публикационного CI доступен в [Actions](https://github.com/AnoY606-git/SplitBox/actions/workflows/ci.yml). Воспроизведение — [TESTING](TESTING.md), контракты — [ARCHITECTURE](ARCHITECTURE.md).

<details>
<summary>Архив: проверки SplitBox 1.0</summary>

# Проверки SplitBox 1.0

## Сборка 4: исправления после аудита

Дата: **14 октября 2026**. Релиз [v1.0-build4](https://github.com/AnoY606-git/SplitBox/releases/tag/v1.0-build4): versionName **1.0**, versionCode **4**, minSdk 34 / target SDK 36. Первый тег `v1.0` соответствует сборке 3; сведения о ней сохранены ниже отдельно.

Полный локальный прогон `:core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :fixture:assembleDebug` завершился **BUILD SUCCESSFUL**. Прошли **173 JVM-теста** (core 65, app 108) и **12 Python-тестов**, без failures/errors/skipped. Lint debug/release: **0 ошибок / 5 предупреждений** в каждом варианте (`UseKtx` × 4, `ObsoleteSdkInt` × 1).

Добавлены 29 регрессионных и граничных тестов. До исправлений новые сценарии воспроизводили ошибки: 6 падений в ядре, 10 в восстановлении состояния и отдельное падение маршрутизации отмены экспорта. Проверяются общий ABI модулей, ресурсный versionName, фактическое состояние системной сессии, атомарный возврат READY, отказ записи с повтором и актуальные результаты очереди/валидатора после успеха, ошибки и отмены экспорта. Эти проверки используют production helpers и настоящие очереди с управляемым coroutine scheduler.

Подписанный R8 APK: **2 668 988 байт**, SHA-256 `32d3768435818d03a2144698c86e01451e2697fc089b00d64b4b9ff763ab8ded`. Сертификат издателя прежний: SHA-256 `276541bb10833dc482bdf54f4760498633a38316b13a29901b84cfdb3a316478`. Проверка подписи и APK audit пройдены: package/version/SDK, разрешения, CRC, alignment и legal assets. INTERNET отсутствует.

На отдельном AOSP API 34 x86_64 AVD `splitbox-fixes-api34` проверены:

- Настоящая незапечатанная `PackageInstaller.Session`, созданная временной instrumentation с сохранённым `COMMITTED`: после остановки процесса и запуска исправленного debug APK показан прерванный результат; Android API подтвердил удаление сессии и освобождение журнала. Это внедрение состояния в точке сбоя, а не случайный kill в узком временном окне.
- APK со строковым ресурсом в `versionName`, собранный AAPT2: Android принимает его, SplitBox импортирует, сохраняет package/versionCode и показывает отсутствие разрешённого названия версии.
- На финальной debug-сборке 4: отказ в System permission → выбор Root → остановка процесса → запуск. Пакет, выбранный APK и backend ROOT восстановлены; Root-команда не запускалась.
- Обычное обновление подписанного приложения через `adb install -r` со сборки 3 до 4 прошло; Package Manager подтвердил `versionCode=4`, `versionName=1.0`.
- На финальном подписанном R8 APK прошли SAF-импорт ресурсного fixture, выдача разрешения, системное подтверждение и реальное обновление fixture. Android подтвердил `versionName=1.2-resource`, `installerPackageName=dev.splitbox.app`; crash buffer пуст.

Гонки завершения валидатора/записи во время экспорта и его отмена проверены детерминированными coroutine-тестами. На устройстве этот узкий timing-сценарий не воспроизводился. Временные instrumentation/fixtures не входят в release или архив исходников. Личный Waydroid не использовался.

Результаты прежних измерений не являются новым benchmark-прогоном сборки 4. Реальный Magisk/KernelSU, физический ARM и Android 15/16 повторно не проверялись. Границы проверок устройства перечислены ниже для каждой сборки.

<details>
<summary>Архив: опубликованная сборка 3 и прежние измерения</summary>

Дата: **14 октября 2026**. Package `dev.splitbox.app`, versionName **1.0**, versionCode **3**, minSdk 34 / target SDK 36. Автор: [AnoY606-git](https://github.com/AnoY606-git).

## Автоматические проверки

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :fixture:assembleDebug
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check_repository.py
```

Финальный прогон завершился **BUILD SUCCESSFUL**: **144 JVM tests** (core 50, app 94), **0 failures/errors/skipped**, **12 Python tests**; lint debug/release — **0 errors / 5 warnings** в каждом варианте. Предупреждения `UseKtx` (4) и `ObsoleteSdkInt` (1) не скрываются.

JVM-проверки охватывают архивы, metadata/targeting, собственные Android fixtures, PM protocol и неоднозначные ответы commit, timeout, OBB, ownership session при отмене, System correlation/ACK, очереди сохранения, поколения выбора, индекс кэша и восстановление привилегированного результата. Python-проверки охватывают source inventory, исключение ключей/кэшей, symlinks, версии и упаковку source-only.

## APK

Публичный R8 release `SplitBox-1.0.apk` собран с постоянным ключом издателя, размер **2,668,988 байт**. Перед публикацией повторно выполнены JVM-тесты, lint debug/release и сборка с этой конфигурацией подписи.

- APK SHA-256: `8d33d38cc7b91cdf16f0680d5dedd7c1f2ffb8a0ff59583635bd56be876aa9d1`.
- Certificate SHA-256: `276541bb10833dc482bdf54f4760498633a38316b13a29901b84cfdb3a316478`; `CN=AnoY606-git, O=SplitBox`, RSA 4096, APK Signature Scheme v2.
- `apksigner verify` и штатный APK audit прошли: applicationId/версии/SDK, точный набор из трёх разрешений, ZIP CRC, ZIP/ELF 16 KiB alignment, legal assets.
- INTERNET и MANAGE_EXTERNAL_STORAGE отсутствуют. В release нет синтетической benchmark Activity; обычная сборка release без credentials остаётся unsigned.
- Ключ подписи, SDK/JDK, кэши и результаты сборки не входят в архив исходников. Последующие публичные обновления должны использовать этот постоянный сертификат. Предварительная `-dev.apk` имела другую подпись и не обновляется этим APK обычным способом.

## Android 14: функциональные проверки

Выделенный AOSP API 34 x86_64 AVD `splitbox-roadmap-api34`, KVM, 2 CPU / 2048 MiB RAM, 1080×1920 / density 420. Основные сценарии отказов и восстановления выполнены с production debug APK `dev.splitbox.app`; benchmark использует отдельный пакет. Личный Waydroid не изменялся.

Публичный APK `8d33d38c…` с постоянной подписью отдельно проверен на чистом AOSP API 34 x86_64 AVD `splitbox-publisher-api34`: установка APK, первый запуск, SAF-импорт raw APK, выдача разрешения, системное подтверждение и реальная установка `dev.splitbox.fixture` прошли. Package Manager подтвердил установленный fixture; `AndroidRuntime FATAL EXCEPTION` отсутствовал.

Предварительный R8 APK с development certificate (`9782b4a2…`) дополнительно установлен поверх debug с тем же сертификатом: прошли SAF-импорт raw APK, штатная очистка 11,3 КБ → 0 Б → IDLE и настоящая XAPK-установка через Shizuku с совпадением bytes OBB. В benchmark APK отдельно прошла ручная очистка после пересоздания MainActivity через NEW_TASK/CLEAR_TASK с сохранением PID. Это не измерение производительности.

| Сценарий | Фактический результат |
| --- | --- |
| Raw APK через SAF | Импорт собственного `application.apk`, digest совпал с независимым SHA-256; private `source.bin` используется без второй копии payload |
| System: разрешение и установка | Переход в Android Settings, возврат, подтверждение PackageInstaller, успешная установка fixture |
| System: process death в CONFIRMATION | После холодного запуска открыто сохранённое подтверждение; источник доступен |
| System: process death при запросе разрешения | После выдачи разрешения cold MAIN дождался актуальной проверки выбора и открыл одно подтверждение; отмена дала известный отказ |
| Ошибка записи ручного выбора | В тестовом 10-split APKS каталог вместо `checkpoint.tmp` вызвал настоящий отказ записи: новый выбор виден, установка заблокирована, последний подтверждённый checkpoint сохранён |
| Повтор сохранения | После устранения отказа точный выбор записан; холодный запуск восстановил его; проверены варианты с 2 и 3 выбранными APK |
| Состояние вкладок | Фактически введённый запрос `roadmapqa` сохранился точно при переходе История → Настройки → История |
| Шрифт 200% | Закреплённая кнопка повтора доступна без прокрутки и не перекрыта навигацией; повтор сохранения и cold restore прошли |
| Ошибка journal перед Android handoff | Read-only permissions на тестовом SharedPreferences directory вызвали видимое сообщение, без handoff и падения процесса; после восстановления записи повтор сохранил READY pointer и точный выбор |
| Ошибка очистки истории | При том же отказе записи сообщение осталось доступно, данные сохранены, процесс работал |
| Shizuku: XAPK и OBB | Shizuku 13.6.0.r1086, shell UID/UserService; реальная установка fixture завершена, bytes OBB в `Android/obb/dev.splitbox.fixture` совпали с исходным fixture |
| Общий журнал Root/Shizuku: холодное восстановление записи | В private storage внедрены корректные записи с backend `ROOT`, фазы CREATING, WRITING, COMMITTING, UNKNOWN, COMMITTED и FINISHED с успехом/отказом; все 7 вариантов дали ожидаемый UI и одну запись истории на UUID |

Проверка фаз — **fault injection сохранённых записей**, не kill реального Magisk в каждой фазе. Для CREATING/WRITING/COMMITTING/UNKNOWN история содержит `success=null`, UI не утверждает успех/отказ; новый UserService при восстановлении не запускался. Справка UNKNOWN открывается явной кнопкой, а не каждым onResume. Контролируемые ошибки файловой системы воспроизводят отказ записи, но не заменяют отдельный ENOSPC-тест на физическом устройстве.

10-split контейнер собран из всех APK собственного bundletool fixture без `toc.pb`; это проверка ручного выбора и persistence, не репрезентативный большой коммерческий пакет. Проверены совместимые density splits, несовместимые языки оставались заблокированы.

## Измерения

`BaselineProfileRule.productionJourneys` прошёл на этом AVD: реальные запуск, настройки/история и импорт, без синтетической Activity. Генератор выполнил 15 повторов и создал 46 487 правил, включая 1 072 строки с классами SplitBox (5 486 994 байта). В logcat на последнем повторе оставалось `Unstable profiles`: стандартный `strictStability=false` допускает успешный тест без стабилизации. Это не объявляется стабильным профилем. Собственный профиль **не интегрирован в релиз 1.0**; production содержит обычные профили зависимостей. Основание поведения генератора — [API BaselineProfileRule](https://developer.android.com/reference/kotlin/androidx/benchmark/macro/junit4/BaselineProfileRule).

Пять независимых сценариев `CompilationMode.None()` прошли по 3 повтора на этом AVD. Target APK SHA-256: `47cdc6176de284de58d84152b30f0f8fb759fccf851ec1e54b615228054cd657`, R8, profileable/testOnly, без собственного профиля. Font scale 1, портретная ориентация, SwiftShader, частота CPU не фиксировалась. EMULATOR подавлен явно; другие проверки AndroidX не подавлялись.

| Сценарий | Контрольные значения на эмуляторе |
| --- | --- |
| Холодный запуск | TTID median 857,12 мс, min 822,88 / max 947,58 мс |
| Настройки → история → установка | FrameDurationCpuMs P50 19,80 / P95 77,31 мс |
| Список 50 записей | FrameDurationCpuMs P50 9,14 / P95 26,30 мс |
| Список 500 записей | FrameDurationCpuMs P50 9,26 / P95 20,86 мс |
| Список 4096 записей | FrameDurationCpuMs P50 9,14 / P95 22,71 мс |

Процентили кадров взяты из `sampledMetrics` AndroidX по кадрам трёх повторов, это не процентили времени всей операции. Три запуска недостаточны для устойчивой оценки p95 старта, поэтому приведены отдельные min/median/max. Списки используют синтетическую metadata и локальное Compose-состояние; parser и persistence для тысяч реальных APK этими числами не измерены.

Холодный импорт дополнительно прошёл по 3 повтора в `None` и `Partial(BaselineProfileMode.Require, warmupIterations=0)` на одном target APK с указанным SHA-256. Оба JSON содержат `completed=true`, по 3 успешных наблюдения и пустой `failures`. Между сериями APK не пересобирался. В `Partial` отдельно прошли 3 холодных запуска: TTID min 815,58 / median 815,86 / max 824,06 мс. Режим Require принял упакованный профиль зависимостей; собственные правила SplitBox в него не добавлялись.

| Импорт: URI dispatch → наблюдаемый READY | None, мс | Partial, мс |
| --- | --- | --- |
| Итерация 0: пустые данные | 2384,80 | 2448,25 |
| Итерация 1: предыдущий READY сохранён | 2434,15 | 2393,78 |
| Итерация 2: предыдущий READY сохранён | 2399,01 | 2662,13 |

Исходные байты одинаковы, SHA-256 fixture `648f5089d3ecd1ec9815acc0a0d3ae116c45cb3f60c5ae5edcfa9fe5bf4e924e`; имена меняются по шаблону `application-{iteration}.apk`, чтобы предыдущий READY не мог удовлетворить ожидание нового импорта. Read grant обновляется после cold force-stop. Таймер включает dispatch/startup, импорт и накладные расходы наблюдения UIAutomator; это не parser CPU time. Между итерациями кэш не очищается: после серии None фактически оставались три private work directory. Штатная очистка проверена отдельно. Это смешанная серия первого импорта и повторных замен, а не универсальное p95 импорта. Эти короткие эмуляторные серии не доказывают ускорение от Partial или улучшение на телефоне. Методика, наборы и команды находятся в [benchmark/README](../benchmark/README.md).

## Ограничения проверки

Установка через реальный Magisk/KernelSU, физический ARM, Android 15/16 runtime, OEM/work profiles, многогигабайтный stress test и runtime 16 KiB не проверены. 16 KiB alignment APK/ELF проверяется статически при упаковке и не доказывает работу на соответствующем ядре. При шрифте 200% выполнена визуальная проверка, но речь и полный маршрут TalkBack на устройстве не проверялись; семантика этапов/заголовков проверена по коду.

Статус каждого GitHub-прогона доступен в [Actions](https://github.com/AnoY606-git/SplitBox/actions/workflows/ci.yml). Локальные проверки и эмуляторные числа не заменяют испытания на реальных устройствах. [TESTING](TESTING.md) описывает воспроизведение, [ARCHITECTURE](ARCHITECTURE.md) — контракты сохранения/восстановления и честный UNKNOWN без автоматического повторного commit.

</details>

</details>
