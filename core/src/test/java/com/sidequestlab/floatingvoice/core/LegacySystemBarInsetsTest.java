package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class LegacySystemBarInsetsTest {
    @Test
    void reservesBottomNavigationInPortrait() {
        LegacySystemBarInsets.Insets insets = LegacySystemBarInsets.resolve(
                1080, 2400, 1080, 2208, 0, 72, 144);

        assertEquals(new LegacySystemBarInsets.Insets(0, 72, 0, 144), insets);
    }

    @Test
    void reservesRightNavigationAtNinetyDegreeRotation() {
        LegacySystemBarInsets.Insets insets = LegacySystemBarInsets.resolve(
                2400, 1080, 2256, 1080, 1, 0, 144);

        assertEquals(new LegacySystemBarInsets.Insets(0, 0, 144, 0), insets);
    }

    @Test
    void reservesLeftNavigationAtTwoHundredSeventyDegreeRotation() {
        LegacySystemBarInsets.Insets insets = LegacySystemBarInsets.resolve(
                2400, 1080, 2256, 1080, 3, 0, 144);

        assertEquals(new LegacySystemBarInsets.Insets(144, 0, 0, 0), insets);
    }

    @Test
    void keepsConservativeBottomFallbackWhenUsableSizeIsUnavailable() {
        LegacySystemBarInsets.Insets insets = LegacySystemBarInsets.resolve(
                1080, 2400, 1080, 2400, 0, 72, 144);

        assertEquals(new LegacySystemBarInsets.Insets(0, 72, 0, 144), insets);
    }
}
