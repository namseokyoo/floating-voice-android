package com.sidequestlab.floatingvoice.core;

import java.util.Objects;

public final class GestureClassifier {
    public enum Action {
        DOWN,
        MOVE,
        TIMEOUT,
        UP,
        CANCEL
    }

    public enum Classification {
        NONE,
        TAP,
        DRAG,
        LONG_PRESS,
        CANCEL
    }

    private final float movementThresholdPx;
    private final long longPressThresholdMs;
    private boolean active;
    private boolean dragging;
    private boolean longPressEmitted;

    public GestureClassifier(float movementThresholdPx, long longPressThresholdMs) {
        if (movementThresholdPx <= 0) {
            throw new IllegalArgumentException("movementThresholdPx must be positive");
        }
        if (longPressThresholdMs <= 0) {
            throw new IllegalArgumentException("longPressThresholdMs must be positive");
        }
        this.movementThresholdPx = movementThresholdPx;
        this.longPressThresholdMs = longPressThresholdMs;
    }

    public Classification classify(Action action, long elapsedMs, float deltaX, float deltaY) {
        Objects.requireNonNull(action, "action");
        if (action == Action.DOWN) {
            active = true;
            dragging = false;
            longPressEmitted = false;
            return Classification.NONE;
        }
        if (action == Action.CANCEL) {
            boolean wasActive = active;
            reset();
            return wasActive ? Classification.CANCEL : Classification.NONE;
        }
        if (!active) {
            return Classification.NONE;
        }

        float distance = distance(deltaX, deltaY);
        if (action == Action.TIMEOUT) {
            if (dragging || longPressEmitted || distance >= movementThresholdPx) {
                return Classification.NONE;
            }
            if (elapsedMs >= longPressThresholdMs) {
                longPressEmitted = true;
                return Classification.LONG_PRESS;
            }
            return Classification.NONE;
        }
        if (action == Action.MOVE) {
            if (dragging) {
                return Classification.DRAG;
            }
            if (longPressEmitted) {
                return Classification.NONE;
            }
            if (distance >= movementThresholdPx) {
                dragging = true;
                return Classification.DRAG;
            }
            if (elapsedMs >= longPressThresholdMs) {
                longPressEmitted = true;
                return Classification.LONG_PRESS;
            }
            return Classification.NONE;
        }

        if (dragging || longPressEmitted) {
            reset();
            return Classification.NONE;
        }
        Classification classification;
        if (distance >= movementThresholdPx) {
            classification = Classification.DRAG;
        } else if (elapsedMs >= longPressThresholdMs) {
            classification = Classification.LONG_PRESS;
        } else {
            classification = Classification.TAP;
        }
        reset();
        return classification;
    }

    private void reset() {
        active = false;
        dragging = false;
        longPressEmitted = false;
    }

    private static float distance(float deltaX, float deltaY) {
        return Math.abs(deltaX) + Math.abs(deltaY);
    }
}
