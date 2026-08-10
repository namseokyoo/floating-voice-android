package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GestureClassifierTest {
    private static final float MOVEMENT_THRESHOLD_PX = 12f;
    private static final long LONG_PRESS_MS = 600L;

    @Test
    void movementThresholdIsExclusiveForTapAndInclusiveForDrag() {
        GestureClassifier below = new GestureClassifier(MOVEMENT_THRESHOLD_PX, LONG_PRESS_MS);
        below.classify(GestureClassifier.Action.DOWN, 0, 0, 0);
        assertEquals(GestureClassifier.Classification.TAP,
                below.classify(GestureClassifier.Action.UP, 100, 11.99f, 0));

        GestureClassifier boundary = new GestureClassifier(MOVEMENT_THRESHOLD_PX, LONG_PRESS_MS);
        boundary.classify(GestureClassifier.Action.DOWN, 0, 0, 0);
        assertEquals(GestureClassifier.Classification.DRAG,
                boundary.classify(GestureClassifier.Action.UP, 100, 12f, 0));
    }

    @Test
    void longPressThresholdIsExclusiveForTapAndInclusiveForLongPress() {
        GestureClassifier below = new GestureClassifier(MOVEMENT_THRESHOLD_PX, LONG_PRESS_MS);
        below.classify(GestureClassifier.Action.DOWN, 0, 0, 0);
        assertEquals(GestureClassifier.Classification.TAP,
                below.classify(GestureClassifier.Action.UP, 599, 0, 0));

        GestureClassifier boundary = new GestureClassifier(MOVEMENT_THRESHOLD_PX, LONG_PRESS_MS);
        boundary.classify(GestureClassifier.Action.DOWN, 0, 0, 0);
        assertEquals(GestureClassifier.Classification.LONG_PRESS,
                boundary.classify(GestureClassifier.Action.UP, 600, 0, 0));
    }

    @Test
    void longPressSuppressesTheSubsequentActionUpClick() {
        GestureClassifier classifier = new GestureClassifier(MOVEMENT_THRESHOLD_PX, LONG_PRESS_MS);
        classifier.classify(GestureClassifier.Action.DOWN, 0, 0, 0);

        assertEquals(GestureClassifier.Classification.LONG_PRESS,
                classifier.classify(GestureClassifier.Action.MOVE, 600, 0, 0));
        assertEquals(GestureClassifier.Classification.NONE,
                classifier.classify(GestureClassifier.Action.UP, 650, 0, 0));
    }

    @Test
    void dragRemainsADragAfterCrossingTheMovementThreshold() {
        GestureClassifier classifier = new GestureClassifier(MOVEMENT_THRESHOLD_PX, LONG_PRESS_MS);
        classifier.classify(GestureClassifier.Action.DOWN, 0, 0, 0);

        assertEquals(GestureClassifier.Classification.DRAG,
                classifier.classify(GestureClassifier.Action.MOVE, 50, 6, 6));
        assertEquals(GestureClassifier.Classification.DRAG,
                classifier.classify(GestureClassifier.Action.MOVE, 100, 1, 1));
        assertEquals(GestureClassifier.Classification.NONE,
                classifier.classify(GestureClassifier.Action.UP, 150, 1, 1));
    }

    @Test
    void stationaryTimeoutEmitsLongPressBeforeFingerUp() {
        GestureClassifier classifier = new GestureClassifier(MOVEMENT_THRESHOLD_PX, LONG_PRESS_MS);
        classifier.classify(GestureClassifier.Action.DOWN, 0, 0, 0);

        assertEquals(GestureClassifier.Classification.NONE,
                classifier.classify(GestureClassifier.Action.TIMEOUT, 599, 0, 0));
        assertEquals(GestureClassifier.Classification.LONG_PRESS,
                classifier.classify(GestureClassifier.Action.TIMEOUT, 600, 0, 0));
        assertEquals(GestureClassifier.Classification.NONE,
                classifier.classify(GestureClassifier.Action.UP, 650, 0, 0));
    }

    @Test
    void dragOrCancelPreventsTimeoutLongPress() {
        GestureClassifier drag = new GestureClassifier(MOVEMENT_THRESHOLD_PX, LONG_PRESS_MS);
        drag.classify(GestureClassifier.Action.DOWN, 0, 0, 0);
        drag.classify(GestureClassifier.Action.MOVE, 100, 12, 0);
        assertEquals(GestureClassifier.Classification.NONE,
                drag.classify(GestureClassifier.Action.TIMEOUT, 600, 12, 0));

        GestureClassifier canceled = new GestureClassifier(MOVEMENT_THRESHOLD_PX, LONG_PRESS_MS);
        canceled.classify(GestureClassifier.Action.DOWN, 0, 0, 0);
        canceled.classify(GestureClassifier.Action.CANCEL, 100, 0, 0);
        assertEquals(GestureClassifier.Classification.NONE,
                canceled.classify(GestureClassifier.Action.TIMEOUT, 600, 0, 0));
    }

    @Test
    void actionCancelClearsThePendingGesture() {
        GestureClassifier classifier = new GestureClassifier(MOVEMENT_THRESHOLD_PX, LONG_PRESS_MS);
        classifier.classify(GestureClassifier.Action.DOWN, 0, 0, 0);

        assertEquals(GestureClassifier.Classification.CANCEL,
                classifier.classify(GestureClassifier.Action.CANCEL, 200, 0, 0));
        assertEquals(GestureClassifier.Classification.NONE,
                classifier.classify(GestureClassifier.Action.UP, 250, 0, 0));

        classifier.classify(GestureClassifier.Action.DOWN, 0, 0, 0);
        assertEquals(GestureClassifier.Classification.TAP,
                classifier.classify(GestureClassifier.Action.UP, 100, 0, 0));
    }
}
