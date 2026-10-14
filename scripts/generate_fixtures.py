#!/usr/bin/env python3
# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
"""Повторная генерация контейнеров только из собственного тестового APK SplitBox (для Linux)."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile
from bootstrap import download, DOWNLOADS
from release_support import java_command, sdk_home

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, default=ROOT / 'core/src/test/resources/fixtures')
args = parser.parse_args()
output = args.output_dir.resolve()
output.mkdir(parents=True, exist_ok=True)
DOWNLOADS.mkdir(parents=True, exist_ok=True)
config = json.loads((ROOT / 'gradle/fixture-tools.json').read_text())
jar = download(config)
environment = {k: v for k, v in os.environ.items() if not k.startswith('SPLITBOX_KEY')}
environment.setdefault('ANDROID_USER_HOME', str(ROOT / '.toolchain/android-user'))
environment['SPLITBOX_ANDROID_HOME'] = str(sdk_home(ROOT))
environment['SPLITBOX_JAVA_HOME'] = str(Path(java_command(ROOT)).resolve().parent.parent)
subprocess.run(['bash', str(ROOT / 'scripts/gradle.sh'), ':fixture:assembleDebug', ':fixture:bundleRelease'],
               cwd=ROOT, env=environment, check=True)
apk = ROOT / 'fixture/build/outputs/apk/debug/fixture-debug.apk'
aab = ROOT / 'fixture/build/outputs/bundle/release/fixture-release.aab'
key = Path(environment['ANDROID_USER_HOME']) / 'debug.keystore'
with tempfile.TemporaryDirectory(prefix='fixture-generation-', dir=ROOT / '.toolchain') as work:
    generated = Path(work) / 'bundletool.apks'
    subprocess.run([java_command(ROOT), '-jar', str(jar), 'build-apks', '--bundle=' + str(aab),
                    '--output=' + str(generated), '--ks=' + str(key), '--ks-key-alias=androiddebugkey',
                    '--ks-pass=pass:android', '--key-pass=pass:android'], check=True, env=environment)
    shutil.copy2(generated, output / 'bundletool.apks')
shutil.copy2(apk, output / 'application.apk')
for name in ('flat.apks', 'flat.apkm', 'flat.xapk'):
    with zipfile.ZipFile(output / name, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.write(apk, 'base.apk')
        if name == 'flat.xapk':
            archive.writestr('manifest.json', json.dumps({'package_name': 'dev.splitbox.fixture', 'version_code': '1'}))
            archive.writestr('Android/obb/dev.splitbox.fixture/main.1.dev.splitbox.fixture.obb', b'SplitBox open-source OBB fixture\n')
print('Пять собственных тестовых пакетов созданы в каталоге ' + str(output))
