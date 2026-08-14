package com.sidequestlab.floatingvoice;

/** Deterministic compact bounds for the centered speech-review window. */
public final class SpeechReviewWindowGeometry {
    public record Size(int widthPx, int heightPx) { }

    private SpeechReviewWindowGeometry() { }

    public static Size calculate(int screenWidthPx, int screenHeightPx, float density) {
        if (screenWidthPx <= 0 || screenHeightPx <= 0 || density <= 0f) {
            throw new IllegalArgumentException("screen bounds and density must be positive");
        }
        int sideInset = Math.round(16f * density);
        int maxWidth = Math.round(360f * density);
        int maxHeight = Math.round(520f * density);
        int width = Math.min(maxWidth, Math.max(1, screenWidthPx - sideInset * 2));
        int height = Math.min(maxHeight, Math.max(1, Math.round(screenHeightPx * 0.60f)));
        return new Size(width, height);
    }
}
