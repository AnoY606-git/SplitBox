# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
"""Общие проверки исходников и выпускаемого APK средствами стандартной библиотеки."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

ROOT_FILES = ('.editorconfig', '.gitattributes', '.gitignore', 'README.md', 'README.ru.md', 'LICENSE', 'NOTICE',
              'AUTHORS.md', 'ATTRIBUTION.md', 'THIRD_PARTY_NOTICES.md', 'CONTRIBUTING.md',
              'SECURITY.md', 'CHANGELOG.md', 'settings.gradle.kts', 'build.gradle.kts',
              'gradle.properties', 'gradlew', 'gradlew.bat')
SOURCE_DIRS = ('app', 'core', 'fixture', 'benchmark', 'gradle', 'scripts', 'docs', '.github')
FIXTURES = tuple('core/src/test/resources/fixtures/' + name for name in
                 ('application.apk', 'bundletool.apks', 'flat.apks', 'flat.apkm', 'flat.xapk'))
REQUIRED_FILES = ROOT_FILES + FIXTURES + (
    'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
    'gradle/toolchain.json', '.github/workflows/ci.yml', 'app/build.gradle.kts',
    'benchmark/build.gradle.kts', 'benchmark/src/main/AndroidManifest.xml',
    'benchmark/src/benchmark/java/dev/splitbox/benchmark/SplitBoxMacrobenchmark.kt',
    'benchmark/src/profileGeneration/java/dev/splitbox/benchmark/SplitBoxBaselineProfile.kt',
    'benchmark/src/main/java/dev/splitbox/benchmark/Journeys.kt',
    'benchmark/src/main/res/xml/fixture_paths.xml',
    'app/src/benchmark/AndroidManifest.xml', 'app/src/profileGeneration/AndroidManifest.xml',
    'benchmark/README.md',
    'app/src/benchmark/java/dev/splitbox/app/benchmark/SplitListBenchmarkActivity.kt',
    'app/src/main/AndroidManifest.xml', 'core/build.gradle.kts', 'fixture/build.gradle.kts',
    'docs/README.md', 'docs/ARCHITECTURE.md', 'docs/BUILDING.md', 'docs/TESTING.md',
    'docs/RELEASING.md', 'docs/VERIFICATION.md',
)
EXCLUDED_DIRS = {'build', '.gradle', '.kotlin', '.toolchain', '.git', '.idea', '.vscode',
                 '.signing', '.aws', '.codex', '.agents', '__pycache__', 'dist'}
EXCLUDED_SUFFIXES = {'.jks', '.keystore', '.p12', '.pfx', '.key', '.pem', '.pyc', '.pyo',
                     '.hprof', '.log', '.tmp', '.bak', '.iml'}


def source_files(root):
    result = []
    candidates = [root / name for name in ROOT_FILES]
    for name in SOURCE_DIRS:
        folder = root / name
        if folder.is_symlink():
            raise ValueError('Символическая ссылка в исходниках недопустима: ' + name)
        if folder.exists():
            candidates.extend(folder.rglob('*'))
    for path in candidates:
        relative = path.relative_to(root)
        if set(relative.parts) & EXCLUDED_DIRS:
            continue
        if path.suffix.lower() in EXCLUDED_SUFFIXES or path.name in {'local.properties', 'keystore.properties', 'signing.properties'}:
            continue
        if path.name == '.env' or path.name.startswith('.env.'):
            continue
        if path.is_symlink():
            raise ValueError('Символическая ссылка в исходниках недопустима: ' + relative.as_posix())
        if path.is_file():
            result.append(path)
    names = {p.relative_to(root).as_posix() for p in result}
    missing = sorted(set(REQUIRED_FILES) - names)
    if missing:
        raise ValueError('Отсутствуют обязательные файлы исходников: ' + ', '.join(missing))
    return sorted(set(result))


def scan_text(path, text):
    patterns = (r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
                r'gh[pousr]_[A-Za-z0-9]{20,}', r'github_pat_[A-Za-z0-9_]{20,}', r'AKIA[A-Z0-9]{16}')
    if any(re.search(pattern, text) for pattern in patterns):
        raise ValueError('Возможные учётные данные в файле ' + str(path) + ' (значение скрыто)')
    if re.search(r'/(?:home|Users)/[A-Za-z0-9_.-]+/', text):
        raise ValueError('Личный путь, привязанный к компьютеру, в файле ' + str(path))


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def version_properties(root):
    return dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines()
                if '=' in line and not line.lstrip().startswith('#'))


def version_code(root):
    code = version_properties(root)['splitbox.versionCode'].strip()
    if not re.fullmatch(r'[1-9]\d*', code) or int(code) > 2147483647:
        raise ValueError('Некорректный splitbox.versionCode: требуется положительное целое число Android')
    return int(code)


def version(root):
    values = version_properties(root)
    name = values['splitbox.versionName'].strip()
    if not re.fullmatch(r'\d+\.\d+(?:\.\d+)?(?:-[A-Za-z0-9.-]+)?', name):
        raise ValueError('Некорректный splitbox.versionName')
    version_code(root)
    return name


def validate_repository(root):
    files = source_files(root)
    for path in files:
        rel = path.relative_to(root).as_posix()
        binary = rel in FIXTURES or rel == 'gradle/wrapper/gradle-wrapper.jar' or (
            path.suffix == '.png' and (rel.startswith('docs/images/') or rel.startswith('fixture/src/main/res/')))
        if not binary:
            try:
                scan_text(Path(rel), path.read_text(encoding='utf-8'))
            except UnicodeDecodeError as error:
                raise ValueError('Неожиданный двоичный файл в исходниках: ' + rel) from error
    tools = json.loads((root / 'gradle/toolchain.json').read_text())
    if sha256(root / 'gradle/wrapper/gradle-wrapper.jar') != tools['wrapperJarSha256']:
        raise ValueError('Не совпадает контрольная сумма JAR-файла Gradle Wrapper')
    for source, asset in [('LICENSE', 'licenses/GPL-3.0.txt'), ('NOTICE', 'NOTICE'),
                          ('ATTRIBUTION.md', 'ATTRIBUTION.md'), ('THIRD_PARTY_NOTICES.md', 'THIRD_PARTY_NOTICES.md')]:
        if (root / source).read_bytes() != (root / 'app/src/main/assets' / asset).read_bytes():
            raise ValueError('Юридический документ в ресурсах приложения отличается от корневого файла: ' + source)
    version(root)
    return files


def source_archive(root, files, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix('.zip.tmp')
    with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            relative = path.relative_to(root).as_posix()
            entry = zipfile.ZipInfo('SplitBox/' + relative, date_time=(1980, 1, 1, 0, 0, 0))
            mode = 0o755 if relative == 'gradlew' or path.suffix == '.sh' else 0o644
            entry.external_attr = (0o100000 | mode) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, path.read_bytes(), compresslevel=9)
    temporary.replace(destination)


def sdk_home(root):
    configured = os.environ.get('SPLITBOX_ANDROID_HOME') or os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if configured:
        return Path(configured)
    local = root / 'local.properties'
    if local.exists():
        for line in local.read_text().splitlines():
            if line.startswith('sdk.dir='):
                return Path(line.partition('=')[2].replace('\\:', ':').replace('\\\\', '\\'))
    return root / '.toolchain/android-sdk'


def java_command(root):
    for home in (os.environ.get('SPLITBOX_JAVA_HOME'), os.environ.get('JAVA_HOME'), str(root / '.toolchain/jdk')):
        if home:
            executable = Path(home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
            if executable.is_file():
                return str(executable)
    executable = shutil.which('java')
    if not executable:
        raise ValueError('JDK 17 не найден; задайте JAVA_HOME или запустите bootstrap.py')
    return executable


def audit_apk(root, apk):
    tools = sdk_home(root) / 'build-tools/36.0.0'
    aapt = tools / ('aapt.exe' if os.name == 'nt' else 'aapt')
    badging = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True).stdout
    expected = version(root)
    package_line = re.search(r'^package: (.+)$', badging, flags=re.MULTILINE)
    metadata = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)='([^']*)'", package_line[1])) if package_line else {}
    if metadata.get('name') != 'dev.splitbox.app' or metadata.get('versionName') != expected:
        raise ValueError('applicationId или версия APK не соответствуют этим исходникам')
    if metadata.get('versionCode') != str(version_code(root)):
        raise ValueError('versionCode APK не соответствует этим исходникам')
    if "sdkVersion:'34'" not in badging or "targetSdkVersion:'37'" not in badging:
        raise ValueError('Версии SDK в APK не соответствуют требованиям проекта')
    permission_text = subprocess.run([str(aapt), 'dump', 'permissions', str(apk)], check=True, capture_output=True, text=True).stdout
    permissions = set(re.findall(r"uses-permission: name='([^']+)'", permission_text))
    allowed = {'android.permission.REQUEST_INSTALL_PACKAGES', 'dev.splitbox.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',
               'moe.shizuku.manager.permission.API_V23'}
    if permissions != allowed:
        raise ValueError('Разрешения APK не соответствуют требованиям проекта: ' + ', '.join(sorted(permissions)))
    zipalign = tools / ('zipalign.exe' if os.name == 'nt' else 'zipalign')
    subprocess.run([str(zipalign), '-c', '-P', '16', '4', str(apk)], check=True, capture_output=True)
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError('Ошибка проверки CRC архива APK')
        for source, asset in [('LICENSE', 'licenses/GPL-3.0.txt'), ('NOTICE', 'NOTICE'), ('ATTRIBUTION.md', 'ATTRIBUTION.md')]:
            if archive.read('assets/' + asset) != (root / source).read_bytes():
                raise ValueError('Юридический документ в APK не соответствует исходникам: ' + source)
        for entry in archive.infolist():
            if not entry.filename.endswith('.so'):
                continue
            data = archive.read(entry)
            if data[:4] != b'\x7fELF':
                raise ValueError('Нативная библиотека не является файлом ELF: ' + entry.filename)
            endian = '<' if data[5] == 1 else '>'
            if data[4] == 2:
                offset = struct.unpack_from(endian + 'Q', data, 32)[0]
                size, count = struct.unpack_from(endian + 'HH', data, 54)
                align_offset, align_type = 48, 'Q'
            else:
                offset = struct.unpack_from(endian + 'I', data, 28)[0]
                size, count = struct.unpack_from(endian + 'HH', data, 42)
                align_offset, align_type = 28, 'I'
            for index in range(count):
                start = offset + size * index
                if struct.unpack_from(endian + 'I', data, start)[0] == 1 and struct.unpack_from(endian + align_type, data, start + align_offset)[0] < 16384:
                    raise ValueError('Нативная библиотека ELF не имеет выравнивания 16 КиБ: ' + entry.filename)
    return {'version': expected, 'versionCode': version_code(root), 'sha256': sha256(apk), 'bytes': apk.stat().st_size, 'permissions': sorted(permissions)}
