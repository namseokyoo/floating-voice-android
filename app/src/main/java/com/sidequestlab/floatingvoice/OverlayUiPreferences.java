package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.SharedPreferences;

import com.sidequestlab.floatingvoice.core.OverlayColorPreset;
import com.sidequestlab.floatingvoice.core.OverlaySizePreset;

/** Non-sensitive visual preferences shared by the setup Activity and overlay service. */
final class OverlayUiPreferences {
    static final String KEY_SIZE = "floating_control_size";
    static final String KEY_COLOR = "floating_control_color";
    private static final String PREFS = "overlay_ui_preferences";

    private final SharedPreferences preferences;

    OverlayUiPreferences(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    OverlaySizePreset sizePreset() {
        return OverlaySizePreset.fromStoredName(
                preferences.getString(KEY_SIZE, OverlaySizePreset.MEDIUM.name()));
    }

    void setSizePreset(OverlaySizePreset preset) {
        if (preset == null || preset == sizePreset()) return;
        preferences.edit().putString(KEY_SIZE, preset.name()).apply();
    }

    OverlayColorPreset colorPreset() {
        return OverlayColorPreset.fromStoredName(
                preferences.getString(KEY_COLOR, OverlayColorPreset.SAGE.name()));
    }

    void setColorPreset(OverlayColorPreset preset) {
        if (preset == null || preset == colorPreset()) return;
        preferences.edit().putString(KEY_COLOR, preset.name()).apply();
    }

    void register(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    void unregister(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
