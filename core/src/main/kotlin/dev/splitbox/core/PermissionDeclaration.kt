// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.core

/** Декларация манифеста; ограничения SDK не означают, что разрешение выдано Android. */
data class PermissionDeclaration(val name: String, val minSdk: Int = 1, val maxSdk: Int? = null)
