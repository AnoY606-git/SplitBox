// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.fixture;
public final class FixtureActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        android.widget.TextView view = new android.widget.TextView(this);
        view.setText("SplitBox: тестовый пакет установлен успешно");
        view.setTextSize(24);
        view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(24, 24, 24, 24);
        setContentView(view);
    }
}
