#!/usr/bin/env bash
# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
SPLITBOX_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -n "${SPLITBOX_JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$SPLITBOX_JAVA_HOME"
elif [[ -z "${JAVA_HOME:-}" && -x "$SPLITBOX_ROOT/.toolchain/jdk/bin/java" ]]; then
    export JAVA_HOME="$SPLITBOX_ROOT/.toolchain/jdk"
fi
if [[ -n "${SPLITBOX_ANDROID_HOME:-}" ]]; then
    export ANDROID_HOME="$SPLITBOX_ANDROID_HOME"
elif [[ -z "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
elif [[ -z "${ANDROID_HOME:-}" && ! -f "$SPLITBOX_ROOT/local.properties" && -d "$SPLITBOX_ROOT/.toolchain/android-sdk" ]]; then
    export ANDROID_HOME="$SPLITBOX_ROOT/.toolchain/android-sdk"
fi
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$SPLITBOX_ROOT/.toolchain/android-user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$SPLITBOX_ROOT/.toolchain/gradle-home}"
mkdir -p -- "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
cd -- "$SPLITBOX_ROOT"
exec "$SPLITBOX_ROOT/gradlew" --console=plain "$@"
