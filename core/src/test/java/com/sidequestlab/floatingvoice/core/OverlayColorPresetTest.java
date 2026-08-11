package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class OverlayColorPresetTest {
    @Test
    public void defaultsToSageForMissingOrUnknownStoredName() {
        assertEquals(OverlayColorPreset.SAGE, OverlayColorPreset.fromStoredName(null));
        assertEquals(OverlayColorPreset.SAGE, OverlayColorPreset.fromStoredName(""));
        assertEquals(OverlayColorPreset.SAGE, OverlayColorPreset.fromStoredName("BROKEN"));
    }

    @Test
    public void restoresEveryPersistedPreset() {
        for (OverlayColorPreset preset : OverlayColorPreset.values()) {
            assertEquals(preset, OverlayColorPreset.fromStoredName(preset.name()));
        }
    }

    @Test
    public void mapsSpinnerPositionsAndFallsBackAtTheEdges() {
        assertEquals(OverlayColorPreset.SAGE, OverlayColorPreset.fromPosition(-1));
        assertEquals(OverlayColorPreset.SAGE, OverlayColorPreset.fromPosition(0));
        assertEquals(OverlayColorPreset.OCEAN, OverlayColorPreset.fromPosition(1));
        assertEquals(OverlayColorPreset.VIOLET, OverlayColorPreset.fromPosition(2));
        assertEquals(OverlayColorPreset.AMBER, OverlayColorPreset.fromPosition(3));
        assertEquals(OverlayColorPreset.SAGE, OverlayColorPreset.fromPosition(4));
    }
}
