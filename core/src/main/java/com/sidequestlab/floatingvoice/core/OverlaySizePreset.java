package com.sidequestlab.floatingvoice.core;

/** User-selectable floating-control size. All presets remain at least 48 dp. */
public enum OverlaySizePreset {
    SMALL(56),
    MEDIUM(64),
    LARGE(72);

    private final int sizeDp;

    OverlaySizePreset(int sizeDp) {
        this.sizeDp = sizeDp;
    }

    public int sizeDp() {
        return sizeDp;
    }

    public static OverlaySizePreset fromStoredName(String value) {
        if (value != null) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ignored) {
                // Fall through to the stable default.
            }
        }
        return MEDIUM;
    }

    public static OverlaySizePreset fromPosition(int position) {
        OverlaySizePreset[] values = values();
        return position >= 0 && position < values.length ? values[position] : MEDIUM;
    }
}
