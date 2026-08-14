package com.sidequestlab.floatingvoice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpeechReviewWindowGeometryTest {
    @Test
    public void phoneWindowLeavesSideMarginsAndLimitsHeight() {
        SpeechReviewWindowGeometry.Size size =
                SpeechReviewWindowGeometry.calculate(360, 800, 1f);

        assertEquals(328, size.widthPx());
        assertEquals(528, size.heightPx());
    }

    @Test
    public void tabletWindowUsesCompactMaximums() {
        SpeechReviewWindowGeometry.Size size =
                SpeechReviewWindowGeometry.calculate(1200, 1600, 1f);

        assertEquals(360, size.widthPx());
        assertEquals(580, size.heightPx());
    }

    @Test
    public void landscapeWindowKeepsScrollableCompactHeight() {
        SpeechReviewWindowGeometry.Size size =
                SpeechReviewWindowGeometry.calculate(800, 360, 1f);

        assertEquals(360, size.widthPx());
        assertEquals(238, size.heightPx());
    }
}
