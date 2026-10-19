// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer;

/** Прогресс передаёт только счётчик байтов; служба ограничивает обновления до десяти в секунду. */
oneway interface ITransferProgress {
    void onProgress(long bytes);
}
