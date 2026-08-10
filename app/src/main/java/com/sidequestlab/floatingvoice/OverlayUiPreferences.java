package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.SharedPreferences;

import com.sidequestlab.floatingvoice.core.OverlaySizePreset;

/** Non-sensitive visual preferences shared by the setup Activity and overlay service. */
final class OverlayUiPreferences {
    static final String KEY_SIZE = "floating_control_size";
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

    void register(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    void unregister(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
