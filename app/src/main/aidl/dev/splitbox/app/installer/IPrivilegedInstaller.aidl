// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer;
import android.os.ParcelFileDescriptor;
import dev.splitbox.app.installer.ITransferProgress;

/** Ограниченный протокол установки: без произвольных команд и путей к закрытым файлам приложения. */
interface IPrivilegedInstaller {
    int createSession(int userId, long totalBytes, boolean downgrade) = 0;
    void writeApk(int sessionId, int index, long size, in ParcelFileDescriptor source, ITransferProgress progress) = 1;
    String commitSession(int sessionId) = 2;
    void abandonSession(int sessionId) = 3;
    String copyObb(int userId, String packageName, long version, String basename, long size, in ParcelFileDescriptor source, ITransferProgress progress) = 4;
    void attachClient(IBinder token) = 5;
    void destroy() = 16777114;
}
