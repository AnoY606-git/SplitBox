#!/usr/bin/env python3
# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
"""Упаковка явно выбранного APK и полного архива исходников с нормализованными метаданными."""
import argparse
from pathlib import Path
import shutil
import subprocess
import zipfile
from release_support import audit_apk, java_command, sdk_home, sha256, source_archive, validate_repository, version

parser = argparse.ArgumentParser(description=__doc__)
mode = parser.add_mutually_exclusive_group(required=True)
mode.add_argument('--source-only', action='store_true', help='Создать только архив исходников, без APK')
mode.add_argument('--apk', type=Path, help='Явно выбранный APK; старые результаты сборки автоматически не подбираются')
parser.add_argument('--unsigned', action='store_true', help='Пометить неподписанную сборку как не готовую к установке')
parser.add_argument('--allow-development-key', action='store_true', help='Разрешить подпись Android Debug и добавить APK суффикс -dev')
parser.add_argument('--output-dir', type=Path, help='Каталог публикации; контрольные суммы относятся только к текущему запуску')
args = parser.parse_args()
if args.source_only and (args.unsigned or args.allow_development_key):
    parser.error('Параметры подписи требуют --apk')
if args.unsigned and args.allow_development_key:
    parser.error('--unsigned и --allow-development-key нельзя использовать вместе')
root = Path(__file__).resolve().parent.parent
dist = args.output_dir.resolve() if args.output_dir else root / 'dist'
dist.mkdir(parents=True, exist_ok=True)
files = validate_repository(root)
release_version = version(root)
artifacts = []
if args.apk:
    apk = args.apk.resolve()
    audit_apk(root, apk)
    suffix = ''
    if args.unsigned:
        with zipfile.ZipFile(apk) as archive, apk.open('rb') as stream:
            stream.seek(max(0, archive.start_dir - 16))
            if stream.read(16) == b'APK Sig Block 42' or any(n.upper().endswith(('.SF', '.RSA', '.DSA', '.EC')) for n in archive.namelist()):
                raise SystemExit('Нельзя пометить подписанный APK как неподписанный.')
        suffix = '-unsigned'
    else:
        signer = sdk_home(root) / 'build-tools/36.0.0/lib/apksigner.jar'
        result = subprocess.run([java_command(root), '-jar', str(signer), 'verify', '--verbose', '--print-certs', str(apk)],
                                check=True, capture_output=True, text=True)
        if 'CN=Android Debug' in result.stdout:
            if not args.allow_development_key:
                raise SystemExit('Обнаружена тестовая подпись. Используйте ключ издателя или явно разрешите APK с суффиксом -dev.')
            suffix = '-dev'
        signature = dist / f'SplitBox-{release_version}{suffix}.apk.signature.txt'
        signature.write_text(result.stdout)
        artifacts.append(signature)
    destination = dist / f'SplitBox-{release_version}{suffix}.apk'
    if apk != destination.resolve():
        shutil.copy2(apk, destination)
    artifacts.append(destination)
    print('APK: ' + destination.name)
archive = dist / f'SplitBox-{release_version}-source.zip'
source_archive(root, files, archive)
artifacts.append(archive)
# Удаляем старую неоднозначную запись; новые записи привязаны к конкретному имени APK.
(dist / 'APK-SIGNATURE.txt').unlink(missing_ok=True)
(dist / 'SHA256SUMS').write_text(''.join(f'{sha256(path)}  {path.name}\n' for path in artifacts))
print(f'Исходники: {archive.name}; файлов: {len(files)}; байт: {archive.stat().st_size:,}')
print((dist / 'SHA256SUMS').read_text())
