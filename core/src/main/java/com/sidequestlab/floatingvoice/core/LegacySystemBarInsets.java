package com.sidequestlab.floatingvoice.core;

/** Pure API-29 fallback policy for inferring system-bar edges from display geometry. */
public final class LegacySystemBarInsets {
    public record Insets(int left, int top, int right, int bottom) {
        public Insets {
            if (left < 0 || top < 0 || right < 0 || bottom < 0) {
                throw new IllegalArgumentException("Insets must be non-negative");
            }
        }
    }

    private LegacySystemBarInsets() {
    }

    public static Insets resolve(
            int realWidth,
            int realHeight,
            int usableWidth,
            int usableHeight,
            int rotationQuarterTurns,
            int statusBarHeight,
            int fallbackNavigationBarSize) {
        int top = Math.max(0, statusBarHeight);
        int widthGap = Math.max(0, realWidth - Math.max(0, usableWidth));
        int heightGap = Math.max(0, realHeight - Math.max(0, usableHeight));
        int left = 0;
        int right = 0;
        int bottom;

        if (widthGap > 0) {
            if (rotationQuarterTurns == 3) {
                left = widthGap;
            } else {
                right = widthGap;
            }
            bottom = Math.max(0, heightGap - top);
        } else {
            bottom = Math.max(Math.max(0, fallbackNavigationBarSize),
                    Math.max(0, heightGap - top));
        }
        return new Insets(left, top, right, bottom);
    }
}
