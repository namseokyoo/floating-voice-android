package com.sidequestlab.floatingvoice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public final class OverlaySizePresetTest {
    @Test void exposesThreeTouchSafeSizes() {
        assertEquals(56, OverlaySizePreset.SMALL.sizeDp());
        assertEquals(64, OverlaySizePreset.MEDIUM.sizeDp());
        assertEquals(72, OverlaySizePreset.LARGE.sizeDp());
    }

    @Test void restoresStoredValueAndFallsBackToMedium() {
        assertEquals(OverlaySizePreset.SMALL, OverlaySizePreset.fromStoredName("SMALL"));
        assertEquals(OverlaySizePreset.LARGE, OverlaySizePreset.fromStoredName("LARGE"));
        assertEquals(OverlaySizePreset.MEDIUM, OverlaySizePreset.fromStoredName("unknown"));
        assertEquals(OverlaySizePreset.MEDIUM, OverlaySizePreset.fromStoredName(null));
    }

    @Test void mapsSpinnerPositionsDeterministically() {
        assertEquals(OverlaySizePreset.SMALL, OverlaySizePreset.fromPosition(0));
        assertEquals(OverlaySizePreset.MEDIUM, OverlaySizePreset.fromPosition(1));
        assertEquals(OverlaySizePreset.LARGE, OverlaySizePreset.fromPosition(2));
        assertEquals(OverlaySizePreset.MEDIUM, OverlaySizePreset.fromPosition(-1));
        assertEquals(OverlaySizePreset.MEDIUM, OverlaySizePreset.fromPosition(3));
    }
}
