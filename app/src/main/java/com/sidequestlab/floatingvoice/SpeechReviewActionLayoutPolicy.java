package com.sidequestlab.floatingvoice;

/** Pure responsive policy for the speech-review action rows. */
public final class SpeechReviewActionLayoutPolicy {
    private SpeechReviewActionLayoutPolicy() { }

    public static boolean shouldStack(float fontScale) {
        if (fontScale <= 0f) throw new IllegalArgumentException("fontScale must be positive");
        return fontScale >= 1.5f;
    }
}
