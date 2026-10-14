#!/usr/bin/env python3
# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
"""Проверка состава публикуемых исходников и, при необходимости, собранного APK."""
import argparse
import json
from pathlib import Path
from release_support import audit_apk, validate_repository

parser = argparse.ArgumentParser()
parser.add_argument('--apk', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
try:
    files = validate_repository(root)
    print(f'Исходники проверены: файлов — {len(files)}; проверены Gradle Wrapper, тестовые пакеты, лицензии и шаблоны учётных данных и путей.')
    if args.apk:
        print(json.dumps(audit_apk(root, args.apk.resolve()), indent=2))
except (ValueError, OSError) as error:
    raise SystemExit(str(error)) from error
