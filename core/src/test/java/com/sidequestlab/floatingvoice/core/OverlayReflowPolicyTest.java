package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class OverlayReflowPolicyTest {
    @Test
    void reflowsAllStatesThatShowTheIdleBubble() {
        assertEquals(OverlayReflowPolicy.Action.REFLOW_IDLE,
                OverlayReflowPolicy.actionFor(OverlayStateMachine.State.IDLE));
        assertEquals(OverlayReflowPolicy.Action.REFLOW_IDLE,
                OverlayReflowPolicy.actionFor(OverlayStateMachine.State.VOICE_QUEUEING));
        assertEquals(OverlayReflowPolicy.Action.REFLOW_IDLE,
                OverlayReflowPolicy.actionFor(OverlayStateMachine.State.VOICE_PENDING));
        assertEquals(OverlayReflowPolicy.Action.REFLOW_IDLE,
                OverlayReflowPolicy.actionFor(OverlayStateMachine.State.TEXT_QUEUEING));
        assertEquals(OverlayReflowPolicy.Action.REFLOW_IDLE,
                OverlayReflowPolicy.actionFor(OverlayStateMachine.State.TEXT_PENDING));
    }

    @Test
    void preservesRecordingAndClosesAnOpenMenu() {
        assertEquals(OverlayReflowPolicy.Action.REFLOW_RECORDING,
                OverlayReflowPolicy.actionFor(OverlayStateMachine.State.RECORDING));
        assertEquals(OverlayReflowPolicy.Action.CLOSE_MENU_AND_REFLOW_IDLE,
                OverlayReflowPolicy.actionFor(OverlayStateMachine.State.MENU_OPEN));
    }

    @Test
    void ignoresTransientHiddenAndTeardownStates() {
        assertEquals(OverlayReflowPolicy.Action.NONE,
                OverlayReflowPolicy.actionFor(OverlayStateMachine.State.VOICE_STARTING));
        assertEquals(OverlayReflowPolicy.Action.NONE,
                OverlayReflowPolicy.actionFor(OverlayStateMachine.State.TEXT_COMPOSING));
        assertEquals(OverlayReflowPolicy.Action.NONE,
                OverlayReflowPolicy.actionFor(OverlayStateMachine.State.TEARING_DOWN));
    }
}
