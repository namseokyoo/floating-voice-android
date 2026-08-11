package com.sidequestlab.floatingvoice.core;

/** Decides how Settings may enter transfer-destination editing. */
public final class TargetChangePolicy {
    public enum Action {
        RESUME_CONNECTION,
        STOP_OVERLAY_THEN_EDIT,
        OPEN_TARGET_EDITOR
    }

    private TargetChangePolicy() {
    }

    public static Action evaluate(boolean authenticationReady, boolean overlayRunning) {
        if (!authenticationReady) return Action.RESUME_CONNECTION;
        return overlayRunning ? Action.STOP_OVERLAY_THEN_EDIT : Action.OPEN_TARGET_EDITOR;
    }
}
