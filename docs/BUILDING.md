[English](en/BUILDING.md) · **Русский**

# Сборка

## Инструменты

| Инструмент | Версия |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| Android SDK | platform 36, build-tools 36.0.0, platform-tools |
| Python | 3 для release scripts; 3.12+ для Linux bootstrap |

Приложение: minSdk 34 / Android 14+, compile/target SDK 36. `gradle.properties` содержит единственный источник versionName/versionCode: сейчас `1.2.1` / `8`.

## С установленными JDK и SDK

Откройте корень проекта в Android Studio или задайте `JAVA_HOME` и `ANDROID_HOME`. Вместо SDK environment variable можно использовать локальный `local.properties` с `sdk.dir`.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :fixture:assembleDebug
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check_repository.py
```

Windows: `gradlew.bat`. Первая сборка загружает Gradle и Maven dependencies; runtime приложения интернет не использует. Gradle Wrapper входит в исходники, его JAR и distribution checksum проверяются.

## Локальный bootstrap для Linux

Для Linux x86_64 с Python 3.12+ и curl:

```bash
python3 scripts/bootstrap.py --accept-licenses
bash scripts/gradle.sh :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

JDK и Android command-line tools закреплены по URL/SHA-256 в `gradle/toolchain.json`; SDK packages устанавливаются через sdkmanager. Без `--accept-licenses` developer SDK licenses подтверждаются интерактивно. Bootstrap пишет в `.toolchain/`, не меняет глобальные инструменты и сохраняет существующий local.properties.

`scripts/gradle.sh` запускает Wrapper. JDK: `SPLITBOX_JAVA_HOME` → `JAVA_HOME` → локальный JDK → PATH. SDK: `SPLITBOX_ANDROID_HOME` → стандартные SDK variables → local.properties → локальный SDK. Заданные `ANDROID_USER_HOME`/`GRADLE_USER_HOME` сохраняются; иначе используются локальные каталоги в `.toolchain/`.

## Результат

| Сборка | Файл |
| --- | --- |
| Debug, автоматический Android debug key | `app/build/outputs/apk/debug/app-debug.apk` |
| Release без signing variables, R8/shrink | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Release с полными publisher credentials | `app/build/outputs/apk/release/app-release.apk` |

Unsigned APK требует подписи перед установкой. Настройка постоянного ключа, dev-артефакты и упаковка — в [RELEASING](RELEASING.md).

## Варианты измерений

Модуль `:benchmark` использует AndroidX Macrobenchmark/BaselineProfileRule 1.4.1. Для его сборки:

```bash
bash scripts/gradle.sh :app:assembleBenchmark :benchmark:assembleBenchmark \
  :app:assembleProfileGeneration :benchmark:assembleProfileGeneration
```

Оба target-варианта имеют `applicationId` `dev.splitbox.app.benchmark`, `testOnly=true`, `debuggable=false`, profileable-манифест и локальную debug-подпись. Они могут сосуществовать с `dev.splitbox.app`, но заменяют друг друга. `benchmark` включает R8/shrink и отдельную Activity для синтетических списков; `profileGeneration` сохраняет имена классов и не включает эту Activity. Для обоих вариантов явно задан ProfileInstaller 1.4.1; обычный release получает ProfileInstaller 1.4.0 транзитивно. Наличие этой библиотеки само по себе не подтверждает включение собственных правил SplitBox в профиль APK.

Release и target APK не запрашивают INTERNET. Отдельный APK test runner получает INTERNET от AndroidX Benchmark для localhost-соединения Perfetto; это разрешение не переносится в поставляемое приложение. Ручная установка target требует `adb install -t -r`.

Connected tests запускайте только на выделенном устройстве с явным `-Pandroid.injected.device.serial=DEVICE_SERIAL`; перед запуском проверьте его identity, потому что сценарии очищают данные тестового пакета. Полные команды, данные, отчёты и правила интерпретации — в [benchmark/README](../benchmark/README.md) и [TESTING](TESTING.md). Baseline Profile не включается в release автоматически по факту наличия модуля.

## Если сборка не запускается

- JDK/Unsupported class version: проверьте `java -version` и JDK 17 в Gradle settings Android Studio.
- SDK location not found: проверьте environment/local.properties; файл local.properties не входит в Git.
- Licenses not accepted: запустите sdkmanager licenses или bootstrap с осознанным принятием SDK licenses.
- Нет keystore: уберите частично заданные SPLITBOX signing variables для unsigned сборки либо задайте все четыре по инструкции выпуска.
- Нет binary fixtures: восстановите `core/src/test/resources/fixtures/` из исходников; общий запрет `*.apk` в Git скрывает нужные тестовые файлы.

Не запускайте два Gradle build одновременно в одном checkout.
