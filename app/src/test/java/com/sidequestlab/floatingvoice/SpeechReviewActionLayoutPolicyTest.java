package com.sidequestlab.floatingvoice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SpeechReviewActionLayoutPolicyTest {
    @Test
    public void normalFontKeepsCompactTwoColumnRows() {
        assertFalse(SpeechReviewActionLayoutPolicy.shouldStack(1.0f));
        assertFalse(SpeechReviewActionLayoutPolicy.shouldStack(1.49f));
    }

    @Test
    public void largeAccessibilityFontStacksActionsWithoutClipping() {
        assertTrue(SpeechReviewActionLayoutPolicy.shouldStack(1.5f));
        assertTrue(SpeechReviewActionLayoutPolicy.shouldStack(2.0f));
    }

    @Test
    public void rejectsInvalidFontScale() {
        assertThrows(IllegalArgumentException.class,
                () -> SpeechReviewActionLayoutPolicy.shouldStack(0f));
    }
}
