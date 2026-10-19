#!/usr/bin/env python3
# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
"""Создать собственные большие APK/APKS для ручной регрессии; не запускает установку."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

from release_support import java_command, sdk_home, sha256

ROOT = Path(__file__).resolve().parent.parent
MIB = 1024 * 1024


def run(*command):
    subprocess.run([str(part) for part in command], check=True)


def fill(archive, name, size, compressed=False):
    # Данные действительно записываются: это проверка I/O, а не только больших чисел в ZIP.
    entry = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    entry.file_size = size
    entry.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
    block = bytes(MIB)
    with archive.open(entry, 'w') as stream:
        while size:
            count = min(len(block), size)
            stream.write(memoryview(block)[:count])
            size -= count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True, help='Новый каталог на диске с запасом места')
    parser.add_argument('--key', type=Path, required=True, help='Только Android debug.keystore (пароль android)')
    parser.add_argument('--apk-mib', type=int, default=400)
    parser.add_argument('--split-mib', type=int, default=1536)
    parser.add_argument('--split-count', type=int, default=6)
    parser.add_argument('--compressed-bundle', action='store_true', help='Дополнительно создать сжатый APKS для проверки отмены распаковки')
    parser.add_argument('--obb-gib', type=int, default=0, help='Дополнительно создать XAPK со сжатым нулевым OBB указанного объёма (0–64 ГиБ)')
    args = parser.parse_args()
    if not (1 <= args.apk_mib <= 2047 and 1 <= args.split_mib <= 2047 and 1 <= args.split_count <= 64):
        parser.error('Размер каждого APK: 1–2047 МиБ; частей: 1–64.')
    if not 0 <= args.obb_gib <= 64:
        parser.error('Размер OBB: 0–64 ГиБ.')
    output = args.output_dir.resolve()
    if output.exists():
        parser.error('Каталог результата уже существует; выберите новый, чтобы не перезаписать файлы.')
    output.parent.mkdir(parents=True, exist_ok=True)
    required = (args.apk_mib * 3 + args.split_mib * (args.split_count * 2 + 2)) * MIB
    if shutil.disk_usage(output.parent).free < required + 1024 * MIB:
        parser.error('Недостаточно свободного места для APK, временных файлов и APKS.')
    sdk = sdk_home(ROOT)
    build_tools = sdk / 'build-tools/36.0.0'
    key = args.key.resolve()
    if not key.is_file():
        parser.error('Debug key не найден.')
    source = ROOT / 'core/src/test/resources/fixtures/application.apk'
    output.mkdir()
    java = java_command(ROOT)

    def sign(unsigned, target):
        aligned = output / 'aligned.tmp.apk'
        run(build_tools / 'zipalign', '-f', '4', unsigned, aligned)
        run(java, '-Xmx512m', '-jar', build_tools / 'lib/apksigner.jar', 'sign',
            '--ks', key, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
            '--key-pass', 'pass:android', '--min-sdk-version', '34',
            '--v1-signing-enabled', 'false', '--v4-signing-enabled', 'false', '--out', target, aligned)
        run(java, '-Xmx512m', '-jar', build_tools / 'lib/apksigner.jar', 'verify', target)
        aligned.unlink()
        unsigned.unlink()

    unsigned = output / 'unsigned.tmp.apk'
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(unsigned, 'w') as archive:
        for entry in original.infolist():
            if not entry.filename.startswith('META-INF/'):
                archive.writestr(entry, original.read(entry))
        for index in range(6000):
            archive.writestr('assets/entries/item-%05d.txt' % index, b'SplitBox QA\n')
        fill(archive, 'assets/payload.bin', args.apk_mib * MIB)
    large_apk = output / 'many-entries.apk'
    sign(unsigned, large_apk)

    # Внешний контейнер становится ZIP64; внутренние APK остаются обычными ZIP для Android.
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(unsigned, 'w') as archive:
        for entry in original.infolist():
            if not entry.filename.startswith('META-INF/'):
                archive.writestr(entry, original.read(entry))
    base = output / 'base.apk'
    sign(unsigned, base)
    parts = [base]
    for index in range(args.split_count):
        split = 'payload%d' % index
        manifest = output / 'manifest.tmp.xml'
        manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
package="dev.splitbox.fixture" split="%s" android:versionCode="1" android:versionName="1.0"
android:isFeatureSplit="true"><uses-sdk android:minSdkVersion="34" android:targetSdkVersion="36"/>
<application android:hasCode="false"/></manifest>''' % split)
        run(build_tools / 'aapt2', 'link', '--manifest', manifest, '-I', sdk / 'platforms/android-36/android.jar', '-o', unsigned)
        with zipfile.ZipFile(unsigned, 'a') as archive:
            fill(archive, 'assets/%s.bin' % split, args.split_mib * MIB)
        target = output / (split + '.apk')
        sign(unsigned, target)
        manifest.unlink()
        parts.append(target)
        print('Подготовлена часть %d/%d' % (index + 1, args.split_count), flush=True)
    bundle = output / 'large.apks'
    with zipfile.ZipFile(bundle, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        for part in parts:
            archive.write(part, part.name)
    extra = []
    if args.compressed_bundle:
        compressed = output / 'compressed.apks'
        with zipfile.ZipFile(compressed, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
            for part in parts:
                archive.write(part, part.name)
        extra.append(compressed)
    if args.obb_gib:
        xapk = output / 'large-obb.xapk'
        with zipfile.ZipFile(xapk, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(base, 'base.apk')
            archive.writestr('manifest.json', json.dumps({'package_name': 'dev.splitbox.fixture', 'version_code': '1'}))
            fill(archive, 'Android/obb/dev.splitbox.fixture/main.1.dev.splitbox.fixture.obb',
                 args.obb_gib * 1024 * MIB, compressed=True)
        extra.append(xapk)
    evidence = {}
    for path in [large_apk, bundle, *parts, *extra]:
        with zipfile.ZipFile(path) as archive:
            evidence[path.name] = {'bytes': path.stat().st_size, 'entries': len(archive.infolist()),
                                  'expandedBytes': sum(entry.file_size for entry in archive.infolist()), 'sha256': sha256(path)}
    (output / 'dataset.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print('Готово: ' + str(output), flush=True)


if __name__ == '__main__':
    main()
