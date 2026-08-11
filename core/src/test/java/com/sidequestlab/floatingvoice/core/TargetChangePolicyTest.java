package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class TargetChangePolicyTest {
    @Test
    void resumesConnectionWhenAuthenticationIsNotReady() {
        assertEquals(TargetChangePolicy.Action.RESUME_CONNECTION,
                TargetChangePolicy.evaluate(false, false));
        assertEquals(TargetChangePolicy.Action.RESUME_CONNECTION,
                TargetChangePolicy.evaluate(false, true));
    }

    @Test
    void stopsRunningOverlayBeforeEditing() {
        assertEquals(TargetChangePolicy.Action.STOP_OVERLAY_THEN_EDIT,
                TargetChangePolicy.evaluate(true, true));
    }

    @Test
    void opensEditorWhenAuthenticatedAndIdle() {
        assertEquals(TargetChangePolicy.Action.OPEN_TARGET_EDITOR,
                TargetChangePolicy.evaluate(true, false));
    }
}
