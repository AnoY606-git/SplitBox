#!/usr/bin/env python3
# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
"""Небольшой скрипт проверки интерфейса с понятным кодом. Отказывается работать с физическим Android-устройством."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / '.toolchain/qa'
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args, binary=False):
    return subprocess.run([*ADB, *args], check=True, capture_output=True,
                          text=not binary, timeout=30).stdout


def hierarchy():
    adb('shell', 'uiautomator', 'dump', '/sdcard/splitbox-qa.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/splitbox-qa.xml'))


def snapshot(name):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', name):
        raise SystemExit('Имя снимка должно содержать от 1 до 80 латинских букв ASCII, цифр, знаков подчёркивания или дефисов.')
    tree = hierarchy()
    (OUT / (name + '.xml')).write_text(ET.tostring(tree, encoding='unicode'))
    (OUT / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    for node in tree.iter('node'):
        label = node.get('text') or node.get('content-desc')
        if label:
            print(label.replace('\n', ' / '), node.get('bounds'))


def tap(label):
    tree = hierarchy()
    matches = [n for n in tree.iter('node') if n.get('text') == label or n.get('content-desc') == label]
    if not matches:
        raise SystemExit('Элемент интерфейса не найден: ' + label)
    node = matches[0]
    coords = [int(v) for v in re.findall(r'\d+', node.get('bounds', ''))]
    if len(coords) != 4:
        raise SystemExit('Некорректные границы элемента интерфейса')
    adb('shell', 'input', 'tap', str((coords[0] + coords[2]) // 2), str((coords[1] + coords[3]) // 2))


parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['snapshot', 'tap', 'swipe', 'back', 'assert-text'], help='Действие: снимок, нажатие, прокрутка, возврат или проверка текста')
parser.add_argument('value', nargs='?', help='Имя снимка, текст элемента/проверки или направление жеста: down/up')
parser.add_argument('--snapshot', help='Сохранить снимок с указанным именем после действия')
parser.add_argument('--serial', default=os.environ.get('SPLITBOX_TEST_SERIAL'), help='Явный серийный номер тестового эмулятора')
parser.add_argument('--adb-port', default=os.environ.get('SPLITBOX_ADB_PORT', '5037'), help='Порт сервера ADB (по умолчанию SPLITBOX_ADB_PORT или 5037)')
args = parser.parse_args()
if not args.serial:
    parser.error('Требуется --serial или SPLITBOX_TEST_SERIAL')
ADB = [os.environ.get('SPLITBOX_ADB', 'adb'), '-P', args.adb_port, '-s', args.serial]
if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
    raise SystemExit('Скрипт проверки работает только с эмулятором (ro.kernel.qemu=1).')
if args.action == 'snapshot':
    snapshot(args.value or 'screen')
elif args.action == 'tap':
    tap(args.value)
elif args.action == 'swipe':
    node = hierarchy().find('node')
    bounds = [int(v) for v in re.findall(r'\d+', node.get('bounds', ''))] if node is not None else []
    if len(bounds) != 4:
        raise SystemExit('Не удалось определить область экрана эмулятора')
    x, upper, lower = str(bounds[2] // 2), str(bounds[3] * 3 // 10), str(bounds[3] * 3 // 4)
    start, end = (upper, lower) if args.value == 'down' else (lower, upper)
    adb('shell', 'input', 'swipe', x, start, x, end, '450')
elif args.action == 'back':
    adb('shell', 'input', 'keyevent', '4')
elif args.action == 'assert-text':
    text = '\n'.join(n.get('text', '') for n in hierarchy().iter('node'))
    if args.value not in text:
        raise SystemExit('Ожидаемый текст отсутствует: ' + args.value)
    print('Проверка пройдена: интерфейс содержит ' + args.value)
if args.snapshot:
    snapshot(args.snapshot)
