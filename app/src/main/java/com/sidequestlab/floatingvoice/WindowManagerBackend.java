package com.sidequestlab.floatingvoice;

import android.view.View;
import android.view.WindowManager;

import java.util.Objects;

final class WindowManagerBackend
        implements OverlayWindowRegistry.Backend<View, WindowManager.LayoutParams> {
    private final WindowManager windowManager;

    WindowManagerBackend(WindowManager windowManager) {
        this.windowManager = Objects.requireNonNull(windowManager, "windowManager");
    }

    @Override public void add(View view, WindowManager.LayoutParams params) {
        windowManager.addView(view, params);
    }

    @Override public void update(View view, WindowManager.LayoutParams params) {
        windowManager.updateViewLayout(view, params);
    }

    @Override public void remove(View view) {
        windowManager.removeViewImmediate(view);
    }
}
