package com.sidequestlab.floatingvoice.core;

public enum OverlayColorPreset {
    SAGE,
    OCEAN,
    VIOLET,
    AMBER;

    public static OverlayColorPreset fromStoredName(String storedName) {
        if (storedName == null || storedName.isBlank()) return SAGE;
        try {
            return valueOf(storedName);
        } catch (IllegalArgumentException ignored) {
            return SAGE;
        }
    }

    public static OverlayColorPreset fromPosition(int position) {
        OverlayColorPreset[] presets = values();
        return position >= 0 && position < presets.length ? presets[position] : SAGE;
    }
}
