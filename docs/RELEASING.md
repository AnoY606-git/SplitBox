# Выпуск версии

## Версия и проверки

`gradle.properties` — единственный источник версии. Текущее приложение: versionName `1.2`, versionCode `7`. У каждого выпускаемого обновления повышайте versionCode. versionName допускает `major.minor`, `major.minor.patch` и суффикс prerelease. Обновляйте CHANGELOG и выполняйте команду из [TESTING](TESTING.md) в чистом checkout.

CI (`.github/workflows/ci.yml`) выполняет source checks, Python/JVM tests, lint, debug/unsigned release и fixture build; проверяет APK policy и сохраняет результаты на 14 дней. Actions закреплены на commit SHA, permissions ограничены contents:read, checkout не сохраняет credentials. CI не публикует Release и не получает production signing secrets.

## Граница поставляемого приложения

Публикуйте обычный `release` с ID `dev.splitbox.app`. `benchmark`/`profileGeneration` — отдельные `testOnly` profileable-варианты с ID `dev.splitbox.app.benchmark` и development-подписью; их APK и test runner не подменяют релиз. В release не должно быть synthetic Activity или test hooks. Проверяйте merged manifest/APK, а не только исходный манифест: release и benchmark target не запрашивают INTERNET; INTERNET отдельного runner нужен AndroidX Benchmark для localhost Perfetto.

Если генерировался Baseline Profile, сохраните оригинальный вывод `BaselineProfileRule` и условия запуска, проверьте профиль до интеграции, затем пересоберите release и отдельно сравните compilation modes. Наличие тестовых вариантов не означает, что профиль включён в поставку или доказано ускорение. Методика — [benchmark/README](../benchmark/README.md).

## Подпись

Release без credentials остаётся unsigned; debug signer не подставляется автоматически. Для publisher release задайте все четыре переменные вместе:

| Переменная | Назначение |
| --- | --- |
| `SPLITBOX_KEYSTORE_PATH` | Путь к постоянному keystore вне публикуемых исходников |
| `SPLITBOX_KEYSTORE_PASSWORD` | Пароль хранилища |
| `SPLITBOX_KEY_ALIAS` | Alias |
| `SPLITBOX_KEY_PASSWORD` | Пароль ключа |

`SPLITBOX_KEYSTORE_TYPE` необязателен: по умолчанию JKS; для PKCS12 укажите тип явно. Частичная конфигурация/отсутствующий keystore останавливают Gradle без печати паролей. Передавайте пароли через окружение/secret manager; локально можно прочитать их через `read -r -s`, не записывая в shell history. После сборки уберите password variables из окружения.

```bash
./gradlew :app:assembleRelease
python3 scripts/package.py --apk app/build/outputs/apk/release/app-release.apk --output-dir dist/publish
(cd dist/publish && sha256sum -c SHA256SUMS)
```

Для локального preview с Android Debug certificate требуется явный `--allow-development-key`; имя получает суффикс `-dev`. Это не постоянная publisher-подпись. При смене сертификата обычное Android обновление не принимается: заранее определите переход, не удаляйте приложение и данные автоматически. Используйте один постоянный сертификат для последующих публичных релизов и храните его backup отдельно.

## Исходники и состав поставки

```bash
python3 scripts/check_repository.py
python3 scripts/package.py --source-only
python3 scripts/package.py --apk app/build/outputs/apk/release/app-release-unsigned.apk --unsigned
```

Упаковка принимает явный APK и проверяет applicationId, обе версии, SDK, permissions, CRC, ZIP/ELF 16 KiB alignment, legal assets и подпись. Unsigned APK помечается `-unsigned` и требует подписи перед установкой.

`SHA256SUMS` описывает только артефакты текущей команды: source ZIP и, при явном APK, этот APK и `<имя APK>.signature.txt`. Старые файлы из той же папки не подхватываются. Source ZIP включает Wrapper/JAR, CI, исходники и пять binary fixtures; SDK, keys, caches, logs и host metadata исключены. ZIP entries нормализованы для повторяемой упаковки исходников; побитовая воспроизводимость APK не заявляется.

Для каждого релиза используйте отдельную папку. В публичную поставку включите подписанный APK, соответствующие исходники, signature record, SHA256SUMS и заметки с проверенными сценариями/ограничениями. Сохраните LICENSE/NOTICE/авторство и уведомления зависимостей. Перед публикацией отдельно проверьте установку и обновление на устройстве. В заметках разделяйте настоящие APK fixtures и синтетические UI-наборы, emulator smoke и измерения на физическом устройстве. Не заявляйте physical ARM/Magisk/16 KiB runtime или выигрыш производительности без соответствующего прогона и исходных результатов. Версия `1.2` / code `7`, GPL-3.0-only и авторство AnoY606-git должны совпадать в APK, исходниках и документации.

## Репозиторий и теги

Публичный репозиторий — [AnoY606-git/SplitBox](https://github.com/AnoY606-git/SplitBox). Для разработки используйте обычный writable checkout:

```bash
git clone https://github.com/AnoY606-git/SplitBox.git
cd SplitBox
git status --short
```

Для изменения используйте свою Git identity и отдельную ветку. После проверок тег `vVERSION` должен указывать на тот же commit, из которого упакованы исходники. Публикуйте APK, source ZIP, signature record и SHA256SUMS вместе с описанием изменений и ограничений. Не загружайте keystore или signing secrets в репозиторий, Release assets и CI artifacts. Создание tag/Release выполняется отдельно и не является действием скрипта упаковки.

Обновление с прежним versionName `1.0` и новым versionCode `4` опубликовано отдельным тегом `v1.0-build4`. Существующие теги и файлы старого релиза не заменяются. Для следующих сборок используйте новый уникальный тег и явно указывайте номер сборки в заголовке релиза.

Релиз 1.1 использует тег `v1.1`, versionCode `5` и файлы `SplitBox-1.1.apk`, `SplitBox-1.1-source.zip`, `SplitBox-1.1.apk.signature.txt`, `SHA256SUMS`. Обновление с 1.0 сохраняет постоянный сертификат издателя.

Релиз 1.1.1 использует тег `v1.1.1`, versionCode `6` и файлы `SplitBox-1.1.1.apk`, `SplitBox-1.1.1-source.zip`, `SplitBox-1.1.1.apk.signature.txt`, `SHA256SUMS`. APK подписан прежним сертификатом; исходники и тег относятся к одному commit.

Релиз 1.2 использует тег `v1.2`, versionCode `7` и файлы `SplitBox-1.2.apk`, `SplitBox-1.2-source.zip`, `SplitBox-1.2.apk.signature.txt`, `SHA256SUMS`. Перед публикацией проверяются русский и английский интерфейс, выбор языка и автоматическая проверка выбранного Root/Shizuku.
