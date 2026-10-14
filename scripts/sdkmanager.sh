#!/usr/bin/env bash
# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
SPLITBOX_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SPLITBOX_SDK="${SPLITBOX_ANDROID_HOME:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$SPLITBOX_ROOT/.toolchain/android-sdk}}}"
SPLITBOX_JAVA="${SPLITBOX_JAVA_HOME:-${JAVA_HOME:-$SPLITBOX_ROOT/.toolchain/jdk}}/bin/java"
if [[ ! -x "$SPLITBOX_JAVA" ]]; then SPLITBOX_JAVA="$(command -v java)"; fi
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$SPLITBOX_ROOT/.toolchain/android-user}"
SPLITBOX_CMDLINE="$SPLITBOX_SDK/cmdline-tools/19.0"
if [[ ! -f "$SPLITBOX_CMDLINE/lib/sdkmanager-classpath.jar" ]]; then SPLITBOX_CMDLINE="$SPLITBOX_SDK/cmdline-tools/latest"; fi
exec "$SPLITBOX_JAVA" "-Dcom.android.sdklib.toolsdir=$SPLITBOX_CMDLINE" \
  -classpath "$SPLITBOX_CMDLINE/lib/sdkmanager-classpath.jar" \
  com.android.sdklib.tool.sdkmanager.SdkManagerCli "--sdk_root=$SPLITBOX_SDK" "$@"
