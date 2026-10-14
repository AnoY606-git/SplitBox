// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.inspector

import android.content.pm.PackageManager

/** Проверяет только точное имя пакета. Ограниченная видимость Android не доказывает отсутствие. */
class AndroidInstalledPackageReader(private val packages: PackageManager) : InstalledPackageReader {
    override fun read(packageName: String): InstalledPackageResult = try {
        val info = packages.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        InstalledPackageResult.Found(PackageVersion(info.versionName.orEmpty(), info.longVersionCode))
    } catch (_: PackageManager.NameNotFoundException) {
        InstalledPackageResult.Unavailable
    } catch (_: RuntimeException) {
        // Включает SecurityException и ошибки связи с системным PackageManager.
        InstalledPackageResult.Failure
    }
}
