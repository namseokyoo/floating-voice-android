package com.sidequestlab.floatingvoice.core;

import java.util.Objects;

/** Chooses how a visible overlay should respond after display or inset geometry changes. */
public final class OverlayReflowPolicy {
    public enum Action {
        REFLOW_IDLE,
        REFLOW_RECORDING,
        CLOSE_MENU_AND_REFLOW_IDLE,
        NONE
    }

    private OverlayReflowPolicy() {
    }

    public static Action actionFor(OverlayStateMachine.State state) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case IDLE, VOICE_QUEUEING, VOICE_PENDING, TEXT_QUEUEING, TEXT_PENDING ->
                    Action.REFLOW_IDLE;
            case RECORDING -> Action.REFLOW_RECORDING;
            case MENU_OPEN -> Action.CLOSE_MENU_AND_REFLOW_IDLE;
            default -> Action.NONE;
        };
    }
}
