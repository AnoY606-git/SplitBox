# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
import hashlib
from pathlib import Path
import shutil
import sys
import tempfile
import subprocess
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release_support import REQUIRED_FILES, audit_apk, source_files, scan_text, source_archive, version


class ReleaseToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for name in REQUIRED_FILES:
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('тестовые исходники\n')

    def tearDown(self):
        self.temp.cleanup()

    def names(self):
        return {p.relative_to(self.root).as_posix() for p in source_files(self.root)}

    def test_keep_wrapper_ci_and_all_binary_fixtures(self):
        names = self.names()
        for name in ['gradle/wrapper/gradle-wrapper.jar', '.github/workflows/ci.yml',
                     'core/src/test/resources/fixtures/application.apk',
                     'core/src/test/resources/fixtures/bundletool.apks',
                     'core/src/test/resources/fixtures/flat.apks',
                     'core/src/test/resources/fixtures/flat.apkm',
                     'core/src/test/resources/fixtures/flat.xapk']:
            self.assertIn(name, names)

    def test_exclude_keys_caches_outputs_and_machine_configuration(self):
        forbidden = ['app/build/app.apk', 'scripts/__pycache__/x.pyc', 'docs/private.keystore',
                     'core/.gradle/state', '.toolchain/android-user/debug.keystore',
                     'local.properties', 'dist/old.apk', 'scripts/.env']
        for name in forbidden:
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('закрытые или сгенерированные данные')
        self.assertTrue(self.names().isdisjoint(forbidden))

    def test_missing_license_fails_instead_of_silently_shipping_incomplete_sources(self):
        (self.root / 'LICENSE').unlink()
        with self.assertRaisesRegex(ValueError, 'LICENSE'):
            source_files(self.root)

    def test_source_symlink_cannot_import_external_material(self):
        (self.root / 'docs' / 'leak.md').symlink_to(self.root / 'LICENSE')
        with self.assertRaisesRegex(ValueError, 'Символическая ссылка'):
            source_files(self.root)

    def test_private_key_marker_is_rejected(self):
        sample = '-----BEGIN ' + 'PRIVATE KEY-----'
        with self.assertRaisesRegex(ValueError, 'учётные данные'):
            scan_text(Path('config.txt'), sample)

    def test_access_token_is_rejected_without_printing_it(self):
        sample = 'gh' + 'p_' + 'a' * 36
        with self.assertRaisesRegex(ValueError, 'учётные данные') as error:
            scan_text(Path('config.txt'), sample)
        self.assertNotIn(sample, str(error.exception))

    def test_machine_home_path_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Личный путь'):
            scan_text(Path('notes.md'), '/' + 'home/alice/private/project')

    def test_source_archive_is_repeatable_and_preserves_gradlew_mode(self):
        files = source_files(self.root)
        first, second = self.root / 'first.zip', self.root / 'second.zip'
        source_archive(self.root, files, first)
        source_archive(self.root, files, second)
        self.assertEqual(hashlib.sha256(first.read_bytes()).digest(), hashlib.sha256(second.read_bytes()).digest())
        with zipfile.ZipFile(first) as z:
            self.assertIsNone(z.testzip())
            self.assertEqual(z.getinfo('SplitBox/gradlew').external_attr >> 16 & 0o777, 0o755)
            self.assertIn('SplitBox/.github/workflows/ci.yml', z.namelist())

    def test_source_archive_keeps_both_readmes_and_their_linked_documentation(self):
        documents = {
            'README.md': '# SplitBox\n[Русский](README.ru.md)\n[Documentation](docs/en/README.md)\n',
            'README.ru.md': '# SplitBox\n[English](README.md)\n[Документация](docs/README.md)\n',
            'docs/en/README.md': '# Documentation\n',
            'docs/README.md': '# Документация\n',
        }
        for name, content in documents.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        destination = self.root / 'source.zip'
        source_archive(self.root, source_files(self.root), destination)
        with zipfile.ZipFile(destination) as archive:
            for name, content in documents.items():
                entry = 'SplitBox/' + name
                self.assertIn(entry, archive.namelist())
                self.assertEqual(archive.read(entry), content.encode('utf-8'))

    def test_source_only_cli_packages_two_component_version_without_outputs_or_sdk(self):
        project = Path(__file__).resolve().parents[2]
        checkout = self.root / 'checkout'
        for path in source_files(project):
            destination = checkout / path.relative_to(project)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, destination)
        (checkout / 'gradle.properties').write_text('splitbox.versionName=1.0\nsplitbox.versionCode=2\n')
        output = self.root / 'publication'
        result = subprocess.run([sys.executable, str(checkout / 'scripts/package.py'), '--source-only',
                                 '--output-dir', str(output)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Исходники:', result.stdout)
        archives = list(output.glob('*-source.zip'))
        self.assertEqual(len(archives), 1)
        self.assertEqual(archives[0].name, 'SplitBox-1.0-source.zip')
        self.assertTrue((output / 'SHA256SUMS').is_file())

    def test_source_only_manifest_does_not_republish_old_apks_or_signature_record(self):
        project = Path(__file__).resolve().parents[2]
        output = self.root / 'publication'
        output.mkdir()
        for suffix in ('', '-dev', '-unsigned'):
            (output / f'SplitBox-{version(project)}{suffix}.apk').write_bytes(b'old APK')
        (output / 'APK-SIGNATURE.txt').write_text('Подпись предыдущего APK')
        result = subprocess.run([sys.executable, str(project / 'scripts/package.py'), '--source-only',
                                 '--output-dir', str(output)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        records = (output / 'SHA256SUMS').read_text().splitlines()
        self.assertEqual(len(records), 1)
        self.assertTrue(records[0].endswith('-source.zip'))
        self.assertFalse((output / 'APK-SIGNATURE.txt').exists())
        self.assertEqual(len(list(output.glob('*.apk'))), 3)

    def test_matching_version_name_cannot_hide_a_stale_version_code(self):
        (self.root / 'gradle.properties').write_text('splitbox.versionName=0.1.0\nsplitbox.versionCode=2\n')
        badging = "package: name='dev.splitbox.app' versionCode='1' versionName='0.1.0'\n" \
                  "sdkVersion:'34'\ntargetSdkVersion:'37'\n"
        with patch('release_support.subprocess.run', return_value=subprocess.CompletedProcess([], 0, badging)):
            with self.assertRaisesRegex(ValueError, 'versionCode'):
                audit_apk(self.root, self.root / 'stale.apk')

    def test_previous_target_sdk_is_rejected_even_with_matching_app_version(self):
        (self.root / 'gradle.properties').write_text('splitbox.versionName=1.2.2\nsplitbox.versionCode=9\n')
        badging = "package: name='dev.splitbox.app' versionCode='9' versionName='1.2.2'\n" \
                  "sdkVersion:'34'\ntargetSdkVersion:'36'\n"
        with patch('release_support.subprocess.run', return_value=subprocess.CompletedProcess([], 0, badging)):
            with self.assertRaisesRegex(ValueError, 'Версии SDK'):
                audit_apk(self.root, self.root / 'stale-target.apk')

    def test_current_target_sdk_reaches_the_permission_audit(self):
        (self.root / 'gradle.properties').write_text('splitbox.versionName=1.2.2\nsplitbox.versionCode=9\n')
        badging = "package: name='dev.splitbox.app' versionCode='9' versionName='1.2.2'\n" \
                  "sdkVersion:'34'\ntargetSdkVersion:'37'\n"
        outputs = [subprocess.CompletedProcess([], 0, badging), subprocess.CompletedProcess([], 0, '')]
        with patch('release_support.subprocess.run', side_effect=outputs):
            with self.assertRaisesRegex(ValueError, 'Разрешения APK'):
                audit_apk(self.root, self.root / 'current-target.apk')

    def test_non_positive_version_code_is_rejected(self):
        (self.root / 'gradle.properties').write_text('splitbox.versionName=0.1.0\nsplitbox.versionCode=0\n')
        with self.assertRaisesRegex(ValueError, 'versionCode'):
            version(self.root)


if __name__ == '__main__':
    unittest.main()
