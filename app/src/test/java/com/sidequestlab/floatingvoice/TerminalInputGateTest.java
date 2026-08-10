package com.sidequestlab.floatingvoice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TerminalInputGateTest {
    @Test public void suppressesTouchesThatStartedBeforeOrAtTerminalCompletion() {
        TerminalInputGate gate = new TerminalInputGate();
        gate.suppressTouchesThrough(100L);

        assertTrue(gate.shouldSuppress(99L));
        assertTrue(gate.shouldSuppress(100L));
        assertFalse(gate.shouldSuppress(101L));
    }

    @Test public void suppressionBoundaryNeverMovesBackward() {
        TerminalInputGate gate = new TerminalInputGate();
        gate.suppressTouchesThrough(200L);
        gate.suppressTouchesThrough(150L);

        assertTrue(gate.shouldSuppress(200L));
        assertFalse(gate.shouldSuppress(201L));
    }
}
